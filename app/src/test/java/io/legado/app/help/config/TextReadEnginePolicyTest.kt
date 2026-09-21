package io.legado.app.help.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextReadEnginePolicyTest {
    @Test fun `unknown and absent preferences keep ordinary books native`() {
        listOf(null, "", "text", "core", "EPUB", "future").forEach {
            assertEquals(TextReadEnginePolicy.NATIVE, TextReadEnginePolicy.normalize(it))
            assertFalse(TextReadEnginePolicy.usesDirect(false, true, true, it))
        }
    }

    @Test fun `ordinary choice does not change actual epub engine choice`() {
        listOf(TextReadEnginePolicy.NATIVE, TextReadEnginePolicy.EPUB).forEach { textEngine ->
            assertTrue(TextReadEnginePolicy.usesDirect(true, true, true, textEngine))
            assertFalse(TextReadEnginePolicy.usesDirect(true, true, false, textEngine))
        }
    }

    @Test fun `ordinary direct works even when epub archive reader is native`() {
        assertTrue(TextReadEnginePolicy.usesDirect(false, true, false, TextReadEnginePolicy.EPUB))
        assertTrue(TextReadEnginePolicy.usesDirect(false, true, true, TextReadEnginePolicy.EPUB))
    }

    @Test fun `audio video pdf and comic formats cannot enter ordinary direct`() {
        assertFalse(TextReadEnginePolicy.usesDirect(false, false, true, TextReadEnginePolicy.EPUB))
    }
}
