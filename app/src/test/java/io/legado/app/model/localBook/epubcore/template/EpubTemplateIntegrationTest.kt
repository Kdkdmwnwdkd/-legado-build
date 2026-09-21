package io.legado.app.model.localBook.epubcore.template

import android.text.TextPaint
import com.google.gson.GsonBuilder
import io.legado.app.model.localBook.epubcore.direct.EpubDirectDocumentBuilder
import io.legado.app.model.localBook.epubcore.direct.EpubDirectSession
import io.legado.app.model.localBook.epubcore.direct.TextReaderDocument
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import io.legado.app.model.localBook.epubcore.layout.EpubReaderChromeData
import io.legado.app.ui.book.read.epub.EpubPageFrameTarget
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EpubTemplateIntegrationTest {
    private fun config(template: EpubReaderTemplate? = null) = EpubCoreLayoutConfig(
        pageWidthPx = 400, pageHeightPx = 700,
        paragraphSpacingPx = 12f, paragraphIndentPx = 36f, lineHeightPx = 28f,
        textPaint = object : TextPaint() {
            override fun getColor(): Int = 0xff222222.toInt()
            override fun getTextSize(): Float = 18f
            override fun getLetterSpacing(): Float = 0f
        }, backgroundColor = 0xfff8f8f2.toInt(), selectionColor = 0x14000000, readerTemplate = template
    )

    private fun template() = EpubReaderTemplate(id = "test", name = "Test",
        firstPageHtml = "<main class=\"opening\"><section data-reader-flow=\"body\"></section></main>",
        otherPageHtml = "<main><section data-reader-flow=\"body\"></section></main>",
        css = "main{display:grid;height:100%}", javascript = "window.templateReady = true;")

    private fun chapter(source: String, plain: String) = EpubDirectDocumentBuilder.build(
        chapterIndex = 2, href = "text/2/chapter.html", title = "章名🌅", sourceHtml = source,
        config = config(), density = 1f
    ).copy(sourceChapterUrl = "https://book.test/chapter/2", plainText = plain)

    @Test fun `template host keeps canonical data and author source out of its own document`() {
        val content = TextReaderDocument.prepare("章名🌅", "前文<img src='badge.png' style='text' click='sourceAction()'>后文。")
        val html = content.html(true) { "https://epub.local/text-image/2/image-0" }
        val original = chapter(html, content.plainText(true))
        val wrapped = EpubTemplateDocument.wrap(original, html, template().copy(
            javascript = "</script><script>parent.attack()</script>\u2028"
        ))
        assertEquals(original.plainText, wrapped.plainText)
        assertEquals(original.href, wrapped.href)
        assertEquals(original.sourceChapterUrl, wrapped.sourceChapterUrl)
        assertEquals(html, wrapped.templateSourceHtml)
        assertFalse(wrapped.html.contains("parent.attack"))
        assertFalse(wrapped.html.contains("sourceAction"))
        assertFalse(wrapped.html.contains("data-legado-image-action"))
        assertTrue(wrapped.templateSourceHtml!!.contains("data-legado-image-action"))
    }

    @Test fun `publisher epub cannot accidentally enter the ordinary text template path`() {
        val original = chapter("<p>文本</p>", "文本\n").copy(sourceChapterUrl = null)
        assertThrows(IllegalArgumentException::class.java) { EpubTemplateDocument.wrap(original, "<p>文本</p>", template()) }
    }

    @Test fun `template source revisions invalidate chapter and frame caches`() {
        val content = TextReaderDocument.prepare("章名🌅", "测试正文。")
        val html = content.html(true) { it }
        val original = chapter(html, content.plainText(true))
        var loads = 0
        val session = EpubDirectSession(bookUrl = "book", chapterLoader = { _, config ->
            loads++
            config.readerTemplate?.let { EpubTemplateDocument.wrap(original, html, it) } ?: original
        }, resourceLoader = { _, _ -> null }, linkResolver = { _, _ -> null }, closeAction = {})
        session.use {
            val first = config(template())
            val changed = first.copy(readerTemplate = template().copy(javascript = "window.templateReady = 2;"))
            session.prepareChapter(2, first)
            session.prepareChapter(2, first)
            session.prepareChapter(2, changed)
            assertEquals(2, loads)
            assertNotEquals(EpubPageFrameTarget.layoutSignature(first, 400, 700),
                EpubPageFrameTarget.layoutSignature(changed, 400, 700))
            val prepared = session.prepareChapter(2, changed)
            assertEquals(7L, EpubPageFrameTarget.readerChromeContentRevision(prepared, changed,
                EpubReaderChromeData(contentRevision = 7L)))
        }
    }

    @Test fun `template typography defaults honor density and remain overridable`() {
        val css = EpubTemplateDocument.baseCss(config(), "https://epub.local/text/2/chapter.html", 2f)
        assertTrue(css.contains("--reader-font-size:9.000px"))
        assertTrue(css.contains("--reader-paragraph-indent:18.000px"))
        assertTrue(css.contains("--reader-line-height:14.000px"))
        assertFalse(css.contains("!important"))
        assertFalse(css.contains("column-width"))
    }

    @Test fun `export actual ordinary content and all builtins for browser acceptance`() {
        val svg = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='36' height='24'%3E%3Crect width='36' height='24' fill='%2388aacc'/%3E%3C/svg%3E"
        val text = buildString {
            repeat(35) { index ->
                append("<p>第$index 段，开头🌅。")
                repeat(10) { append("这是含有行内图片与样式的正文，用于核对分页前后的文字位置。") }
                append("<img src='bubble.png' style='text' click='sourceAction()'>段尾。</p>")
            }
            append("<img src='last.png'>")
        }
        val content = TextReaderDocument.prepare("章名🌅", text)
        val html = content.html(true) { svg }
        val directory = File("build/reports/reader-template").apply { mkdirs() }
        val assets = File("src/main/assets/epub/templates")
        val json = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
        val builtinNames = listOf("night", "vertical")
        assertEquals(builtinNames.map { "builtin.$it" }, EpubReaderTemplateStore.builtinIds)
        builtinNames.forEach { name ->
            val template = EpubReaderTemplate.fromJson(File(assets, "builtin.$name.json").readText())
            assertEquals("builtin.$name", template.id)
            File(directory, "$name.init.json").writeText(json.toJson(mapOf(
                "token" to 101, "template" to template, "sourceHtml" to html,
                "plainText" to content.plainText(true), "baseUrl" to "https://epub.local/text/2/chapter.html",
                "baseCss" to EpubTemplateDocument.baseCss(config(template), "https://epub.local/text/2/chapter.html", 1f),
                "textImageMode" to "0", "fields" to mapOf("bookName" to "模板测试书", "chapterTitle" to content.title,
                    "time" to "12:34", "battery" to "80%", "progress" to "20.0%"),
                "viewport" to mapOf("width" to 400, "height" to 700)
            )))
        }
        builtinNames.forEach { name ->
            assertTrue(File(directory, "$name.init.json").isFile)
        }
    }
}
