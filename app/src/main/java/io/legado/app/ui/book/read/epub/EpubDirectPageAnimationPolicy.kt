package io.legado.app.ui.book.read.epub

import io.legado.app.constant.PageAnim
import kotlin.math.abs

internal object EpubDirectPageAnimationPolicy {

    class Frame {
        var sourceTranslationX = 0f
            private set
        var targetTranslationX = 0f
            private set
        var sourceClipLeft = 0f
            private set
        var sourceClipRight = 0f
            private set
        var edgeX: Float? = null
            private set
        var shadowDirection = 0
            private set
        var sourceMaskAlpha = 0
            private set
        var targetMaskAlpha = 0
            private set

        internal fun reset(width: Float) {
            sourceTranslationX = 0f
            targetTranslationX = 0f
            sourceClipLeft = 0f
            sourceClipRight = width
            edgeX = null
            shadowDirection = 0
            sourceMaskAlpha = 0
            targetMaskAlpha = 0
        }

        internal fun source(translationX: Float, clipLeft: Float, clipRight: Float) {
            sourceTranslationX = translationX
            sourceClipLeft = clipLeft
            sourceClipRight = clipRight
        }

        internal fun target(translationX: Float) {
            targetTranslationX = translationX
        }

        internal fun edge(x: Float, direction: Int) {
            edgeX = x
            shadowDirection = direction
        }

        internal fun masks(sourceAlpha: Int = 0, targetAlpha: Int = 0) {
            sourceMaskAlpha = sourceAlpha
            targetMaskAlpha = targetAlpha
        }
    }

    enum class Style {
        None,
        Cover,
        Slide,
        Simulation,
        LinkedCover
    }

    enum class TurnAction {
        Next,
        Previous
    }

    fun style(pageAnim: Int, horizontal: Boolean): Style {
        if (!horizontal) return Style.None
        return when (pageAnim) {
            PageAnim.coverPageAnim -> Style.Cover
            PageAnim.slidePageAnim -> Style.Slide
            PageAnim.simulationPageAnim -> Style.Simulation
            PageAnim.linkedCoverPageAnim -> Style.LinkedCover
            else -> Style.None
        }
    }

    fun requiresLayoutReload(previousPageAnim: Int, nextPageAnim: Int): Boolean {
        return (previousPageAnim == PageAnim.scrollPageAnim) !=
            (nextPageAnim == PageAnim.scrollPageAnim)
    }

    fun durationMillis(style: Style, baseDurationMillis: Int): Long {
        if (style == Style.None) return 0L
        val base = baseDurationMillis.coerceAtLeast(1).toLong()
        return when (style) {
            Style.Simulation -> base * 2L
            Style.LinkedCover -> base * 135L / 100L
            else -> base
        }
    }

    fun settleDurationMillis(
        style: Style,
        baseDurationMillis: Int,
        startProgress: Float,
        targetProgress: Float,
        viewportWidth: Int,
        velocityTowardsTarget: Float = 0f
    ): Long {
        val fullDuration = durationMillis(style, baseDurationMillis)
        if (fullDuration == 0L) return 0L
        val remaining = abs(targetProgress - startProgress).coerceIn(0f, 1f)
        val normalDuration = fullDuration * remaining
        val distance = viewportWidth.coerceAtLeast(1) * remaining * if (style == Style.Simulation) 2f else 1f
        val flingDuration = if (velocityTowardsTarget.isFinite() && velocityTowardsTarget > 0f) {
            // Carry the finger's speed into a deceleration, rather than moving at
            // that speed until the last frame and stopping abruptly.
            distance * 2000f / velocityTowardsTarget
        } else {
            normalDuration
        }
        val maximum = minOf(fullDuration, baseDurationMillis.coerceAtLeast(1) * 120L / 100L)
        return minOf(normalDuration, flingDuration).toLong()
            .coerceIn(minOf(72L, maximum), maximum)
    }

    class SettleMotion internal constructor(
        private val start: Float,
        private val target: Float,
        val durationMillis: Long,
        private val initialSlope: Double
    ) {
        fun progress(fraction: Float): Float {
            if (!fraction.isFinite() || fraction <= 0f) return start
            if (fraction >= 1f) return target
            val t = fraction.toDouble()
            val m = initialSlope
            // Quintic Hermite motion: preserve release velocity, with zero
            // acceleration at release and zero velocity/acceleration at rest.
            // m in [0, 2.5] keeps its Bezier control points in order: no overshoot
            // or bounce, including when returning a cancelled page.
            val eased = t * (m + t * t * (10.0 - 6.0 * m +
                t * (8.0 * m - 15.0 + t * (6.0 - 3.0 * m))))
            return (start + (target - start) * eased).toFloat()
                .coerceIn(minOf(start, target), maxOf(start, target))
        }
    }

    fun settleMotion(
        style: Style,
        baseDurationMillis: Int,
        startProgress: Float,
        targetProgress: Float,
        viewportWidth: Int,
        velocityTowardsTarget: Float = 0f
    ): SettleMotion {
        val duration = settleDurationMillis(
            style, baseDurationMillis, startProgress, targetProgress, viewportWidth, velocityTowardsTarget
        )
        val distance = viewportWidth.coerceAtLeast(1).toDouble() * abs(targetProgress - startProgress) *
            if (style == Style.Simulation) 2.0 else 1.0
        val slope = if (distance > 0.0 && velocityTowardsTarget.isFinite() && velocityTowardsTarget > 0f) {
            (velocityTowardsTarget * duration / (distance * 1000.0)).coerceIn(0.0, 2.5)
        } else {
            0.0
        }
        return SettleMotion(startProgress, targetProgress, duration, slope)
    }

