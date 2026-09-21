package io.legado.app.ui.book.read

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAloudPanelLayoutTest {

    @Test
    fun everyCueChangeRequestsUiRebuild() {
        assertEquals(
            true,
            ReadAloudPanelLayout.playbackTargetChanged(
                currentCueIndex = 4,
                currentChapterIndex = 2,
                currentPlanKey = "plan",
                nextCueIndex = 5,
                nextChapterIndex = 2,
                nextPlanKey = "plan"
            )
        )
    }

    @Test
    fun phaseOnlyChangeKeepsExistingCueLayout() {
        assertEquals(
            false,
            ReadAloudPanelLayout.playbackTargetChanged(
                currentCueIndex = 5,
                currentChapterIndex = 2,
                currentPlanKey = "plan",
                nextCueIndex = 5,
                nextChapterIndex = 2,
                nextPlanKey = "plan"
            )
        )
    }

    @Test
    fun centeredItemNeedsNoScroll() {
        assertEquals(
            0f,
            ReadAloudPanelLayout.centeredScrollDelta(
                viewportStartOffset = 0,
                viewportEndOffset = 1000,
                itemOffset = 450,
                itemSize = 100
            )
        )
    }

    @Test
    fun itemBelowCenterScrollsForward() {
        assertEquals(
            350f,
            ReadAloudPanelLayout.centeredScrollDelta(
                viewportStartOffset = 0,
                viewportEndOffset = 1000,
                itemOffset = 800,
                itemSize = 100
            )
        )
    }

    @Test
    fun itemAboveCenterScrollsBackward() {
        assertEquals(
            -350f,
            ReadAloudPanelLayout.centeredScrollDelta(
                viewportStartOffset = 0,
                viewportEndOffset = 1000,
                itemOffset = 100,
                itemSize = 100
            )
        )
    }

    @Test
    fun contentPaddingUsesShiftedViewportCoordinates() {
        assertEquals(
            0f,
            ReadAloudPanelLayout.centeredScrollDelta(
                viewportStartOffset = -146,
                viewportEndOffset = 254,
                itemOffset = 4,
                itemSize = 100
            )
        )
    }
}
