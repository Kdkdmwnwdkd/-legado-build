package io.legado.app.ui.book.read.epub

import io.legado.app.ui.book.read.ReadMenuButtonConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectReaderMenuPolicyTest {
    @Test fun `ordinary text retains replacement in both engines and EPUB retains native behavior`() {
        assertTrue(ReadMenuButtonConfig.supportsReplaceRules(isEpub = false, directReader = false))
        assertTrue(ReadMenuButtonConfig.supportsReplaceRules(isEpub = false, directReader = true))
        assertTrue(ReadMenuButtonConfig.supportsReplaceRules(isEpub = true, directReader = false))
        assertFalse(ReadMenuButtonConfig.supportsReplaceRules(isEpub = true, directReader = true))
    }

    @Test fun `direct hides unsupported buttons while retaining saved native layout`() {
        val original = ReadMenuButtonConfig.defaultLayout()
        val filtered = ReadMenuButtonConfig.forDirectReader(original)
        val ids = (filtered.firstRow + filtered.secondRow).map { it.id }
        assertFalse(ids.contains(ReadMenuButtonConfig.Builtin.PARAGRAPH_RULES))
        assertFalse(ids.contains(ReadMenuButtonConfig.Builtin.REPLACE_RULE))
        assertFalse(ids.contains(ReadMenuButtonConfig.Builtin.BUBBLE))
        assertTrue(ids.contains(ReadMenuButtonConfig.Builtin.CATALOG))
        assertTrue(ids.contains(ReadMenuButtonConfig.Builtin.READ_STYLE))
        assertTrue(ids.contains(ReadMenuButtonConfig.Builtin.READ_ALOUD))
        assertEquals(ReadMenuButtonConfig.defaultLayout(), original)
    }

    @Test fun `ordinary direct permits replacement without restoring other unsupported buttons`() {
        val original = ReadMenuButtonConfig.defaultLayout()
        val filtered = ReadMenuButtonConfig.forDirectReader(original, allowReplaceRules = true)
        val ids = (filtered.firstRow + filtered.secondRow).map { it.id }
        assertTrue(ids.contains(ReadMenuButtonConfig.Builtin.REPLACE_RULE))
        assertFalse(ids.contains(ReadMenuButtonConfig.Builtin.SEARCH))
        assertFalse(ids.contains(ReadMenuButtonConfig.Builtin.PARAGRAPH_RULES))
        assertFalse(ids.contains(ReadMenuButtonConfig.Builtin.BUBBLE))
        assertTrue(ids.contains(ReadMenuButtonConfig.Builtin.CATALOG))
        assertEquals(ReadMenuButtonConfig.defaultLayout(), original)
    }

    @Test fun `replacement availability does not change saved custom buttons or button order`() {
        val replacement = ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.REPLACE_RULE)
        val catalog = ReadMenuButtonConfig.builtin(ReadMenuButtonConfig.Builtin.CATALOG)
        val custom = ReadMenuButtonConfig.ButtonRef(ReadMenuButtonConfig.TYPE_CUSTOM, "42")
        val original = ReadMenuButtonConfig.ButtonLayout(
            firstRow = listOf(custom, replacement, catalog),
            secondRow = listOf(replacement, custom)
        )
        val ordinary = ReadMenuButtonConfig.forDirectReader(original, allowReplaceRules = true)
        val epub = ReadMenuButtonConfig.forDirectReader(original)
        assertEquals(original, ordinary)
        assertEquals(listOf(custom, catalog), epub.firstRow)
        assertEquals(listOf(custom), epub.secondRow)
        assertTrue(ReadMenuButtonConfig.supportsDirectReader(replacement, allowReplaceRules = true))
        assertFalse(ReadMenuButtonConfig.supportsDirectReader(replacement))
        assertTrue(ReadMenuButtonConfig.supportsDirectReader(custom))
        assertTrue(ReadMenuButtonConfig.supportsDirectReader(custom, allowReplaceRules = true))
    }
}
