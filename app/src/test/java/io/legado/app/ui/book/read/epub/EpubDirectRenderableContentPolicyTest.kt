package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDirectRenderableContentPolicyTest {

    @Test
    fun `visible content is required for text and visual chapters`() {
        assertTrue(requires(hasText = true))
        assertTrue(requires(singlePage = true))
        assertTrue(requires(fullPageArtwork = true))
        assertTrue(requires(duokanGallery = true))
    }

    @Test
    fun `empty reflowable chapter may activate without a content rectangle`() {
        assertFalse(requires())
        assertTrue(EpubDirectRenderableContentPolicy.canActivate(false, false))
    }

    @Test
    fun `required chapter cannot activate while every content rectangle is empty`() {
        assertFalse(EpubDirectRenderableContentPolicy.canActivate(true, false))
        assertTrue(EpubDirectRenderableContentPolicy.canActivate(true, true))
    }

    private fun requires(
        hasText: Boolean = false,
        singlePage: Boolean = false,
        fullPageArtwork: Boolean = false,
        duokanGallery: Boolean = false
    ): Boolean {
        return EpubDirectRenderableContentPolicy.requiresRenderableContent(
            hasText = hasText,
            singlePage = singlePage,
            fullPageArtwork = fullPageArtwork,
            duokanGallery = duokanGallery
        )
    }
}
