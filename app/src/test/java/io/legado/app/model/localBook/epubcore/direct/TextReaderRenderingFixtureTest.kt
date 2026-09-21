package io.legado.app.model.localBook.epubcore.direct

import android.text.TextPaint
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import org.jsoup.Jsoup
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.decodeBase64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLEncoder

/** Exports the production builder output used by the browser regression probe. */
class TextReaderRenderingFixtureTest {
    @Test fun `prepared bubble fixtures use current preparation and builder before first display`() = runBlocking {
        val paint = object : TextPaint() {
            override fun getColor(): Int = 0xFF222222.toInt()
            override fun getTextSize(): Float = 18f
            override fun getLetterSpacing(): Float = 0f
        }
        // A valid, deterministic 64x32 PNG substitutes only for Android SVG rasterization.
        // The local preparation, image metadata, document and layout builders are production code.
        val png = "iVBORw0KGgoAAAANSUhEUgAAAEAAAAAgCAYAAACinX6EAAAAi0lEQVR4nO3ZwQ2AMAwEQaqjHMqhXfgjwAEUVrH3pPyi6DxPZ5qCzMu6jXyi+VIO/RqDLohC0KVwBLoQCkCXwRHoIgKQAHQJ+nQDOObJ3asIIEBSgNZ3BBAgKUBrBBCgAECvPgIIMAjAXQQYCeAL1q8AlXcCboQEKLwZ9l+g8u/Q6fAVIMLBM2JE8+3HV41/URvfSgAAAABJRU5ErkJggg=="
            .decodeBase64()!!.toByteArray()
        val svg = "data:image/svg+xml," + URLEncoder.encode(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"64\" height=\"32\"><text x=\"8\" y=\"24\">30</text></svg>", "UTF-8"
        ).replace("+", "%20")
        fun badge(src: String, token: String): String {
            val options = linkedMapOf("style" to "TEXT", "width" to "3em", "height" to "2em",
                "click" to "sourceAction('$token')")
            return "<img src=\"$src,${GSON.toJson(options)}\">"
        }
        val slowImages = (1..4).joinToString("") {
            "<img src='https://example.invalid/slow-$it.png' style='TEXT' width='1em' height='1em'>"
        }
        val prose = (1..120).joinToString("\n") { i ->
            "第${i}段：清晨的阳光穿过树梢，旅人沿着河岸缓缓前行。".repeat(5)
        }
        val source = "<p>普通慢图$slowImages</p>" +
            "<p>小气泡🌅${badge("dp:10", "small")} 正常${badge("bubble://paragraph?displayText=20", "normal")}" +
            " 大气泡${badge(svg, "large")}正文接续。</p>" + prose
        val denseBubbles = (1..240).joinToString("") { i ->
            val bubble = listOf("dp:10", "bubble://paragraph?displayText=20", svg)[i % 3]
            "<p>密集气泡第${i}段，旅人继续沿着河岸前行。${badge(bubble, "dense-$i")}段落接续🌅。</p>"
        }
        val chapterSources = mapOf("bubble-ready" to source,
            "bubble-ready-dense" to source.substringBefore(prose) + denseBubbles + prose)
        chapterSources.forEach { (baseName, chapterSource) ->
            val content = TextReaderDocument.prepare("阅读测试", chapterSource)
            val generated = ArrayList<String>()
            val prepared = TextReaderBubblePreparation(render = { src ->
                generated.add(src)
                val scale = when (src.substringAfter("displayText=").substringBefore('&')) {
                    "10" -> .5f
                    "20" -> 1f
                    "30" -> 1.5f
                    else -> error("Unexpected local bubble: $src")
                }
                TextReaderImageResource.bytes(png, scale, isBubble = true)
            }).prepare(content.images(), managedBubble = true)
            assertEquals(3, generated.size)
            assertTrue(generated.all { it.startsWith("bubble://paragraph") })
            assertEquals(content.images().size - 4, prepared.size)
            val resolved = ArrayList<String>()
            val sourceHtml = content.htmlWithImages(true, deferredImages = true, preparedImages = prepared) {
                resolved.add(it.id)
                "https://text-fixture.epub.local/text-image/fixture/${it.id}"
            }
            assertEquals(content.images().take(4).map { it.id }, resolved)
            assertEquals(prepared.size, content.imageActions().size)
            val directory = File("build/reports/text-reader").apply { mkdirs() }
            listOf(baseName, "$baseName-scroll").forEach { name ->
                val chapter = EpubDirectDocumentBuilder.build(
                    chapterIndex = 0, href = "text/0/$name.html", title = content.title,
                    sourceHtml = sourceHtml,
                    config = EpubCoreLayoutConfig(
                        pageWidthPx = 400, pageHeightPx = 700,
                        readerPaddingLeftPx = 24, readerPaddingRightPx = 24,
                        readerPaddingTopPx = 40, readerPaddingBottomPx = 40,
                        paragraphSpacingPx = 12f, paragraphIndentPx = 36f, lineHeightPx = 28f,
                        scrollMode = name.endsWith("scroll"), textPaint = paint,
                        backgroundColor = 0xFFF8F8F2.toInt(), selectionColor = 0x14000000
                    ), density = 1f, resourceHost = "text-fixture.epub.local"
                )
                assertEquals(EpubDirectLayoutMode.REFLOWABLE, chapter.layoutMode)
                val dom = Jsoup.parse(chapter.html)
                assertEquals(4, dom.select("img[data-legado-image-resource]").size)
                val bubbles = dom.select("img.legado-text-bubble")
                assertEquals(prepared.size, bubbles.size)
                bubbles.forEach {
                    assertTrue(it.attr("src").startsWith("data:image/png;base64,"))
                    assertFalse(it.hasAttr("data-legado-image-resource"))
                    assertTrue(it.parent()!!.hasAttr("data-legado-image-action"))
                }
                assertTrue(bubbles[0].attr("style").contains("font-size:50.0%"))
                assertFalse(bubbles[1].attr("style").contains("font-size"))
                assertTrue(bubbles[2].attr("style").contains("font-size:150.0%"))
                val plainText = content.plainText(true)
                assertFalse(plainText.contains("sourceAction"))
                dom.select("[data-legado-text-offset]").forEach { element ->
                    val offset = element.attr("data-legado-text-offset").toInt()
                    assertEquals(element.wholeText(), plainText.substring(offset, offset + element.wholeText().length))
                }
                File(directory, "$name.html").writeText(chapter.html, Charsets.UTF_8)
                File(directory, "$name.txt").writeText(plainText, Charsets.UTF_8)
            }
        }
    }

