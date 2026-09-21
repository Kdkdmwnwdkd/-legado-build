package io.legado.app.model.localBook.epubcore.direct

import android.text.TextPaint
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TextReaderFoundationTest {
    private val imageData = "data:image/png;base64," +
        "iVBORw0KGgoAAAANSUhEUgAAAEAAAAAgCAYAAACinX6EAAAAi0lEQVR4nO3ZwQ2AMAwEQaqjHMqhXfgjwAEUVrH3pPyi6DxPZ5qCzMu6jXyi+VIO/RqDLohC0KVwBLoQCkCXwRHoIgKQAHQJ+nQDOObJ3asIIEBSgNZ3BBAgKUBrBBCgAECvPgIIMAjAXQQYCeAL1q8AlXcCboQEKLwZ9l+g8u/Q6fAVIMLBM2JE8+3HV41/URvfSgAAAABJRU5ErkJggg=="

    private fun config(indent: Float = 36f, scroll: Boolean = false) = EpubCoreLayoutConfig(
        pageWidthPx = 400, pageHeightPx = 700,
        readerPaddingLeftPx = 24, readerPaddingRightPx = 24,
        readerPaddingTopPx = 40, readerPaddingBottomPx = 40,
        paragraphSpacingPx = 12f, paragraphIndentPx = indent, lineHeightPx = 28f,
        scrollMode = scroll,
        textPaint = object : TextPaint() {
            override fun getColor(): Int = 0xFF222222.toInt()
            override fun getTextSize(): Float = 18f
            override fun getLetterSpacing(): Float = 0f
        },
        backgroundColor = 0xFFF8F8F2.toInt(), selectionColor = 0x14000000
    )

    private fun css(indent: Float = 36f, scroll: Boolean = false, density: Float = 1f) =
        EpubDirectDocumentBuilder.readerCss(
            config = config(indent, scroll), density = density,
            layoutMode = EpubDirectLayoutMode.REFLOWABLE, viewport = null,
            fullPageArtwork = false, implicitSinglePage = false, duokanGallery = false,
            readerFontUrl = null
        )

    @Test fun `semantic blocks are stable across title and action visibility without changing canonical text`() {
        val content = TextReaderDocument.prepare("标题🌅", """
            <p>纯正文。</p><p>前🌅<img src='badge.png' style='TEXT' click='sourceAction()'>后  文。</p>
            <img src='cover.png'><p><img src='badge-only.png' style='TEXT'></p>
        """.trimIndent())
        val expectedBody = "纯正文。\n前🌅后  文。\n"
        assertEquals(expectedBody, content.plainText(false))
        val imageIds = content.images().map { it.id }
        listOf(true, false).forEach { includeTitle ->
            listOf(true, false).forEach { actionsEnabled ->
                val plainText = content.plainText(includeTitle)
                val dom = Jsoup.parse(content.html(includeTitle, actionsEnabled) { imageData })
                val bodyBlocks = dom.select("[data-reader-block]").filter { it.attr("data-reader-kind") != "title" }
                assertEquals(listOf("block-0", "block-1", "block-2", "block-3"),
                    bodyBlocks.map { it.attr("data-reader-block") })
                assertEquals(listOf("paragraph", "paragraph", "image", "image"),
                    bodyBlocks.map { it.attr("data-reader-kind") })
                assertEquals(listOf(true, true, false, false), bodyBlocks.map { it.hasClass("reader-paragraph") })
                assertEquals(imageIds, dom.select("img").map { it.attr("data-legado-image-id") })
                assertEquals(if (actionsEnabled) 1 else 0, dom.select("[data-legado-image-action]").size)
                assertFalse(dom.outerHtml().contains("sourceAction()"))
                if (includeTitle) {
                    val title = dom.selectFirst("h2.reader-chapter-title")!!
                    assertEquals("title", title.attr("data-reader-block"))
                    assertEquals("title", title.attr("data-reader-kind"))
                    assertFalse(title.hasClass("reader-paragraph"))
                    assertEquals("标题🌅\n$expectedBody", plainText)
                } else assertTrue(dom.select("[data-reader-kind=title]").isEmpty())
                dom.select("[data-legado-text-offset]").forEach { block ->
                    val offset = block.attr("data-legado-text-offset").toInt()
                    assertEquals(block.wholeText(), plainText.substring(offset, offset + block.wholeText().length))
                }
                assertTrue(dom.select("[data-reader-kind=image][data-legado-text-offset]").isEmpty())
            }
        }
    }

    @Test fun `prepared bubbles retain image classes and prose semantics`() {
        val content = TextReaderDocument.prepare("", "<p>前<img src='dp:4' click='sourceAction()'>后。</p>")
        val image = content.images().single()
        val dom = Jsoup.parse(content.htmlWithImages(false,
            preparedImages = mapOf(image.id to TextReaderPreparedImage(imageData, 1.5f))) {
            error("Prepared image must not resolve again")
        })
        val paragraph = dom.selectFirst("p.reader-paragraph")!!
        assertEquals("paragraph", paragraph.attr("data-reader-kind"))
        assertEquals("block-0", paragraph.attr("data-reader-block"))
        val bubble = paragraph.selectFirst("img")!!
        assertTrue(bubble.hasClass("legado-text-inline-image"))
        assertTrue(bubble.hasClass("legado-text-bubble"))
        assertEquals(image.id, bubble.attr("data-legado-image-id"))
        assertEquals(image.id, bubble.parent()!!.attr("data-legado-image-action"))
        assertEquals("前后。\n", content.plainText(false))
    }

    @Test fun `empty placeholder is visible but is not a canonical prose block`() {
        val content = TextReaderDocument.prepare("", "")
        val dom = Jsoup.parse(content.html(false) { imageData })
        assertEquals("本章暂无正文", dom.body().text())
        assertEquals("placeholder", dom.selectFirst("[data-reader-block=empty]")!!.attr("data-reader-kind"))
        assertTrue(dom.select(".reader-paragraph,[data-legado-text-offset]").isEmpty())
        assertEquals("", content.plainText(false))
    }

    @Test fun `ordinary prose indentation does not require the pure text line grid marker`() {
        val content = TextReaderDocument.prepare("标题", "<p>纯文字。</p><p>前<img src='badge.png' style='TEXT'>后。</p><img src='cover.png'>")
        val dom = Jsoup.parse(content.html(true) { imageData })
        val selector = "body[data-legado-text-reader] p.reader-paragraph"
        assertEquals(2, dom.select(selector).size)
        assertTrue(dom.select("[data-legado-reader-paragraph]").isEmpty())
        assertTrue(dom.select(selector).all { it.attr("data-reader-kind") == "paragraph" })
        listOf(false, true).forEach { scroll ->
            assertTrue(css(33.5f, scroll, 2f).contains("$selector{text-indent:16.750px!important;}"))
            assertTrue(css(0f, scroll).contains("$selector{text-indent:0.000px!important;}"))
        }
        // Identical element/class names in a publisher document do not opt it in.
        dom.body().removeAttr("data-legado-text-reader")
        assertTrue(dom.select(selector).isEmpty())
    }

    @Test fun `position fixtures use production semantic document and indentation css`() {
        val prose = (1..180).joinToString("") { index ->
            "第${index}处🌅，甲乙  丙丁，旅人继续向前。" +
                if (index % 9 == 0) "<img src='badge-$index.png' style='TEXT' click='sourceAction()'>" else ""
        }
        val content = TextReaderDocument.prepare("稳定定位🌅", "<p>起点。</p><p>$prose</p><p>终点。</p>")
        val sourceHtml = content.html(true) { imageData }
        val directory = File("build/reports/text-reader-foundation").apply { mkdirs() }
        listOf(false, true).forEach { scroll ->
            val name = if (scroll) "positions-scroll" else "positions-horizontal"
            val chapter = EpubDirectDocumentBuilder.build(
                chapterIndex = 0, href = "text/0/$name.html", title = content.title,
                sourceHtml = sourceHtml, config = config(scroll = scroll), density = 1f,
                resourceHost = "text-fixture.epub.local"
            )
            assertEquals(EpubDirectLayoutMode.REFLOWABLE, chapter.layoutMode)
            val dom = Jsoup.parse(chapter.html)
            assertEquals(3, dom.select("p.reader-paragraph").size)
            assertEquals(20, dom.select("img.legado-text-inline-image").size)
            assertTrue(chapter.html.contains("body[data-legado-text-reader] p.reader-paragraph{text-indent:36.000px!important;}"))
            File(directory, "$name.html").writeText(chapter.html, Charsets.UTF_8)
            File(directory, "$name.txt").writeText(content.plainText(true), Charsets.UTF_8)
        }
    }
}
