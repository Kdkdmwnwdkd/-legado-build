package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.highlight.HighlightRules
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isOnLineTxt
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.CacheBook
import io.legado.app.model.localBook.epubcore.template.EpubTemplateDocument
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import splitties.init.appCtx
import java.util.UUID

/** Supplies ordinary chapters to Direct without opening an archive or inventing a TOC. */
object TextReaderSessionProvider {
    fun open(book: Book, source: BookSource?, revision: Long, isCurrent: () -> Boolean): EpubDirectSession {
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
        check(chapters.isNotEmpty()) { "未找到章节目录" }
        val chapterIndex = TextReaderChapterIndex(chapters)
        // Rule imports and external edits may not return through the reader's settings activity.
        val contentProcessor = ContentProcessor.get(book).also { it.upReplaceRules() }
        // Reopening the same book must not reuse pending WebView resource requests.
        val host = "text-" + MD5Utils.md5Encode16(book.bookUrl) + "-" + UUID.randomUUID().toString().take(8) + ".epub.local"
        val owner = SupervisorJob()
        val registry = TextReaderImageRegistry()
        fun checkedChapter(index: Int) = checkNotNull(chapterIndex.chapter(index)) { "章节已更新，请刷新目录" }.also {
            check(isCurrent()) { "正文已更新，请重试" }
            check(chapterIndex.matches(index, appDb.bookChapterDao.getChapter(book.bookUrl, index))) {
                "章节目录已更新，请重试"
            }
        }
        val images = TextReaderImageResources(owner, isCurrent, load = { path ->
            val request = checkNotNull(registry.lookup(path)) { "图片所属章节已释放" }
            val chapter = checkedChapter(request.chapterIndex)
            check(chapter.url == request.chapterUrl) { "图片所属章节已更新" }
            TextReaderImageLoader.load(book, source, chapter, request.image, request.managedBubble)
                .also { checkedChapter(request.chapterIndex) }
        }, onFailure = { path, error ->
            AppLog.putDebug("普通正文图片加载失败: book=${book.name}, chapter=${path.split('/').getOrNull(1)}", error)
        })
        fun imageResource(path: String, headOnly: Boolean): EpubDirectResource? {
            if (!path.startsWith("text-image/") || path.length > 256) return null
            val stateRequest = path.endsWith("/state")
            val key = if (stateRequest) path.removeSuffix("/state") else path
            // No database, URL analysis or source script runs on interception.
            if (registry.lookup(key) == null) return null
            return if (stateRequest) images.state(key, start = !headOnly) else images.resource(key, headOnly)
        }
        return EpubDirectSession(
            bookUrl = book.bookUrl,
            resourceHost = host,
            sourceRevision = revision,
            chapterLoader = { index, config ->
                runBlocking(owner + Dispatchers.IO) {
                    withTimeout(25_000L) {
                        val chapter = checkedChapter(index)
                        val raw = if (chapter.isVolume) "" else BookHelp.getContent(book, chapter)
                            ?: if (!book.isLocal && source != null) {
                                CacheBook.getOrCreate(source, book).downloadAwait(chapter, failOnError = true)
                            } else error(if (book.isLocal) "无法读取章节正文" else "没有可用的书源")
                        ensureActive()
                        checkedChapter(index)
                        val content = contentProcessor.prepareDirectContent(book, chapter, raw,
                            checkActive = { ensureActive() }).document
                        ensureActive()
                        checkedChapter(index)
                        val sourceActionsEnabled = book.isOnLineTxt && source?.bookSourceUrl == book.origin
                        val managedBubble = AppConfig.forceSoftwareParagraphBubble
                        val renderRevision = MD5Utils.md5Encode(buildString {
                            append(imageConfigRevision()).append('|').append(managedBubble).append('|')
                            append(config.readerFontRevision).append('|').append(config.readerFontPath).append('|')
                            append(config.textFontWeight).append('|').append(config.textFontItalic)
                        })
                        val chapterImages = content.images()
                        ensureActive()
                        checkedChapter(index)
                        val preparedImages = TextReaderBubblePreparation(
                            render = TextReaderImageLoader::renderBubble,
                            onFailure = { _, error ->
                                AppLog.putDebug("普通正文气泡预生成失败，继续异步加载: book=${book.name}, chapter=$index", error)
                            }
                        ).prepare(chapterImages, managedBubble)
                        ensureActive()
                        checkedChapter(index)
                        val imageUrls = HashMap<String, String>()
                        val requests = ArrayList<TextReaderImageRequest>()
                        chapterImages.forEach { image ->
                            preparedImages[image.id]?.let { prepared ->
                                imageUrls[image.id] = prepared.dataUri
                                return@forEach
                            }
                            val data = TextReaderImageSource.browserDataImage(image.renderSource)
                            val converted = data != null && TextReaderSourceBubblePolicy.resolve(
                                image.source, data, image.sourceStyle, image.click, enabled = managedBubble
                            ) != null
                            if (data != null && !converted) imageUrls[image.id] = data
                            else requests.add(TextReaderImageRequest(index, chapter.url, image, renderRevision, managedBubble))
                        }
                        val registered = registry.register(requests)
                        registered.forEach { (path, request) ->
                            imageUrls[request.image.id] = EpubDirectSession.baseUrl(path, host)
                        }
                        val sourceHtml = content.htmlWithImages(includeTitle = true, sourceActionsEnabled = sourceActionsEnabled,
                            deferredImages = true, preparedImages = preparedImages) { image ->
                            checkNotNull(imageUrls[image.id])
                        }
                        val html = HighlightRules.decorate(sourceHtml, book.bookUrl, false, host, config)
                        val href = "text/" + index + "/" + MD5Utils.md5Encode16(chapter.url + html) + ".html"
                        EpubDirectDocumentBuilder.build(
                            chapterIndex = index,
                            href = href,
                            title = content.title,
                            sourceHtml = html,
                            config = config,
                            density = appCtx.resources.displayMetrics.density,
                            resourceHost = host
                        ).copy(
                            plainText = content.plainText(includeTitle = true),
                            sourceChapterUrl = chapter.url,
                            sourceImages = TextReaderSourceImages(
                                sourceKey = source?.bookSourceUrl,
                                onlineText = book.isOnLineTxt,
                                actions = if (sourceActionsEnabled) content.imageActions() else emptyMap(),
                                resources = registered
                            )
                        ).let { prepared ->
                            config.readerTemplate?.let { template ->
                                EpubTemplateDocument.wrap(prepared, html, template)
                            } ?: prepared
                        }.also {
                            ensureActive()
                            checkedChapter(index)
                        }
                    }
                }
            },
            resourceLoader = { path, _ -> HighlightRules.resource(path) ?: imageResource(path, headOnly = false) },
            headResourceLoader = { path, _ -> HighlightRules.resource(path, true) ?: imageResource(path, headOnly = true) },
            linkResolver = { _, _ -> null },
            readableChapterResolver = { index, _ ->
                chapterIndex.resolve(index)
            },
            adjacentChapterResolver = chapterIndex::adjacent,
            closeAction = { images.close(); registry.close(); owner.cancel() },
            onCloseRequested = { images.close(); registry.close(); owner.cancel() },
            chapterRevisionProvider = {
                imageConfigRevision() + "|replace:" + contentProcessor.directReplacementRevision(book) +
                    "|highlight:" + HighlightRules.store.revision() +
                    "|assets:" + io.legado.app.help.reader.ReaderAssets.store.revision()
            }
        )
    }

    private fun imageConfigRevision(): String {
        val entry = BubblePackageManager.currentEntry()
        return MD5Utils.md5Encode(buildString {
            append(entry.dirName).append('|').append(entry.config.updatedAt).append('|')
            append(entry.config.sizeScale).append('|').append(AppConfig.isNightTheme).append('|')
            append(AppConfig.forceSoftwareParagraphBubble).append('|').append(ReadBookConfig.textFont)
        })
    }
}
