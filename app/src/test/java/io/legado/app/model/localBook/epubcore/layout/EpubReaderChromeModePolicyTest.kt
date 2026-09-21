package io.legado.app.model.localBook.epubcore.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubReaderChromeModePolicyTest {

    @Test
    fun `direct epub rejects only the native advanced mode`() {
        assertTrue(EpubReaderChromeModePolicy.isSupported(true, mode = 1, advancedMode = 3))
        assertFalse(EpubReaderChromeModePolicy.isSupported(true, mode = 3, advancedMode = 3))
        assertTrue(EpubReaderChromeModePolicy.isSupported(false, mode = 3, advancedMode = 3))
    }

    @Test
    fun `direct epub removes advanced choice without changing ordinary reader modes`() {
        val modes = linkedMapOf(0 to "hide", 1 to "classic", 2 to "status", 3 to "advanced")

        assertEquals(
            linkedMapOf(0 to "hide", 1 to "classic", 2 to "status"),
            EpubReaderChromeModePolicy.selectableModes(modes, true, advancedMode = 3)
        )
        assertSame(
            modes,
            EpubReaderChromeModePolicy.selectableModes(modes, false, advancedMode = 3)
        )
    }
}
