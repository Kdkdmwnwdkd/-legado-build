package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.help.ImageSourceOptions
import io.legado.app.utils.GSON
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder
import java.util.Base64

class TextReaderSourceBubblePolicyTest {
    private fun svg(text: String) = """<svg xmlns="http://www.w3.org/2000/svg" width="64" height="24"><text x="4" y="20">$text</text></svg>"""
    private fun data(text: String) = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg(text).toByteArray())
    private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun source(url: String = "badge.png", vararg options: Pair<String, String>) =
        url + "," + GSON.toJson(mapOf(*options))
    private fun bubble(text: String, status: String = "normal", color: String? = null) =
        "bubble://paragraph?displayText=${encoded(text)}&num=${encoded(text)}&status=${encoded(status)}" +
            (color?.let { "&displayColor=${encoded(it)}" } ?: "")

    @Test fun `managed package conversion requires explicit opt in`() {
        val raw = source(data("12"), "style" to "TEXT", "click" to "showCmt(1)")
        assertNull(TextReaderSourceBubblePolicy.resolve(raw, data("12"), enabled = false))
        assertEquals(bubble("12"), TextReaderSourceBubblePolicy.resolve(raw, data("12"), enabled = true))
    }

    @Test fun `source metadata wins over generated SVG and source click stays untouched`() {
        val action = "showComment(book.name, java.get('title'))"
        val raw = source("placeholder", "style" to "TEXT", "type" to "qd", "num" to "7+",
            "status" to "emphasis", "color" to "#cafe00", "click" to action, "pclick" to "rule:7:blocked()")
        val result = TextReaderSourceBubblePolicy.resolve(raw, data("12"), enabled = true)
        assertEquals(bubble("7+", "emphasis", "#cafe00"), result)
        val content = TextReaderDocument.prepare("", "<p>正文<img src='$raw'>结尾</p>")
        assertEquals(listOf("正文结尾"), content.paragraphs(false))
        assertEquals(TextReaderImageAction(raw, action), content.imageActions().values.single())
        assertFalse(result!!.contains(action))
        assertFalse(result.contains("blocked()"))
    }

    @Test fun `HTML attribute metadata and generated SVG work after request options were separated`() {
        assertEquals(bubble("9+"), TextReaderSourceBubblePolicy.resolve(
            "placeholder", data("9+"), style = "text", click = "showCmt(4)", enabled = true))
        assertEquals(bubble("9+"), TextReaderSourceBubblePolicy.resolve(
            "placeholder", data("9+"), style = "TEXT", enabled = true))
    }

    @Test fun `explicit standalone images and unrelated SVG artwork keep their source`() {
        for (style in listOf("full", "single", "left", "center", "right")) {
            val raw = source("cover.jpg", "style" to style, "type" to "qd", "num" to "12", "click" to "showCmt(2)")
            assertNull(TextReaderSourceBubblePolicy.resolve(raw, data("12"), enabled = true))
        }
        assertNull(TextReaderSourceBubblePolicy.resolve(data("12"), data("12"), enabled = true))
        assertNull(TextReaderSourceBubblePolicy.resolve(
            source("cover.jpg", "num" to "12", "click" to "preview()"), "cover.jpg", enabled = true))
    }

    @Test fun `supported source types and display aliases preserve the native policy`() {
        for (type in listOf("qd", "fqpl", "fanqie", "cmt", "comment", "comments", "review", "paragraph", "paragraphcomment")) {
            assertEquals(bubble("12"), TextReaderSourceBubblePolicy.resolve(
                source("badge.png", "type" to type, "num" to "12"), "badge.png", enabled = true))
        }
        for (key in listOf("displayText", "num", "\$num", "\${num}", "{{num}}", "count", "text", "label")) {
            val raw = source("badge.png", "type" to "CMT", key to "99+")
            assertEquals(bubble("99+"), TextReaderSourceBubblePolicy.resolve(raw, "badge.png", enabled = true))
        }
    }

    @Test fun `color aliases and query parameters preserve encoded and literal plus signs`() {
        for (key in listOf("displayColor", "color", "\$color", "\${color}", "{{color}}")) {
            val raw = source("badge.png", "type" to "comment", "num" to "12", key to "#abcdef")
            assertEquals(bubble("12", color = "#abcdef"),
                TextReaderSourceBubblePolicy.resolve(raw, "badge.png", enabled = true))
        }
        val raw = source("https://book/badge.svg?num=6+&color=%23aabbcc&status=emphasis", "type" to "cmt")
        assertEquals(bubble("6+", "emphasis", "#aabbcc"),
            TextReaderSourceBubblePolicy.resolve(raw, "badge.svg", enabled = true))
        val resolved = "https://book/badge.svg?displayText=8%2B&displayColor=%23aabbcc"
        assertEquals(bubble("8+", color = "#aabbcc"),
            TextReaderSourceBubblePolicy.resolve(source("placeholder", "type" to "cmt"), resolved, enabled = true))
    }

    @Test fun `legacy createSvg calls are read as metadata without executing a source action`() {
        for (name in listOf("createSvg", "createSvg2")) {
            val script = "$name(64, 64, '#fff', 98, 'normal'); throw new Error('must not run');"
            val raw = source("placeholder", "type" to "qd", "js" to script)
            assertEquals(bubble("98"), TextReaderSourceBubblePolicy.resolve(raw, "placeholder", enabled = true))
        }
        val raw = source("placeholder", "pclick" to "paragraphRule:rule:7:createSvg(64,64,0,15)")
        assertEquals(bubble("15"), TextReaderSourceBubblePolicy.resolve(raw, "placeholder", enabled = true))
        assertTrue(TextReaderDocument.prepare("", "<img src='$raw'>").imageActions().isEmpty())
    }

    @Test fun `percent SVG and nested XML text select the numeric label without decoding twice`() {
        val xml = """<svg xmlns="http://www.w3.org/2000/svg"><text>评论</text><text><tspan>12</tspan>+🌅</text></svg>"""
        val percent = "data:image/svg+xml," + encoded(xml)
        assertEquals(bubble("12+🌅"), TextReaderSourceBubblePolicy.resolve(
            "placeholder", percent, style = "TEXT", enabled = true))
        assertEquals(bubble("5&lt;6"), TextReaderSourceBubblePolicy.resolve(
            "placeholder", data("5&amp;lt;6"), style = "TEXT", enabled = true))
    }

    @Test fun `empty malformed oversized and explicit virtual resources stay on their original route`() {
        for (resolved in listOf("data:image/svg+xml;base64,???", "data:image/svg+xml,%zz", "data:image/svg+xml,%3Csvg/%3E")) {
            assertNull(TextReaderSourceBubblePolicy.resolve("placeholder", resolved, style = "TEXT", enabled = true))
        }
        for (raw in listOf("dp:12", "bubble://paragraph?num=12", "BUBBLE://paragraph?num=12")) {
            assertNull(TextReaderSourceBubblePolicy.resolve(raw, raw, enabled = true))
        }
        val raw = source("badge.png", "type" to "qd", "num" to "12")
        assertNull(TextReaderSourceBubblePolicy.resolve(raw, "x".repeat(2 * 1024 * 1024 + 1), enabled = true))
    }

    @Test fun `labels are bounded without splitting a supplementary unicode character`() {
        val label = "1".repeat(23) + "🌅" + "tail"
        val raw = source("badge.png", "type" to "qd", "num" to label)
        assertEquals(bubble("1".repeat(23) + "🌅"), TextReaderSourceBubblePolicy.resolve(raw, "badge.png", enabled = true))
        assertEquals(label, ImageSourceOptions.parse(raw)!!.option("num"))
    }
}