    @Test fun `generated chapters retain anchor markers through production preparation`() {
        val paint = object : TextPaint() {
            override fun getColor(): Int = 0xFF222222.toInt()
            override fun getTextSize(): Float = 18f
            override fun getLetterSpacing(): Float = 0f
        }
        val prose = (1..120).joinToString("\n") { i ->
            "第${i}段：清晨的阳光穿过树梢，旅人沿着河岸缓缓前行。".repeat(5)
        }
        val image = "<img src='data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 width=%22300%22 height=%22360%22%3E%3Crect width=%22300%22 height=%22360%22 fill=%22green%22/%3E%3C/svg%3E'>"
        val badge = "data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 width=%2260%22 height=%2220%22%3E%3Crect width=%2260%22 height=%2220%22 rx=%228%22 fill=%22royalblue%22/%3E%3Ctext x=%2210%22 y=%2215%22 fill=%22white%22%3E12%3C/text%3E%3C/svg%3E"
        fun sourceImage(click: String?, pclick: String? = null): String {
            val options = linkedMapOf("style" to "TEXT", "width" to "3em")
            click?.let { options["click"] = it }
            pclick?.let { options["pclick"] = it }
            return "<img src=\"$badge,${GSON.toJson(options)}\">"
        }
        val sourceImages = "<p>评论🌅${sourceImage("sourceAction('first')", "rule:9:bad()")}正文接续。</p>" +
            "<p>第二张${sourceImage("sourceAction('second')")}正文。</p>" +
            "<p>仅段落动作${sourceImage(null, "rule:9:bad()")}保持普通图片。</p>" + image + prose
        val sources = mapOf("prose" to prose, "scroll" to prose,
            "long-paragraph" to "很长的一段正文🌅，需要跨页定位。".repeat(3000),
            "images" to prose.take(6000) + "\n" + image + "\n" + prose.drop(6000),
            "source-images" to sourceImages, "source-images-scroll" to sourceImages)
        val directory = File("build/reports/text-reader").apply { mkdirs() }
        sources.forEach { (name, source) ->
            val content = TextReaderDocument.prepare("阅读测试", source)
            val chapter = EpubDirectDocumentBuilder.build(
                chapterIndex = 0, href = "text/0/$name.html", title = content.title,
                sourceHtml = content.html(true) { it },
                config = EpubCoreLayoutConfig(
                    pageWidthPx = 400, pageHeightPx = 700,
                    readerPaddingLeftPx = 24, readerPaddingRightPx = 24,
                    readerPaddingTopPx = 40, readerPaddingBottomPx = 40,
                    paragraphSpacingPx = 12f, paragraphIndentPx = 36f, lineHeightPx = 28f,
                    scrollMode = name.endsWith("scroll"),
                    textPaint = paint, backgroundColor = 0xFFF8F8F2.toInt(), selectionColor = 0x14000000
                ), density = 1f, resourceHost = "text-fixture.epub.local"
            )
            assertEquals(EpubDirectLayoutMode.REFLOWABLE, chapter.layoutMode)
            val dom = Jsoup.parse(chapter.html)
            assertTrue(dom.body().hasAttr("data-legado-text-reader"))
            val plainText = content.plainText(true)
            dom.select("[data-legado-text-offset]").forEach { element ->
                val offset = element.attr("data-legado-text-offset").toInt()
                assertEquals(element.wholeText(), plainText.substring(offset, offset + element.wholeText().length))
            }
            File(directory, "$name.html").writeText(chapter.html)
            File(directory, "$name.txt").writeText(plainText)
        }
    }
}
