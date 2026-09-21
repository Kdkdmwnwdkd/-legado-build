package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDirectChapterTurnPolicyTest {

    @Test
    fun forwardBoundaryBindsTheResolvedReadableChapterStart() {
        assertTrue(canBind(source = 7, target = 8, direction = 1))
        assertTrue(canBind(source = 7, expectedTarget = 9, target = 9, direction = 1))
        assertFalse(canBind(source = 7, expectedTarget = 9, target = 8, direction = 1))
        assertFalse(canBind(source = 7, target = 8, direction = 1, initialPage = 2))
        assertFalse(canBind(source = 7, target = 8, direction = 1, openAtEnd = true))
    }

    @Test
    fun backwardBoundaryBindsTheResolvedReadableChapterEnd() {
        assertTrue(canBind(source = 7, target = 6, direction = -1, openAtEnd = true))
        assertTrue(canBind(source = 7, expectedTarget = 5, target = 5, direction = -1, openAtEnd = true))
        assertFalse(canBind(source = 7, expectedTarget = 5, target = 6, direction = -1, openAtEnd = true))
        assertFalse(canBind(source = 7, target = 6, direction = -1, openAtEnd = false))
    }

    @Test
    fun explicitAndPositionedNavigationNeverConsumesABoundaryTurn() {
        assertFalse(canBind(boundaryTransition = false))
        assertFalse(canBind(hasProgress = true))
        assertFalse(canBind(targetFragment = "note-4", chapterStartFragment = "chapter-2"))
        assertTrue(canBind(targetFragment = "chapter-2", chapterStartFragment = "chapter-2"))
        assertFalse(canBind(sourceRtl = false, targetRtl = true))
        assertFalse(canBind(direction = 0))
    }

    @Test
    fun activationRequiresTheRequestedChapterEdge() {
        assertTrue(EpubDirectChapterTurnPolicy.isExpectedTargetPage(1, 0, 5))
        assertFalse(EpubDirectChapterTurnPolicy.isExpectedTargetPage(1, 1, 5))
        assertTrue(EpubDirectChapterTurnPolicy.isExpectedTargetPage(-1, 4, 5))
        assertFalse(EpubDirectChapterTurnPolicy.isExpectedTargetPage(-1, 3, 5))
        assertFalse(EpubDirectChapterTurnPolicy.isExpectedTargetPage(-1, 0, 0))
    }

    private fun canBind(
        boundaryTransition: Boolean = true,
        source: Int = 7,
        target: Int = 8,
        expectedTarget: Int = target,
        direction: Int = 1,
        initialPage: Int = 0,
        openAtEnd: Boolean = false,
        hasProgress: Boolean = false,
        targetFragment: String? = null,
        chapterStartFragment: String? = null,
        sourceRtl: Boolean = false,
        targetRtl: Boolean = sourceRtl
    ): Boolean {
        return EpubDirectChapterTurnPolicy.canBind(
            boundaryTransition = boundaryTransition,
            sourceChapterIndex = source,
            expectedTargetChapterIndex = expectedTarget,
            targetChapterIndex = target,
            logicalDirection = direction,
            initialPageIndex = initialPage,
            openAtEnd = openAtEnd,
            hasProgress = hasProgress,
            targetFragmentId = targetFragment,
            chapterStartFragmentId = chapterStartFragment,
            sourceRtl = sourceRtl,
            targetRtl = targetRtl
        )
    }
}
