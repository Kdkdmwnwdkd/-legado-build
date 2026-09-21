package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDirectFrameReadinessPolicyTest {

    @Test
    fun `requires exact chapter page resources and viewport pixels`() {
        assertTrue(ready())
        assertFalse(ready(actualChapterIndex = 8))
        assertFalse(ready(actualPageIndex = 4))
        assertFalse(ready(resourcesReady = false))
        assertFalse(ready(hasRenderableContent = false))
        assertFalse(ready(hasViewportContent = false))
    }

    @Test
    fun `allows intentionally empty structural page after resources settle`() {
        assertTrue(
            ready(
                requiresRenderableContent = false,
                hasRenderableContent = false,
                hasViewportContent = false
            )
        )
    }

    private fun ready(
        actualChapterIndex: Int? = 7,
        actualPageIndex: Int? = 3,
        resourcesReady: Boolean = true,
        requiresRenderableContent: Boolean = true,
        hasRenderableContent: Boolean = true,
        hasViewportContent: Boolean = true
    ): Boolean {
        return EpubDirectFrameReadinessPolicy.isReady(
            expectedChapterIndex = 7,
            actualChapterIndex = actualChapterIndex,
            expectedPageIndex = 3,
            actualPageIndex = actualPageIndex,
            resourcesReady = resourcesReady,
            requiresRenderableContent = requiresRenderableContent,
            hasRenderableContent = hasRenderableContent,
            hasViewportContent = hasViewportContent
        )
    }
}
