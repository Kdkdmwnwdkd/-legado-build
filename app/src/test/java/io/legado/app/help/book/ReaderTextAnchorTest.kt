package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTextAnchorTest {
    @Test fun `anchor survives native indentation and line break differences`() {
        val source = "章节标题\n　　清晨阳光落在窗台上。\n　　下一段文字继续讲述旅途的故事。"
        val target = "章节标题\n清晨阳光落在窗台上。\n下一段文字继续讲述旅途的故事。\n"
        val offset = source.indexOf("落在")
        assertEquals(target.indexOf("落在"), ReaderTextAnchor.capture(source, offset).locate(target, offset))
    }

    @Test fun `removed rule output does not guess an unrelated paragraph`() {
        assertNull(ReaderTextAnchor.capture("A generated bubble with substituted text", 10)
            .locate("Original chapter with different words", 10))
    }

    @Test fun `nearest duplicate wins including overlapping occurrences`() {
        val anchor = ReaderTextAnchor("aaa", 1)
        assertEquals(3, anchor.locate("aaaaaa", 3))
        assertEquals(11, ReaderTextAnchor("repeat", 2).locate("repeat / repeat", 11))
    }

    @Test fun `end of text and supplementary unicode preserve utf16 offsets`() {
        val text = "日出🌅，旅程继续。"
        assertEquals(text.length, ReaderTextAnchor.capture(text, text.length).locate(text, text.length))
        val offset = text.indexOf("旅")
        assertEquals(offset, ReaderTextAnchor.capture(text, offset).locate(text, offset))
    }

    @Test fun `empty and whitespace only cues have no match`() {
        assertNull(ReaderTextAnchor.capture("", 100).locate("", 0))
        assertNull(ReaderTextAnchor.capture(" \n　\u200b", -10).locate("Body", 0))
    }

    @Test fun `cue boundaries survive utf8 persistence beside supplementary characters`() {
        listOf(("a🌅" + "旅".repeat(90)) to 14, ("旅".repeat(63) + "🌅尾声") to 0).forEach { (text, offset) ->
            val anchor = ReaderTextAnchor.capture(text, offset)
            val restored = String(anchor.cue.toByteArray(Charsets.UTF_8), Charsets.UTF_8)
            assertEquals(anchor.cue, restored)
            assertTrue(anchor.cue.length <= 76)
            assertEquals(offset, ReaderTextAnchor(restored, anchor.cueOffset).locate(text, offset))
        }
    }

    @Test fun `stored cue stays bounded on very large chapters`() {
        val text = "序章\n" + "旅程继续。".repeat(100_000) + "独特的尾声。"
        val offset = text.indexOf("独特")
        val anchor = ReaderTextAnchor.capture(text, offset)
        assertTrue(anchor.cue.length <= 76)
        assertEquals(offset, anchor.locate(text, offset))
    }
}
