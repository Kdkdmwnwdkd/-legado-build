package io.legado.app.ui.book.read.epub

import io.legado.app.ui.book.read.epub.EpubDirectPageAnimationPolicy.TurnAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubSimulationPageMotionTest {

    @Test
    fun `next sheet travels completely offscreen and previous sheet returns from offscreen`() {
        for (action in TurnAction.entries) {
            val direction = if (action == TurnAction.Next) 1 else -1
            val motion = EpubSimulationPageMotion(action, direction)
            val frame = EpubSimulationPageMotion.Frame()
            val start = motion.updateFrame(frame, 1000, 1600, 0f).touchX
            val end = motion.updateFrame(frame, 1000, 1600, 1f).touchX
            assertEquals(1000f, frame.cornerX, 0f)
            assertEquals(if (action == TurnAction.Next) 1000f else -1000f, start, 0f)
            assertEquals(if (action == TurnAction.Next) -1000f else 1000f, end, 0f)
        }
    }

    @Test
    fun `rtl mirrors geometry without changing the logical folding page`() {
        for (action in TurnAction.entries) {
            val ltrDirection = if (action == TurnAction.Next) 1 else -1
            val ltr = EpubSimulationPageMotion(action, ltrDirection, 0.12f)
            val rtl = EpubSimulationPageMotion(action, -ltrDirection, 0.12f)
            val leftFrame = EpubSimulationPageMotion.Frame()
            val rightFrame = EpubSimulationPageMotion.Frame()
            for (step in 0..100) {
                ltr.updateFrame(leftFrame, 1080, 1920, step / 100f)
                rtl.updateFrame(rightFrame, 1080, 1920, step / 100f)
                assertEquals(1080f, leftFrame.touchX + rightFrame.touchX, 0.001f)
                assertEquals(1080f, leftFrame.cornerX + rightFrame.cornerX, 0f)
                assertEquals(leftFrame.touchY, rightFrame.touchY, 0f)
            }
        }
    }

    @Test
    fun `fold follows horizontal finger displacement one for one`() {
        for (direction in listOf(-1, 1)) {
            val motion = EpubSimulationPageMotion(TurnAction.Next, direction, 0.1f)
            val gesture = EpubDirectGesturePolicy.DragState(direction, 8)
            val frame = EpubSimulationPageMotion.Frame()
            gesture.update(-direction * 108f)
            val firstX = motion.updateFrame(frame, 1000, 1600, gesture.progress(1000, true)).touchX
            gesture.update(-direction * 308f)
            val nextX = motion.updateFrame(frame, 1000, 1600, gesture.progress(1000, true)).touchX
            assertEquals(-direction * 200f, nextX - firstX, 0.001f)
        }
    }

    @Test
    fun `top and bottom corners track vertical drag and release without a jump`() {
        for (startY in listOf(0.1f, 0.9f)) {
            for (target in listOf(0f, 1f)) {
                val motion = EpubSimulationPageMotion(TurnAction.Next, 1, startY)
                val frame = EpubSimulationPageMotion.Frame()
                motion.updateDrag(0.24f)
                motion.updateFrame(frame, 1000, 1600, 0.2f)
                val dragX = frame.touchX
                val dragY = frame.touchY
                assertEquals(384f, dragY, 0.001f)
                motion.prepareSettle(0.2f, target)
                motion.updateFrame(frame, 1000, 1600, 0.2f)
                assertEquals(dragX, frame.touchX, 0f)
                assertEquals(dragY, frame.touchY, 0f)
                motion.updateFrame(frame, 1000, 1600, target)
                assertTrue(kotlin.math.abs(frame.touchY - frame.cornerY) <= 0.11f)
            }
        }
    }

    @Test
    fun `middle gestures stay flat and previous pages use the text reader bottom corner`() {
        for (startY in listOf(0.4f, 0.6f)) {
            val motion = EpubSimulationPageMotion(TurnAction.Next, 1, startY)
            motion.updateDrag(0.1f)
            val frame = motion.updateFrame(EpubSimulationPageMotion.Frame(), 1000, 1600, 0.3f)
            assertTrue(kotlin.math.abs(frame.touchY - frame.cornerY) <= 0.11f)
        }
        val previous = EpubSimulationPageMotion(TurnAction.Previous, -1, 0.1f)
        previous.updateDrag(0.1f)
        val frame = previous.updateFrame(EpubSimulationPageMotion.Frame(), 1000, 1600, 0.3f)
        assertEquals(1600f, frame.cornerY, 0f)
        assertEquals(1599.9f, frame.touchY, 0.001f)
    }

    @Test
    fun `corner and viewport boundary inputs remain finite`() {
        for (size in listOf(0, 1, 1080, 4096)) {
            for (startY in listOf(0f, 1f, Float.NaN)) {
                val motion = EpubSimulationPageMotion(TurnAction.Next, -1, startY)
                motion.updateDrag(Float.NaN)
                for (progress in listOf(0f, 0.00001f, 0.5f, 0.99999f, 1f)) {
                    val frame = motion.updateFrame(EpubSimulationPageMotion.Frame(), size, size, progress)
                    assertTrue(frame.touchX.isFinite())
                    assertTrue(frame.touchY.isFinite())
                    assertTrue(frame.touchY > 0f && frame.touchY < size.coerceAtLeast(1))
                }
            }
        }
    }
}
