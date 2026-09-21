package io.legado.app.model.localBook.epubcore.facade

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDirectFragmentWindowPolicyTest {

    @Test
    fun `keeps head and only the requested body window`() {
        val source = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><link rel="stylesheet" href="../css/book.css"/><style>.chapter{color:red}</style></head>
              <body><div id="before">before</div><section id="start"><p>kept</p></section><div id="end">removed</div><p>after</p></body>
            </html>
        """.trimIndent()

        val result = EpubDirectFragmentWindowPolicy.apply(source, "start", "end")
        assertTrue(result.applied)
        val document = Jsoup.parse(result.html)
        assertEquals("../css/book.css", document.selectFirst("link")?.attr("href"))
        assertTrue(result.html.contains("color:red"))
        assertTrue(document.getElementById("start") != null)
        assertTrue(document.text().contains("kept"))
        assertFalse(document.text().contains("before"))
        assertFalse(document.text().contains("removed"))
        assertFalse(document.text().contains("after"))
    }

    @Test
    fun `trims inside nested parent without dropping the remaining siblings`() {
        val source = """<html><head><title>T</title></head><body><p>old <a name="s"></a>new <b>text</b><a id="e"></a> tail</p><p>after</p></body></html>"""
        val result = EpubDirectFragmentWindowPolicy.apply(source, "s", "e")
        assertTrue(result.applied)
        val document = Jsoup.parse(result.html)
        assertEquals("new text", document.body().text())
    }

    @Test
    fun `supports xml id and encoded fragments`() {
        val source = """<html xmlns:xml="http://www.w3.org/XML/1998/namespace"><body><p xml:id="section one">kept</p><p id="next">drop</p></body></html>"""
        val result = EpubDirectFragmentWindowPolicy.apply(source, "section%20one", "next")
        assertTrue(result.applied)
        assertTrue(Jsoup.parse(result.html).text().contains("kept"))
        assertFalse(Jsoup.parse(result.html).text().contains("drop"))
    }

    @Test
    fun `missing or reversed boundaries leave the source untouched`() {
        val source = """<html><body><p id="a">a</p><p id="b">b</p></body></html>"""
        val missing = EpubDirectFragmentWindowPolicy.apply(source, "missing", "b")
        assertFalse(missing.applied)
        assertEquals(source, missing.html)

        val reversed = EpubDirectFragmentWindowPolicy.apply(source, "b", "a")
        assertFalse(reversed.applied)
        assertEquals(source, reversed.html)
    }

    @Test
    fun `one-sided windows are supported`() {
        val source = """<html><body><p id="a">a</p><p id="b">b</p><p id="c">c</p></body></html>"""
        val fromStart = EpubDirectFragmentWindowPolicy.apply(source, "b", null)
        assertTrue(fromStart.applied)
        assertEquals("b c", Jsoup.parse(fromStart.html).body().text())

        val throughEnd = EpubDirectFragmentWindowPolicy.apply(source, null, "c")
        assertTrue(throughEnd.applied)
        assertEquals("a b", Jsoup.parse(throughEnd.html).body().text())
    }
}
