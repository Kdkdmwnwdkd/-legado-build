package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubPageTurnResultTest {

    @Test
    fun `host requests a chapter only for an explicit boundary result`() {
        EpubPageTurnResult.entries.forEach { result ->
            if (result == EpubPageTurnResult.BoundaryRequired) {
                assertTrue(result.requiresBoundaryNavigation)
            } else {
                assertFalse(result.requiresBoundaryNavigation)
            }
        }
    }
}
