package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDirectGesturePolicyTest {

    @Test
    fun `slow scrolling past the long press timeout cannot arm selection`() {
        for (elapsed in listOf(500L, 1_000L, 8_000L)) {
            assertFalse(EpubDirectGesturePolicy.startsLongPressSelection(elapsed, 500L, true, false, false))
        }
    }

    @Test
    fun `stationary long press waits for the threshold and respects cancelled gestures`() {
        assertFalse(EpubDirectGesturePolicy.startsLongPressSelection(499L, 500L, false, false, false))
        assertTrue(EpubDirectGesturePolicy.startsLongPressSelection(500L, 500L, false, false, false))
        assertFalse(EpubDirectGesturePolicy.startsLongPressSelection(600L, 500L, false, true, false))
        assertFalse(EpubDirectGesturePolicy.startsLongPressSelection(600L, 500L, false, false, true))
    }

    @Test
    fun `web touch cancellation starts on the direction lock move`() {
        assertTrue(EpubDirectGesturePolicy.shouldCancelWebTouch(true, false, false))
        assertFalse(EpubDirectGesturePolicy.shouldCancelWebTouch(true, true, false))
        assertFalse(EpubDirectGesturePolicy.shouldCancelWebTouch(true, false, true))
        assertFalse(EpubDirectGesturePolicy.shouldCancelWebTouch(false, false, false))
    }

    @Test
    fun `horizontal drag does not steal vertical scroll or text selection`() {
        assertFalse(EpubDirectGesturePolicy.startsHorizontalDrag(80f, 10f, 8, true, false))
        assertFalse(EpubDirectGesturePolicy.startsHorizontalDrag(80f, 10f, 8, false, true))
        assertFalse(EpubDirectGesturePolicy.startsHorizontalDrag(80f, 10f, 8, false, false, true))
        assertFalse(EpubDirectGesturePolicy.startsHorizontalDrag(6f, 5f, 8, false, false))
        assertFalse(EpubDirectGesturePolicy.startsHorizontalDrag(8f, 9f, 8, false, false))
        assertTrue(EpubDirectGesturePolicy.startsHorizontalDrag(9f, 4f, 8, false, false))
    }

    @Test
    fun `page turn accepts deliberate drag or directional fling`() {
        assertTrue(EpubDirectGesturePolicy.shouldTurnPage(100f, 15f, 200f, 8, 1000, 1000))
        assertTrue(EpubDirectGesturePolicy.shouldTurnPage(-30f, 5f, -1600f, 8, 1000, 1000))
        assertFalse(EpubDirectGesturePolicy.shouldTurnPage(40f, 5f, 200f, 8, 1000, 1000))
        assertFalse(EpubDirectGesturePolicy.shouldTurnPage(30f, 5f, -1600f, 8, 1000, 1000))
        assertFalse(EpubDirectGesturePolicy.shouldTurnPage(100f, 120f, 2000f, 8, 1000, 1000))
    }

    @Test
    fun `first drag frame preserves all movement beyond touch slop in either direction`() {
        for (direction in listOf(-1, 1)) {
            val gesture = EpubDirectGesturePolicy.DragState(direction, 8)
            gesture.update(-direction * 108f)
            assertEquals(0.1f, gesture.progress(1000, simulation = false), 0.0001f)
            gesture.update(-direction * 258f)
            assertEquals(0.25f, gesture.progress(1000, simulation = false), 0.0001f)
            assertEquals(0.125f, gesture.progress(1000, simulation = true), 0.0001f)
        }
    }

    @Test
    fun `release jitter does not reverse an intentional drag`() {
        for (direction in listOf(-1, 1)) {
            val gesture = EpubDirectGesturePolicy.DragState(direction, 8)
            for (distance in listOf(12f, 80f, 240f, 239f, 240f, 237f)) {
                gesture.update(-direction * distance)
            }
            assertTrue(gesture.shouldCommit(0f, 1000, 1000))
        }
    }

    @Test
    fun `small reverse steps accumulate and forward drag can resume after cancellation`() {
        val gesture = EpubDirectGesturePolicy.DragState(1, 8)
        gesture.update(-240f)
        for (distance in 239 downTo 228) gesture.update(-distance.toFloat())
        assertFalse(gesture.shouldCommit(0f, 1000, 1000))
        gesture.update(-233f)
        assertFalse(gesture.shouldCommit(0f, 1000, 1000))
        gesture.update(-238f)
        assertTrue(gesture.shouldCommit(0f, 1000, 1000))
    }

    @Test
    fun `a short drag rolls back but a deliberate short fling commits`() {
        val gesture = EpubDirectGesturePolicy.DragState(1, 8)
        gesture.update(-30f)
        assertFalse(gesture.shouldCommit(-200f, 1000, 1000))
        assertTrue(gesture.shouldCommit(-1600f, 1000, 1000))
        assertFalse(gesture.shouldCommit(1600f, 1000, 1000))
        gesture.update(2f)
        assertEquals(0f, gesture.progress(1000, simulation = false), 0f)
        assertFalse(gesture.shouldCommit(1600f, 1000, 1000))
    }

    @Test
    fun `the last up sample participates in the release decision`() {
        val gesture = EpubDirectGesturePolicy.DragState(1, 8)
        gesture.update(-160f)
        assertTrue(gesture.shouldCommit(0f, 1000, 1000))
        gesture.update(-120f)
        assertFalse(gesture.shouldCommit(0f, 1000, 1000))
    }

    @Test
    fun `chapter boundary settle reaches the endpoint while activation stays covered`() {
        assertEquals(
            1f,
            EpubDirectGesturePolicy.interactiveSettleTarget(commit = true),
            0.001f
        )
    }

    @Test
    fun `interactive settle finishes only after a committed target is ready`() {
        assertEquals(
            0f,
            EpubDirectGesturePolicy.interactiveSettleTarget(commit = false),
            0.001f
        )
        assertEquals(
            1f,
            EpubDirectGesturePolicy.interactiveSettleTarget(commit = true),
            0.001f
        )
        assertFalse(EpubDirectGesturePolicy.canFinalizeInteractiveCommit(false, true))
        assertFalse(EpubDirectGesturePolicy.canFinalizeInteractiveCommit(true, false))
        assertTrue(EpubDirectGesturePolicy.canFinalizeInteractiveCommit(true, true))
    }
}
