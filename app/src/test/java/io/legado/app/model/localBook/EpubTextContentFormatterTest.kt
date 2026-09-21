package io.legado.app.model.localBook

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubTextContentFormatterTest {

    @Test
    fun `formats visible text without the legacy usehtml wrapper`() {
        val output = format(
            """
            <div><span>第一段</span></div>
            <script>dangerous()</script>
            <style>.hidden { display: none; }</style>
            <link rel="stylesheet" href="book.css">
            <meta name="x" content="y">
            """.trimIndent()
        )

        assertTrue(output.contains("第一段"))
        assertFalse(output.contains("<usehtml", ignoreCase = true))
        assertFalse(output.contains("dangerous"))
        assertFalse(output.contains("display: none"))
        assertFalse(output.contains("<div", ignoreCase = true))
        assertFalse(output.contains("<span", ignoreCase = true))
    }

    @Test
    fun `keeps only valid image sources`() {
        val output = format(
            """
            <p>图片</p>
            <img src="images/a.png" alt="说明" width="640" data-extra="discard">
            <img alt="missing source">
            """.trimIndent()
        )

        assertTrue(output.contains("<img src=\"images/a.png\">"))
        assertFalse(output.contains("alt="))
        assertFalse(output.contains("width="))
        assertFalse(output.contains("data-extra="))
        assertFalse(output.contains("missing source"))
    }

    @Test
    fun `removes hidden content and duplicate synthetic covers`() {
        val output = format(
            """
            <p hidden>hidden by attribute</p>
            <p style="display:none">hidden compact</p>
            <p style="display: none">hidden spaced</p>
            <img src="cover.jpeg">
            <p>可见正文</p>
            <img src="cover.jpeg">
            """.trimIndent()
        )

        assertTrue(output.contains("可见正文"))
        assertFalse(output.contains("hidden by attribute"))
        assertFalse(output.contains("hidden compact"))
        assertFalse(output.contains("hidden spaced"))
        assertEquals(1, Regex("<img src=\\\"cover\\.jpeg\\\">").findAll(output).count())
    }

    @Test
    fun `removing ruby annotations keeps adjacent base text continuous`() {
        val output = format(
            "前<ruby>漢<rp>（</rp><rt>かん</rt><rp>）</rp></ruby>後",
            removeRubyAnnotations = true
        )

        assertTrue(output.contains("前漢後"))
        assertFalse(output.contains("かん"))
    }

    @Test
    fun `keeping ruby annotations preserves base and pronunciation text`() {
        val output = format(
            "前<ruby>漢<rp>（</rp><rt>かん</rt><rp>）</rp></ruby>後",
            removeRubyAnnotations = false
        )

        assertTrue(output.contains("前"))
        assertTrue(output.contains("漢"))
        assertTrue(output.contains("かん"))
        assertTrue(output.contains("後"))
    }

    private fun format(html: String, removeRubyAnnotations: Boolean = false): String {
        val elements = Jsoup.parseBodyFragment(html).select("body")
        return EpubTextContentFormatter.format(elements, removeRubyAnnotations)
    }
}
