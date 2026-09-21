package io.legado.app.ui.book.read.epub

internal object EpubDirectNavigationTargetPolicy {

    data class Target(
        val chapterIndex: Int,
        val resetPageOffset: Boolean,
        val boundaryTransition: Boolean,
        val fragmentId: String?
    )

    fun resolve(
        requestedChapterIndex: Int?,
        requestedResetPageOffset: Boolean,
        requestedBoundaryTransition: Boolean,
        requestedFragmentId: String?,
        pendingTarget: Target?,
        currentChapterIndex: Int,
        relativePosition: Int,
        chapterCount: Int,
        preserveChapterIdentity: Boolean = false
    ): Target? {
        if (chapterCount <= 0) return null
        val inherited = pendingTarget.takeIf { requestedChapterIndex == null }
        val rawChapterIndex = requestedChapterIndex
            ?: inherited?.chapterIndex
            ?: (currentChapterIndex + relativePosition)
        val boundaryTransition = inherited?.boundaryTransition ?: requestedBoundaryTransition
        // Ordinary-text TOC selections and accepted boundary turns own their exact
        // stored index. A stale target must not silently become a different chapter.
        val chapterIndex = if (boundaryTransition || preserveChapterIdentity) {
            rawChapterIndex.takeIf { it >= 0 } ?: return null
        } else {
            rawChapterIndex.coerceIn(0, chapterCount - 1)
        }
        return Target(
            chapterIndex = chapterIndex,
            resetPageOffset = inherited?.resetPageOffset ?: requestedResetPageOffset,
            boundaryTransition = boundaryTransition,
            fragmentId = inherited?.fragmentId ?: requestedFragmentId
        )
    }
}