    fun settledProgress(style: Style, progress: Float): Float {
        val value = progress.coerceIn(0f, 1f)
        if (style != Style.LinkedCover) return value
        val eased = 1f - (1f - value) * (1f - value) * (1f - value)
        return (0.2f * value + 0.8f * eased).coerceIn(0f, 1f)
    }

    fun liveTargetTranslationX(
        style: Style,
        action: TurnAction,
        visualDirection: Int,
        progress: Float,
        viewportWidth: Int
    ): Float {
        val width = viewportWidth.coerceAtLeast(1).toFloat()
        val value = progress.coerceIn(0f, 1f)
        val signedWidth = (if (visualDirection < 0) -1 else 1) * width
        return when (style) {
            Style.Cover -> if (action == TurnAction.Previous) signedWidth * (1f - value) else 0f
            Style.Slide -> signedWidth * (1f - value)
            Style.LinkedCover -> if (action == TurnAction.Next) {
                signedWidth * LINKED_OFFSET_RATIO * (1f - value)
            } else {
                signedWidth * (1f - value)
            }
            Style.Simulation, Style.None -> 0f
        }
    }

    fun frame(
        style: Style,
        action: TurnAction,
        visualDirection: Int,
        progress: Float,
        viewportWidth: Int
    ): Frame {
        return updateFrame(Frame(), style, action, visualDirection, progress, viewportWidth)
    }

    fun updateFrame(
        frame: Frame,
        style: Style,
        action: TurnAction,
        visualDirection: Int,
        progress: Float,
        viewportWidth: Int
    ): Frame {
        val width = viewportWidth.coerceAtLeast(1).toFloat()
        val value = progress.coerceIn(0f, 1f)
        val direction = if (visualDirection < 0) -1 else 1
        val signedWidth = direction * width
        frame.reset(width)

        fun outgoingEdge(sourceX: Float): Float {
            return if (direction > 0) sourceX + width else sourceX
        }

        fun incomingEdge(targetX: Float): Float {
            return if (direction < 0) targetX + width else targetX
        }

        fun setPreviousSource(targetX: Float, sourceX: Float = 0f) {
            val edge = incomingEdge(targetX)
            if (direction < 0) {
                frame.source(sourceX, edge.coerceIn(0f, width), width)
            } else {
                frame.source(sourceX, 0f, edge.coerceIn(0f, width))
            }
            frame.target(targetX)
            frame.edge(edge, -direction)
        }

        when (style) {
            Style.Cover -> if (action == TurnAction.Next) {
                val sourceX = -signedWidth * value
                frame.source(sourceX, 0f, width)
                frame.edge(outgoingEdge(sourceX), direction)
            } else {
                val targetX = signedWidth * (1f - value)
                setPreviousSource(targetX)
            }

            Style.Slide -> {
                frame.source(-signedWidth * value, 0f, width)
                frame.target(signedWidth * (1f - value))
            }

            Style.LinkedCover -> if (action == TurnAction.Next) {
                val sourceX = -signedWidth * value
                frame.source(sourceX, 0f, width)
                frame.target(signedWidth * LINKED_OFFSET_RATIO * (1f - value))
                frame.edge(outgoingEdge(sourceX), direction)
                frame.masks(targetAlpha = (LINKED_TARGET_MASK_ALPHA * (1f - value)).toInt())
            } else {
                val targetX = signedWidth * (1f - value)
                setPreviousSource(
                    targetX = targetX,
                    sourceX = -signedWidth * LINKED_OFFSET_RATIO * value
                )
                frame.masks(sourceAlpha = (LINKED_SOURCE_MASK_ALPHA * value).toInt())
            }

            Style.Simulation, Style.None -> Unit
        }
        return frame
    }

    fun requiresMovingLiveTarget(style: Style, action: TurnAction): Boolean {
        return style == Style.Slide || style == Style.LinkedCover ||
            (style == Style.Cover && action == TurnAction.Previous)
    }

    fun hasRequiredBitmapFrames(
        style: Style,
        action: TurnAction,
        hasTargetBitmap: Boolean
    ): Boolean {
        return style != Style.Simulation || action != TurnAction.Previous || hasTargetBitmap
    }

    fun consumesRepeatedTurn(animationRunning: Boolean): Boolean {
        return animationRunning
    }

    fun visualDirection(logicalDirection: Int, rtl: Boolean): Int {
        val normalized = if (logicalDirection < 0) -1 else 1
        return if (rtl) -normalized else normalized
    }

    fun turnAction(logicalDirection: Int): TurnAction {
        return if (logicalDirection < 0) TurnAction.Previous else TurnAction.Next
    }

    private const val LINKED_OFFSET_RATIO = 0.2f
    private const val LINKED_TARGET_MASK_ALPHA = 102
    private const val LINKED_SOURCE_MASK_ALPHA = 76
}
