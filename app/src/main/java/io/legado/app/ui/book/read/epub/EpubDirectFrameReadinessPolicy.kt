package io.legado.app.ui.book.read.epub

internal object EpubDirectFrameReadinessPolicy {

    fun isReady(
        expectedChapterIndex: Int,
        actualChapterIndex: Int?,
        expectedPageIndex: Int,
        actualPageIndex: Int?,
        resourcesReady: Boolean,
        requiresRenderableContent: Boolean,
        hasRenderableContent: Boolean,
        hasViewportContent: Boolean
    ): Boolean {
        return actualChapterIndex == expectedChapterIndex &&
            actualPageIndex == expectedPageIndex &&
            resourcesReady &&
            (!requiresRenderableContent || (hasRenderableContent && hasViewportContent))
    }
}
