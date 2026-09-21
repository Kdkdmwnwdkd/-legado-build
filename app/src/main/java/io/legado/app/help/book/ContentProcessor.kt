package io.legado.app.help.book

import android.os.Build
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.AppPattern.spaceRegex
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.exception.RegexTimeoutException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.epubcore.direct.TextReaderDocument
import io.legado.app.model.localBook.epubcore.direct.TextReaderReplacementProcessor
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.escapeRegex
import io.legado.app.utils.replace
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.lang.ref.WeakReference
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

class ContentProcessor private constructor(
    private val bookName: String,
    private val bookOrigin: String
) {

    companion object {
        private val processors = hashMapOf<Pair<String, String>, WeakReference<ContentProcessor>>()
        private val ruleRevision = AtomicLong()
        private val isAndroid8 = Build.VERSION.SDK_INT in 26..27

        fun get(book: Book) = get(book.name, book.origin)

        @Synchronized
        fun get(bookName: String, bookOrigin: String): ContentProcessor {
            val key = bookName to bookOrigin
            val processorWr = processors[key]
            var processor: ContentProcessor? = processorWr?.get()
            if (processor == null) {
                processor = ContentProcessor(bookName, bookOrigin)
                processors[key] = WeakReference(processor)
            }
            return processor
        }

        fun upReplaceRules() {
            val readingBook = ReadBook.book
            val readingBookUrl = readingBook?.bookUrl
            val readingProcessor = if (readingBook != null && !readingBook.isEpub && readingBook.usesDirectReader) {
                ReadBook.contentProcessor?.takeIf {
                    it.bookName == readingBook.name && it.bookOrigin == readingBook.origin
                }
            } else null
            val previousRevision = readingProcessor?.ruleSnapshot?.revision
            val current = synchronized(this) { processors.values.mapNotNull { it.get() } }
            current.forEach { it.upReplaceRules() }
            if (readingProcessor != null && previousRevision != readingProcessor.ruleSnapshot.revision) {
                // Subscription updates have no activity result callback. Start a new reader request
                // on Main, after rechecking ownership; instance refreshes intentionally do not reload.
                ReadBook.launch {
                    val book = ReadBook.book ?: return@launch
                    if (book.bookUrl != readingBookUrl || ReadBook.contentProcessor !== readingProcessor ||
                        book.name != readingProcessor.bookName || book.origin != readingProcessor.bookOrigin ||
                        book.isEpub || !book.usesDirectReader || previousRevision == readingProcessor.ruleSnapshot.revision
                    ) return@launch
                    ReadBook.reloadCurrentContent("replace-rules-updated")
                }
            }
        }

    }

    private data class RuleSnapshot(
        val title: List<ReplaceRule>,
        val content: List<ReplaceRule>,
        val direct: TextReaderReplacementProcessor.Rules,
        val revision: Long
    )

    data class DirectContent(
        val document: TextReaderDocument.Content,
        val effectiveReplaceRules: List<ReplaceRule>?
    )

    @Volatile
    private var ruleSnapshot = RuleSnapshot(emptyList(), emptyList(), TextReaderReplacementProcessor.Rules(), 0L)
    private val directProcessor = TextReaderReplacementProcessor()
    val removeSameTitleCache = hashSetOf<String>()

    init {
        upReplaceRules()
        upRemoveSameTitle()
    }

    @Synchronized
    fun upReplaceRules() {
        val (title, content) = appDb.runInTransaction(Callable {
            appDb.replaceRuleDao.findEnabledByTitleScope(bookName, bookOrigin) to
                appDb.replaceRuleDao.findEnabledByContentScope(bookName, bookOrigin)
        })
        val direct = TextReaderReplacementProcessor.Rules(title.map(::directRule), content.map(::directRule))
        val changed = direct != ruleSnapshot.direct
        val revision = if (changed) ruleRevision.incrementAndGet() else ruleSnapshot.revision
        // Both scopes become visible together; refreshes never expose an empty intermediate list.
        ruleSnapshot = RuleSnapshot(title, content, direct, revision)
        if (changed) directProcessor.invalidate()
    }

    private fun upRemoveSameTitle() {
        val book = appDb.bookDao.getBookByOrigin(bookName, bookOrigin) ?: return
        removeSameTitleCache.clear()
        val files = BookHelp.getChapterFiles(book).filter {
            it.endsWith("nr")
        }
        removeSameTitleCache.addAll(files)
    }

    fun getTitleReplaceRules(): List<ReplaceRule> {
        return ruleSnapshot.title
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun getContentReplaceRules(): List<ReplaceRule> {
        return ruleSnapshot.content
    }

    fun directReplacementRevision(book: Book, useReplace: Boolean = true): String =
        "${useReplace && book.getUseReplaceRule()}:${ruleSnapshot.revision}"

    fun prepareDirectContent(
        book: Book,
        chapter: BookChapter,
        source: String,
        useReplace: Boolean = true,
        checkActive: () -> Unit = {}
    ): DirectContent {
        val snapshot = ruleSnapshot
        val enabled = useReplace && book.getUseReplaceRule()
        val replaceBook by lazy { book.toReplaceBook() }
        val prepared = directProcessor.prepare(
            source = TextReaderReplacementProcessor.Source(
                book.bookUrl, chapter.url, chapter.index, chapter.title, source
            ),
            rules = snapshot.direct,
            enabled = enabled,
            replaceRegex = { rule, value ->
                value.replace(rule.name, rule.regex, rule.replacement, rule.timeoutMillis, chapter, replaceBook)
            },
            onRuleError = { rule, error -> reportDirectRuleError(rule, error) },
            checkActive = {
                checkActive()
                if (snapshot.revision != ruleSnapshot.revision || enabled != (useReplace && book.getUseReplaceRule())) {
                    throw CancellationException("替换规则已更新")
                }
            }
        )
        return DirectContent(
            prepared.content,
            if (enabled) snapshot.content.filter { it.id in prepared.effectiveContentRuleIds } else null
        )
    }

    private fun directRule(rule: ReplaceRule) = TextReaderReplacementProcessor.Rule(
        rule.id, rule.name, rule.pattern, rule.replacement, rule.isRegex,
        rule.getValidTimeoutMillisecond(), rule.isEnabled
    )

    private fun reportDirectRuleError(rule: TextReaderReplacementProcessor.Rule, error: Exception) {
        if (error is RegexTimeoutException) {
            try {
                appDb.runInTransaction {
                    val current = appDb.replaceRuleDao.findById(rule.id)
                    // A late timeout must not overwrite a rule edited while this chapter was loading.
                    if (current != null && directRule(current) == rule) {
                        appDb.replaceRuleDao.update(current.copy(isEnabled = false))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLog.put("停用超时替换规则失败: ${rule.name}", failure)
            }
        }
        AppLog.put("普通正文替换净化: 规则 ${rule.name} 替换出错，保留替换前内容", error)
        appCtx.toastOnUi("替换净化: 规则 ${rule.name} 替换出错")
    }

    fun getContent(
        book: Book,
        chapter: BookChapter,
        content: String,
        includeTitle: Boolean = true,
        useReplace: Boolean = true,
        chineseConvert: Boolean = true,
        reSegment: Boolean = true,
        checkActive: () -> Unit = {}
    ): BookContent {
        if (!book.isEpub && book.usesDirectReader) {
            val direct = prepareDirectContent(book, chapter, content, useReplace, checkActive)
            return BookContent(false, direct.document.paragraphs(includeTitle), direct.effectiveReplaceRules,
                displayTitle = direct.document.title)
        }
        val snapshot = ruleSnapshot
        val titleReplaceRules = snapshot.title
        val contentReplaceRules = snapshot.content
        var mContent = content
        var sameTitleRemoved = false
        var effectiveReplaceRules: ArrayList<ReplaceRule>? = null
        val replaceBook by lazy { book.toReplaceBook() }
        if (content != "null") {
            //去除重复标题
            val removeSameTitleMarked = BookHelp.getChapterCacheFileNames(book, chapter, "nr")
                .any(removeSameTitleCache::contains)
            if (!removeSameTitleMarked) try {
                val name = Pattern.quote(book.name)
                var title = chapter.title.escapeRegex().replace(spaceRegex, "\\\\s*")
                var matcher = Pattern.compile("^(\\s|\\p{P}|${name})*${title}(\\s)*")
                    .matcher(mContent)
                if (matcher.find()) {
                    mContent = mContent.substring(matcher.end())
                    sameTitleRemoved = true
                } else if (useReplace && book.getUseReplaceRule()) {
                    title = Pattern.quote(
                        chapter.getDisplayTitle(
                            titleReplaceRules,
                            chineseConvert = false,
                            replaceBook = replaceBook
                        )
                    )
                    matcher = Pattern.compile("^(\\s|\\p{P}|${name})*${title}(\\s)*")
                        .matcher(mContent)
                    if (matcher.find()) {
                        mContent = mContent.substring(matcher.end())
                        sameTitleRemoved = true
                    }
                }
            } catch (e: Exception) {
                AppLog.put("去除重复标题出错\n${e.localizedMessage}", e)
            }
            if (reSegment && book.getReSegment()) {
                //重新分段
                mContent = ContentHelp.reSegment(mContent, chapter.title)
            }
            if (chineseConvert) {
                //简繁转换
                try {
                    when (AppConfig.chineseConverterType) {
                        1 -> mContent = ChineseUtils.t2s(mContent)
                        2 -> mContent = ChineseUtils.s2t(mContent)
                    }
                } catch (_: Exception) {
                    appCtx.toastOnUi("简繁转换出错")
                }
            }
            val useHtmlMap = mutableMapOf<String, String>()
            if (AppConfig.adaptSpecialStyle) { //html处理
                mContent = AppPattern.useHtmlRegex.replace(mContent) { matchResult ->
                    val placeholder = "特殊格式的占位不应该被看见${useHtmlMap.size}。"
                    useHtmlMap[placeholder] = "\n${matchResult.value.replace("\n","")}\n"
                    placeholder
                }
            }
            if (useReplace && book.getUseReplaceRule()) {
                //替换
                effectiveReplaceRules = arrayListOf()
                mContent = mContent.lines().joinToString("\n") { it.trim() }
                val protectedContent = SpecialContentProtector.protect(mContent)
                mContent = protectedContent.content
                contentReplaceRules.forEach { item ->
                    if (item.pattern.isEmpty()) {
                        return@forEach
                    }
                    try {
                        val tmp = if (item.isRegex) {
                            mContent.replace(
                                item.name,
                                item.regex,
                                item.replacement,
                                item.getValidTimeoutMillisecond(),
                                chapter,
                                replaceBook
                            )
                        } else {
                            mContent.replace(item.pattern, item.replacement)
                        }
                        if (mContent != tmp) {
                            effectiveReplaceRules.add(item)
                            mContent = tmp
                        }
                    } catch (e: RegexTimeoutException) {
                        item.isEnabled = false
                        appDb.replaceRuleDao.update(item)
                        mContent = item.name + e.stackTraceStr
                    } catch (_: CancellationException) {
                    } catch (e: Exception) {
                        AppLog.put("替换净化: 规则 ${item.name}替换出错.\n${mContent}", e)
                        appCtx.toastOnUi("替换净化: 规则 ${item.name}替换出错")
                    }
                }
                mContent = protectedContent.restore(mContent)
            }
            useHtmlMap.forEach { (placeholder, originalContent) ->
                mContent = mContent.replace(placeholder, originalContent)
            }
        }
        if (includeTitle) {
            //重新添加标题
            mContent = chapter.getDisplayTitle(
                titleReplaceRules,
                useReplace = useReplace && book.getUseReplaceRule(),
                replaceBook = replaceBook
            ) + "\n" + mContent
        }
        if (isAndroid8) {
            mContent = mContent.replace('\u00A0', ' ')
        }
        val contents = arrayListOf<String>()
        mContent.split("\n").forEach { str ->
            val paragraph = str.trim {
                it.code <= 0x20 || it == '　'
            }
            if (paragraph.isNotEmpty()) {
                if (contents.isEmpty() && includeTitle) {
                    contents.add(paragraph)
                } else {
                    contents.add("${ReadBookConfig.paragraphIndent}$paragraph")
                }
            }
        }
        return BookContent(sameTitleRemoved, contents, effectiveReplaceRules)
    }

}
