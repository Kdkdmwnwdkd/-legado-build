package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.help.ImageSourceOptions
import io.legado.app.utils.GSON
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class TextReaderImageTest {
    private val svg = "data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 width=%2260%22 height=%2220%22%3E%3Ctext y=%2215%22%3E12%3C/text%3E%3C/svg%3E"

    @Test fun `legacy unescaped JSON keeps source actions native and SVG inline`() {
        val click = "if (1 > 0) java.toast('source,{ok}');"
        val raw = svg + "," + GSON.toJson(mapOf("style" to "TEXT", "click" to click, "pclick" to "rule:9:bad()"))
        val content = TextReaderDocument.prepare("", "<p>前🌅<img src=\"$raw\">后文</p>")
        assertEquals(listOf("前🌅后文"), content.paragraphs(false))
        assertEquals(TextReaderImageAction(raw, click), content.imageActions()["image-0"])
        val html = content.html(false) { it }
        val dom = Jsoup.parse(html)
        assertEquals(svg, dom.selectFirst("img")!!.attr("src"))
        assertEquals("image-0", dom.selectFirst("a")!!.attr("data-legado-image-action"))
        assertEquals(1, dom.select("p img.legado-text-inline-image").size)
        assertTrue(dom.select("figure,script,[onclick],[click],[pclick]").isEmpty())
        assertFalse(html.contains("java.toast"))
        assertFalse(html.contains("rule:9"))
    }

    @Test fun `headers bodies and load time JS survive removal of presentation options`() {
        val raw = "../image.svg," + """{"headers":{"Referer":"https://book/"},"body":{"id":7},"js":"result + '?ok=1'","click":"java.toast('ok')","width":"50%"}"""
        val content = TextReaderDocument.prepare("", "<img src='$raw'>")
        // A valid escaped attribute is the normal form; the legacy form above has
        // embedded apostrophes, so use matching raw double-quoted src as well.
        val image = TextReaderDocument.prepare("", "<img src=\"$raw\">").blocks.single().image!!
        val request = ImageSourceOptions.parse(image.renderSource)!!
        assertEquals("../image.svg", request.source)
        assertEquals("{\"Referer\":\"https://book/\"}", request.option("headers"))
        assertEquals("{\"id\":7}", request.option("body"))
        assertEquals("result + '?ok=1'", request.option("js"))
        assertNull(request.click)
        assertNull(request.width)
        assertEquals("50%", image.width)
        assertEquals(raw, image.source)
        assertEquals(raw, content.blocks.single().image!!.source)
    }

    @Test fun `HTML entities and twice escaped option quotes retain their click`() {
        val options = GSON.toJson(mapOf("click" to "java.toast(\"ok\")", "style" to "TEXT"))
        val escaped = GSON.toJson(options).removeSurrounding("\"")
        for (suffix in listOf(options.replace("\"", "&quot;"), escaped)) {
            val content = TextReaderDocument.prepare("", "<img src=\"$svg,$suffix\">")
            assertEquals("java.toast(\"ok\")", content.imageActions().values.single().click)
            assertEquals(svg, Jsoup.parse(content.html(false) { it }).selectFirst("img")!!.attr("src"))
        }
    }

    @Test fun `backslashes and nested braces inside click cannot truncate the source attribute`() {
        for (click in listOf("java.toast('x\\\\')", "\\", "function f(){return {x:'} > <img'};}")) {
            val raw = "image.png," + GSON.toJson(mapOf("click" to click))
            val content = TextReaderDocument.prepare("", "<img src=\"$raw\"><p>正文</p>")
            assertEquals(TextReaderImageAction(raw, click), content.imageActions().values.single())
            assertEquals(listOf("正文"), content.paragraphs(false))
        }
    }

    @Test fun `explicit image attributes become source actions without HTML execution`() {
        val content = TextReaderDocument.prepare("", "<p>正文<img src='x.png' onclick=\"java.toast('ok')\" style='TEXT' width='2em' height='1em'></p>")
        assertEquals("java.toast('ok')", content.imageActions().values.single().click)
        val image = Jsoup.parse(content.html(false) { it }).selectFirst("img")!!
        assertEquals("width:2em;height:1em;", image.attr("style"))
        assertFalse(image.hasAttr("onclick"))
        assertTrue(Jsoup.parse(content.html(false, sourceActionsEnabled = false) { it }).select("a").isEmpty())
    }

    @Test fun `paragraph actions remain disabled even when stored in click or attribute aliases`() {
        val sources = listOf(
            "<img src='x.png,{&quot;pclick&quot;:&quot;rule:1:bad()&quot;}'>",
            "<img src='x.png,{&quot;click&quot;:&quot; paragraphRule:rule:1 bad()&quot;}'>",
            "<img src='x.png' onclick='rule:1:bad()' data-legado-pclick='bad()'>"
        )
        sources.forEach { source ->
            val content = TextReaderDocument.prepare("", source)
            assertTrue(content.imageActions().isEmpty())
            assertTrue(Jsoup.parse(content.html(false) { it }).select("a,[onclick],[pclick]").isEmpty())
        }
        val mixed = TextReaderDocument.prepare("", "<img src='x.png,{&quot;click&quot;:&quot;source()&quot;,&quot;pclick&quot;:&quot;rule:1:bad()&quot;}'>")
        assertEquals("source()", mixed.imageActions().values.single().click)
    }

    @Test fun `inline images preserve whitespace trimming unicode and text offsets`() {
        val image = "<img src='$svg,{&quot;style&quot;:&quot;TEXT&quot;}'>"
        val content = TextReaderDocument.prepare("标题", "<p>　${image}前🌅${image}后　\n $image\n尾${image}声 </p><img src='large.jpg'><p>结束</p>")
        assertEquals(listOf("标题", "前🌅后", "尾声", "结束"), content.paragraphs(true))
        for (title in listOf(true, false)) {
            val plain = content.plainText(title)
            val dom = Jsoup.parse(content.html(title) { it })
            assertEquals(4, dom.select("p img").size)
            assertEquals(1, dom.select("figure img").size)
            dom.select("[data-legado-text-offset]").forEach {
                val offset = it.attr("data-legado-text-offset").toInt()
                assertEquals(it.wholeText(), plain.substring(offset, offset + it.wholeText().length))
            }
        }
    }

    @Test fun `dimensions admit only bounded lengths and never arbitrary CSS`() {
        assertEquals("32px", TextReaderImage.cssDimension("32dp"))
        assertEquals("50%", TextReaderImage.cssDimension("50%"))
        assertEquals("1.5em", TextReaderImage.cssDimension("1.5em"))
        listOf("0", "-5", "1000%", "9999999px", "1px;position:fixed", "url(javascript:x)", "NaN").forEach {
            assertNull(TextReaderImage.cssDimension(it))
        }
    }
}
