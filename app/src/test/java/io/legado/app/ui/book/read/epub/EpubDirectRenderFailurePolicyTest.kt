package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubDirectRenderFailurePolicyTest {

    @Test
    fun `chapter failure keeps the committed direct page visible`() {
        assertEquals(
            EpubDirectRenderFailurePolicy.Action.KeepVisibleDocument,
            EpubDirectRenderFailurePolicy.decide(
                hasVisibleDocument = true
            )
        )
    }

    @Test
    fun `initial failure stays in direct mode and shows an error`() {
        assertEquals(
            EpubDirectRenderFailurePolicy.Action.ShowError,
            EpubDirectRenderFailurePolicy.decide(
                hasVisibleDocument = false
            )
        )
    }
}
