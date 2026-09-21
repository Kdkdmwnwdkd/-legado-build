package io.legado.app.model.localBook.epubcore.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDirectSessionHostTest {

    @Test
    fun `direct resources use the fixed moting origin`() {
        assertEquals("epub.local", EpubDirectSession.HOST)
        assertEquals(
            "https://epub.local/OPS/Text/chapter%201.xhtml",
            EpubDirectSession.baseUrl("OPS/Text/chapter 1.xhtml")
        )
        assertTrue(EpubDirectSession.isLocalHost("epub.local"))
        assertFalse(EpubDirectSession.isLocalHost("book.epub.local"))
    }
}
