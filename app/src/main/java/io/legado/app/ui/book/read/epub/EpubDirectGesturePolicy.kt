package io.legado.app.ui.book.read.epub

import kotlin.math.abs
import kotlin.math.max

internal object EpubDirectGesturePolicy {

    /** Keep the text reader's pull-back-to-cancel gesture without reacting to finger jitter. */
    class DragState(visualDirection: Int, touchSlop: Int) {
        private val direction = if (visualDirection < 0) -1 else 1
        private val slop = touchSlop.coerceAtLeast(1)
        private val startDeltaX = -direction * slop.toFloat()
        private var directionAnchor = 0f
        private var deltaX = 0f
        var reversed = false
            private set

        fun update(deltaX: Float) {
            if (!deltaX.isFinite()) return
            this.deltaX = deltaX
            val distance = -direction * (deltaX - startDeltaX)
            if (reversed) {
                directionAnchor = minOf(directionAnchor, distance)
                if (distance - directionAnchor >= slop) {
                    reversed = false
                    directionAnchor = distance
                }
            } else {
                directionAnchor = maxOf(directionAnchor, distance)
                if (directionAnchor - distance >= slop) {
                    reversed = true
                    directionAnchor = distance
                }
            }
        }

        fun progress(viewportWidth: Int, simulation: Boolean): Float {
            // A sheet's corner travels two page widths to uncover the whole next page.
            val travelWidth = viewportWidth.coerceAtLeast(1).toFloat() * if (simulation) 2f else 1f
            return (-direction * (deltaX - startDeltaX) / travelWidth).coerceIn(0f, 1f)
        }

        fun shouldCommit(velocityX: Float, minimumFlingVelocity: Int, viewportWidth: Int): Boolean {
            if (reversed || -direction * deltaX <= 0f) return false
            return shouldTurnPage(deltaX, 0f, velocityX, slop, minimumFlingVelocity, viewportWidth)
        }
    }

    fun startsLongPressSelection(
        elapsedMillis: Long,
        timeoutMillis: Long,
        moved: Boolean,
        horizontalDrag: Boolean,
        cancelled: Boolean
    ): Boolean = !moved && !horizontalDrag && !cancelled && elapsedMillis >= timeoutMillis

    fun shouldCancelWebTouch(
        horizontalDrag: Boolean,
        embeddedInteraction: Boolean,
        alreadyCancelled: Boolean
    ): Boolean {
        return horizontalDrag &&
            !embeddedInteraction &&
            !alreadyCancelled
    }

    fun startsHorizontalDrag(
        deltaX: Float,
        deltaY: Float,
        touchSlop: Int,
        verticalMode: Boolean,
        selectionActive: Boolean,
        embeddedInteraction: Boolean = false
    ): Boolean {
        if (verticalMode || selectionActive || embeddedInteraction) return false
        val threshold = touchSlop.coerceAtLeast(1).toFloat()
        return abs(deltaX) > abs(deltaY) &&
            deltaX * deltaX + deltaY * deltaY > threshold * threshold
    }

    fun shouldTurnPage(
        deltaX: Float,
        deltaY: Float,
        velocityX: Float,
        touchSlop: Int,
        minimumFlingVelocity: Int,
        viewportWidth: Int
    ): Boolean {
        if (abs(deltaX) <= abs(deltaY)) return false
        val enoughDistance = abs(deltaX) >= max(touchSlop * 3f, viewportWidth * 0.08f)
        val enoughVelocity = abs(deltaX) >= touchSlop * 2f &&
            abs(velocityX) >= minimumFlingVelocity &&
            deltaX * velocityX > 0f
        return enoughDistance || enoughVelocity
    }

    fun interactiveSettleTarget(commit: Boolean): Float = if (commit) 1f else 0f

    fun canFinalizeInteractiveCommit(commit: Boolean, targetReady: Boolean): Boolean {
        return commit && targetReady
    }
}
