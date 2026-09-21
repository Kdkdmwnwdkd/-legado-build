package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubVirtualFrameLayoutTest {

    @Test
    fun `accepts tightly packed rgba rows`() {
        assertEquals(1080, EpubVirtualFrameLayout.paddedWidth(1080, 4, 4320))
    }

    @Test
    fun `retains vendor row padding in bitmap width`() {
        assertEquals(1088, EpubVirtualFrameLayout.paddedWidth(1080, 4, 4352))
    }

    @Test
    fun `rejects truncated and misaligned rows`() {
        assertNull(EpubVirtualFrameLayout.paddedWidth(1080, 4, 4316))
        assertNull(EpubVirtualFrameLayout.paddedWidth(1080, 4, 4353))
        assertNull(EpubVirtualFrameLayout.paddedWidth(0, 4, 4))
    }
}
