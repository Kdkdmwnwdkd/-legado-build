package io.legado.app.ui.book.read.epub

internal object EpubDirectActivationVisualPolicy {

    fun canNavigate(
        requiresViewportContent: Boolean,
        requiresRenderableContent: Boolean,
        hasRenderableContent: Boolean,
        hasViewportContent: Boolean,
        expectedPageIndex: Int,
        actualPageIndex: Int
    ): Boolean {
        // Authored EPUBs can deliberately contain blank columns or image-only pages
        // whose bytes arrive later. Their logical navigation must remain available.
        return if (requiresViewportContent) {
            canCommit(
                requiresRenderableContent, hasRenderableContent, hasViewportContent,
                expectedPageIndex, actualPageIndex
            )
        } else {
            hasStableTargetPage(
                requiresRenderableContent, hasRenderableContent,
                expectedPageIndex, actualPageIndex
            )
        }
    }

    fun hasStableTargetPage(
        requiresRenderableContent: Boolean,
        hasRenderableContent: Boolean,
        expectedPageIndex: Int,
        actualPageIndex: Int
    ): Boolean {
        if (expectedPageIndex != actualPageIndex) return false
        return EpubDirectRenderableContentPolicy.canActivate(
            requiresRenderableContent,
            hasRenderableContent
        )
    }

    fun canCommit(
        requiresRenderableContent: Boolean,
        hasRenderableContent: Boolean,
        hasViewportContent: Boolean,
        expectedPageIndex: Int,
        actualPageIndex: Int
    ): Boolean {
        if (!hasStableTargetPage(
                requiresRenderableContent = requiresRenderableContent,
                hasRenderableContent = hasRenderableContent,
                expectedPageIndex = expectedPageIndex,
                actualPageIndex = actualPageIndex
            )
        ) return false
        return !requiresRenderableContent || hasViewportContent
    }
}
