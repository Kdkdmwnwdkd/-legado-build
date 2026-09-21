package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDirectPreloadReadyPolicyTest {

    @Test
    fun incompleteOrUncommittedDocumentCannotEnterPreloadCache() {
        assertFalse(canCache(loadComplete = false))
        assertFalse(canCache(runtimeInstalled = false))
        assertFalse(canCache(stable = false))
        assertFalse(canCache(chromeApplied = false))
        assertFalse(canCache(metricsAvailable = false))
        assertFalse(canCache(hasViewportContent = false))
    }

    @Test
    fun stableRenderableDocumentCanEnterPreloadCache() {
        assertTrue(canCache())
    }

    @Test
    fun intentionallyEmptyDocumentDoesNotRequireViewportPixels() {
        assertTrue(
            canCache(
                requiresRenderableContent = false,
                hasRenderableContent = false,
                hasViewportContent = false
            )
        )
    }

    private fun canCache(
        loadComplete: Boolean = true,
        runtimeInstalled: Boolean = true,
        stable: Boolean = true,
        chromeApplied: Boolean = true,
        metricsAvailable: Boolean = true,
        requiresRenderableContent: Boolean = true,
        hasRenderableContent: Boolean = true,
        hasViewportContent: Boolean = true
    ): Boolean {
        return EpubDirectPreloadReadyPolicy.canCache(
            loadComplete = loadComplete,
            runtimeInstalled = runtimeInstalled,
            stable = stable,
            chromeApplied = chromeApplied,
            metricsAvailable = metricsAvailable,
            requiresRenderableContent = requiresRenderableContent,
            hasRenderableContent = hasRenderableContent,
            hasViewportContent = hasViewportContent
        )
    }
}
