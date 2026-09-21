package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.help.http.dns.DnsScope
import android.graphics.Bitmap
import android.net.Uri
import com.script.rhino.runScriptWithContext
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.ImageSourceOptions
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isMobi
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.model.ImageProvider
import io.legado.app.model.ParagraphBubbleRenderer
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import splitties.init.appCtx
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

/** Image work runs off WebView interception, under the owning chapter or image worker. */
internal object TextReaderImageLoader {
    suspend fun load(book: Book, source: BookSource?, chapter: BookChapter, image: TextReaderImage,
                     managedBubble: Boolean): TextReaderImageResource {
        val raw = image.renderSource
        val context = currentCoroutineContext()
        val baseUrl = chapter.getAbsoluteURL()
        val resolver = TextReaderImageResolver(
            analyze = { request ->
                val parsed = requireNotNull(ImageSourceOptions.parse(request))
                val imageSource = source?.takeIf { !book.isLocal && it.bookSourceUrl == book.origin }
                if (TextReaderImageSource.needsScript(parsed)) {
                    check(imageSource != null) { "图片书源已失效" }
                }
                // Like native TEXT images, loading js gets this book and this chapter.
                // Never read ReadBook here: it may already point at the next book.
                val analyze = runInterruptible {
                    AnalyzeUrl(request, dnsScope = DnsScope.IMAGE, baseUrl = baseUrl, source = imageSource,
                        ruleData = book, chapter = chapter, coroutineContext = context,
                        readTimeout = 10_000, callTimeout = 15_000)
                }
                TextReaderImageResolver.Request(analyze.url) {
                    val cacheKey = if (TextReaderImageSource.needsScript(parsed)) {
                        "text-image:${chapter.url}:$request:${analyze.url}"
                    } else {
                        NetworkUtils.getAbsoluteURL(baseUrl, parsed.source) + request.removePrefix(parsed.source)
                    }
                    val cached = BookHelp.getImage(book, cacheKey)
                    val existing = runCatching { TextReaderImageResource.file(cached) }.getOrNull()
                    if (existing != null) existing else {
                        val bytes = analyze.getResponseAwait().use { response ->
                            if (!response.isSuccessful) throw IOException("图片 HTTP ${response.code}")
                            if (response.body.contentLength() > TextReaderImageResource.MAX_BYTES) {
                                throw IOException("图片数据过大")
                            }
                            runInterruptible { readImage(response.body.byteStream(), context) }
                        }
                        val decoded = runScriptWithContext {
                            val decodeSource = if (TextReaderImageSource.needsScript(parsed)) analyze.url else cacheKey
                            ImageUtils.decode(decodeSource, bytes, isCover = false, imageSource, book)
                        } ?: throw IOException("图片解密失败")
                        context.ensureActive()
                        // Reject error pages rather than saving another permanently blank image.
                        val imageResource = TextReaderImageResource.bytes(decoded)
                        runInterruptible { imageResource.persist(cached, replaceExisting = true) }
                    }
                }
            },
            local = { src ->
                if (src.startsWith("file:", true)) {
                    TextReaderImageResource.file(File(Uri.parse(src).path ?: throw IOException("本地图片路径无效")))
                } else if (src.startsWith("content:", true)) {
                    val input = appCtx.contentResolver.openInputStream(Uri.parse(src))
                        ?: throw IOException("本地图片不存在")
                    TextReaderImageResource.bytes(input.use { readImage(it, context) })
                } else {
                    TextReaderImageResource.file(ImageProvider.cacheImage(book, src, source))
                }
            },
            bubble = ::renderBubble,
            containerImage = if (book.isMobi) {
                { src -> TextReaderImageResource.file(ImageProvider.cacheImage(book, src, source)) }
            } else null,
            managedBubble = { resolved ->
                TextReaderSourceBubblePolicy.resolve(image.source, resolved, image.sourceStyle, image.click,
                    enabled = managedBubble)
            },
            isLocalFile = { it.startsWith('/') && File(it).isFile }
        )
        val resource = resolver.load(raw)
        context.ensureActive()
        return if (resource.retainedBytes > 64 * 1024) {
            runInterruptible {
                val file = BookHelp.getImage(book, "text-image-resource:" + resource.contentHash())
                resource.persist(file)
            }.also { context.ensureActive() }
        } else resource
    }

    suspend fun renderBubble(src: String): TextReaderImageResource {
        val context = currentCoroutineContext()
        context.ensureActive()
        return runInterruptible {
            val scale = BubblePackageManager.currentEntry().config.sizeScale
                .coerceIn(BubblePackageManager.MIN_SIZE_SCALE, BubblePackageManager.MAX_SIZE_SCALE)
            val side = (64 * appCtx.resources.displayMetrics.density * scale).roundToInt().coerceIn(64, 384)
            val bitmap = ParagraphBubbleRenderer.render(src, side, null)
                ?: throw IOException("气泡图片生成失败")
            try {
                context.ensureActive()
                val output = ByteArrayOutputStream()
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "气泡图片编码失败" }
                context.ensureActive()
                TextReaderImageResource.bytes(output.toByteArray(), scale, isBubble = true)
            } finally {
                bitmap.recycle()
            }
        }.also { context.ensureActive() }
    }

    private fun readImage(input: InputStream, context: CoroutineContext): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            context.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size().toLong() + read > TextReaderImageResource.MAX_BYTES) {
                throw IOException("图片数据过大")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
