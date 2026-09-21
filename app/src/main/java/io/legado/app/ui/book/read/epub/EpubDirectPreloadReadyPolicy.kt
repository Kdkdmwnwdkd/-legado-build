package io.legado.app.ui.book.read.epub

internal object EpubDirectPreloadReadyPolicy {

    fun canCache(
        loadComplete: Boolean,
        runtimeInstalled: Boolean,
        stable: Boolean,
        chromeApplied: Boolean,
        metricsAvailable: Boolean,
        requiresRenderableContent: Boolean,
        hasRenderableContent: Boolean,
        hasViewportContent: Boolean
    ): Boolean {
        return loadComplete && runtimeInstalled && stable && chromeApplied && metricsAvailable &&
            (!requiresRenderableContent || (hasRenderableContent && hasViewportContent))
    }
}
