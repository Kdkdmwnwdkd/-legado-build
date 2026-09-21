package io.legado.app.ui.book.read.epub

internal object EpubDirectChapterTurnPolicy {

    fun canBind(
        boundaryTransition: Boolean,
        sourceChapterIndex: Int,
        expectedTargetChapterIndex: Int,
        targetChapterIndex: Int,
        logicalDirection: Int,
        initialPageIndex: Int,
        openAtEnd: Boolean,
        hasProgress: Boolean,
        targetFragmentId: String?,
        chapterStartFragmentId: String?,
        sourceRtl: Boolean,
        targetRtl: Boolean
    ): Boolean {
        if (!boundaryTransition || (logicalDirection != -1 && logicalDirection != 1)) return false
        if (sourceChapterIndex < 0 || expectedTargetChapterIndex < 0 ||
            targetChapterIndex != expectedTargetChapterIndex
        ) {
            return false
        }
        if (logicalDirection > 0 && expectedTargetChapterIndex <= sourceChapterIndex) return false
        if (logicalDirection < 0 && expectedTargetChapterIndex >= sourceChapterIndex) return false
        if (hasProgress) return false
        val targetFragment = targetFragmentId?.takeIf { it.isNotBlank() }
        val chapterStart = chapterStartFragmentId?.takeIf { it.isNotBlank() }
        if (targetFragment != null && targetFragment != chapterStart) return false
        if (sourceRtl != targetRtl) return false
        return if (logicalDirection > 0) {
            initialPageIndex == 0 && !openAtEnd
        } else {
            openAtEnd
        }
    }

    fun isExpectedTargetPage(
        logicalDirection: Int,
        targetPageIndex: Int,
        targetPageCount: Int
    ): Boolean {
        if ((logicalDirection != -1 && logicalDirection != 1) || targetPageCount <= 0) return false
        val expectedPage = if (logicalDirection > 0) 0 else targetPageCount - 1
        return targetPageIndex == expectedPage
    }
}
