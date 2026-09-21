package io.legado.app.help.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubReadEnginePolicyTest {

    @Test
    fun `missing and unknown values select the direct core`() {
        assertEquals(EpubReadEnginePolicy.CORE, EpubReadEnginePolicy.normalize(null))
        assertEquals(EpubReadEnginePolicy.CORE, EpubReadEnginePolicy.normalize("legacy"))
        assertTrue(EpubReadEnginePolicy.usesCore("unknown"))
    }

    @Test
    fun `plain text is used only when explicitly selected`() {
        assertEquals(EpubReadEnginePolicy.TEXT, EpubReadEnginePolicy.normalize("text"))
        assertFalse(EpubReadEnginePolicy.usesCore("text"))
    }
}
