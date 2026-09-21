package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDirectActivationVisualPolicyTest {

    @Test
    fun `authored blank column is navigable without becoming a reusable screenshot`() {
        assertTrue(EpubDirectActivationVisualPolicy.canNavigate(
            requiresViewportContent = false,
            requiresRenderableContent = true,
            hasRenderableContent = true,
            hasViewportContent = false,
            expectedPageIndex = 1,
            actualPageIndex = 1
        ))
        assertFalse(canCommit(hasViewportContent = false))
    }

    @Test
    fun `generated text still waits for its requested visible page`() {
        assertFalse(EpubDirectActivationVisualPolicy.canNavigate(
            requiresViewportContent = true,
            requiresRenderableContent = true,
            hasRenderableContent = true,
            hasViewportContent = false,
            expectedPageIndex = 1,
            actualPageIndex = 1
        ))
    }

    @Test
    fun `authored blank page cannot excuse a wrong logical target or missing document`() {
        for ((hasDocument, actualPage) in listOf(true to 0, false to 1)) {
            assertFalse(EpubDirectActivationVisualPolicy.canNavigate(
                requiresViewportContent = false,
                requiresRenderableContent = true,
                hasRenderableContent = hasDocument,
                hasViewportContent = false,
                expectedPageIndex = 1,
                actualPageIndex = actualPage
            ))
        }
    }

    @Test
    fun `content chapter requires target page inside viewport`() {
        assertTrue(canCommit())
        assertFalse(canCommit(hasViewportContent = false))
        assertFalse(canCommit(actualPageIndex = 2))
        assertFalse(canCommit(hasRenderableContent = false))
    }

    @Test
    fun `required chapter navigation cannot bypass viewport verification`() {
        assertFalse(
            canCommit(
                hasViewportContent = false
            )
        )
        assertFalse(
            canCommit(
                hasRenderableContent = false,
                hasViewportContent = false,
                actualPageIndex = 2
            )
        )
    }

    @Test
    fun `empty reflowable chapter may commit without viewport rectangle`() {
        assertTrue(
            canCommit(
                requiresRenderableContent = false,
                hasRenderableContent = false,
                hasViewportContent = false
            )
        )
    }

    @Test
    fun `stable target page does not depend on vendor viewport probing`() {
        assertTrue(
            EpubDirectActivationVisualPolicy.hasStableTargetPage(
                requiresRenderableContent = true,
                hasRenderableContent = true,
                expectedPageIndex = 4,
                actualPageIndex = 4
            )
        )
        assertFalse(
            EpubDirectActivationVisualPolicy.hasStableTargetPage(
                requiresRenderableContent = true,
                hasRenderableContent = true,
                expectedPageIndex = 4,
                actualPageIndex = 3
            )
        )
        assertFalse(
            EpubDirectActivationVisualPolicy.hasStableTargetPage(
                requiresRenderableContent = true,
                hasRenderableContent = false,
                expectedPageIndex = 4,
                actualPageIndex = 4
            )
        )
    }

    private fun canCommit(
        requiresRenderableContent: Boolean = true,
        hasRenderableContent: Boolean = true,
        hasViewportContent: Boolean = true,
        expectedPageIndex: Int = 3,
        actualPageIndex: Int = 3
    ): Boolean {
        return EpubDirectActivationVisualPolicy.canCommit(
            requiresRenderableContent = requiresRenderableContent,
            hasRenderableContent = hasRenderableContent,
            hasViewportContent = hasViewportContent,
            expectedPageIndex = expectedPageIndex,
            actualPageIndex = actualPageIndex
        )
    }
}
