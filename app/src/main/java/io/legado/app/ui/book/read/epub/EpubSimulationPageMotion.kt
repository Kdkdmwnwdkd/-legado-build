package io.legado.app.ui.book.read.epub

import io.legado.app.ui.book.read.epub.EpubDirectPageAnimationPolicy.TurnAction
import kotlin.math.abs

/** Viewport coordinates shared by dragging and settling; bitmaps keep their own orientation. */
internal class EpubSimulationPageMotion(
    private val action: TurnAction,
    visualDirection: Int,
    startYFraction: Float = 0.9f
) {
    class Frame {
        var cornerX = 0f
        var cornerY = 0f
        var touchX = 0f
        var touchY = 0f
    }

    private val cornerOnRight = (action == TurnAction.Next) == (visualDirection > 0)
    private val startY = startYFraction.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0.9f
    private val cornerAtBottom = action == TurnAction.Previous || startY > 0.5f
    private val horizontal = action == TurnAction.Previous || startY > 1f / 3f && startY < 2f / 3f
    private val cornerYFraction = if (cornerAtBottom) 1f else 0f
    private var dragY = startY
    private var dragging = false
    private var settling = false
    private var settleStartProgress = 0f
    private var settleTargetProgress = 1f
    private var settleStartY = startY

    fun updateDrag(yFraction: Float) {
        if (!yFraction.isFinite()) return
        dragging = true
        settling = false
        dragY = yFraction.coerceIn(0f, 1f)
    }

    fun prepareSettle(startProgress: Float, targetProgress: Float) {
        settleStartY = touchYFraction(startProgress)
        settleStartProgress = startProgress
        settleTargetProgress = targetProgress
        settling = true
        dragging = false
    }

    fun updateFrame(frame: Frame, width: Int, height: Int, progress: Float): Frame {
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        val value = progress.coerceIn(0f, 1f)
        val canonicalX = if (action == TurnAction.Next) w * (1f - 2f * value) else w * (2f * value - 1f)
        frame.cornerX = if (cornerOnRight) w else 0f
        frame.cornerY = if (cornerAtBottom) h else 0f
        frame.touchX = if (cornerOnRight) canonicalX else w - canonicalX
        frame.touchY = (touchYFraction(value) * h).coerceIn(0.1f, h - 0.1f)
        return frame
    }

    private fun touchYFraction(progress: Float): Float {
        if (horizontal) return cornerYFraction
        if (settling) {
            val distance = settleTargetProgress - settleStartProgress
            val fraction = if (abs(distance) < 0.0001f) 1f else {
                ((progress - settleStartProgress) / distance).coerceIn(0f, 1f)
            }
            return settleStartY + (cornerYFraction - settleStartY) * fraction
        }
        if (dragging) return dragY
        return startY + (cornerYFraction - startY) * progress
    }
}
