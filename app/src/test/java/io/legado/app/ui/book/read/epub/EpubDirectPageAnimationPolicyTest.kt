package io.legado.app.ui.book.read.epub

import io.legado.app.constant.PageAnim
import io.legado.app.constant.PageAnimationSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDirectPageAnimationPolicyTest {

    @Test
    fun mapsReaderAnimationModes() {
        assertEquals(
            EpubDirectPageAnimationPolicy.Style.Cover,
            EpubDirectPageAnimationPolicy.style(PageAnim.coverPageAnim, horizontal = true)
        )
        assertEquals(
            EpubDirectPageAnimationPolicy.Style.Slide,
            EpubDirectPageAnimationPolicy.style(PageAnim.slidePageAnim, horizontal = true)
        )
        assertEquals(
            EpubDirectPageAnimationPolicy.Style.Simulation,
            EpubDirectPageAnimationPolicy.style(PageAnim.simulationPageAnim, horizontal = true)
        )
        assertEquals(
            EpubDirectPageAnimationPolicy.Style.LinkedCover,
            EpubDirectPageAnimationPolicy.style(PageAnim.linkedCoverPageAnim, horizontal = true)
        )
        assertEquals(
            EpubDirectPageAnimationPolicy.Style.None,
            EpubDirectPageAnimationPolicy.style(PageAnim.noAnim, horizontal = true)
        )
        assertEquals(
            EpubDirectPageAnimationPolicy.Style.None,
            EpubDirectPageAnimationPolicy.style(PageAnim.coverPageAnim, horizontal = false)
        )
    }

    @Test
    fun changingBetweenHorizontalAnimationsKeepsTheExistingDocument() {
        assertFalse(
            EpubDirectPageAnimationPolicy.requiresLayoutReload(
                previousPageAnim = PageAnim.coverPageAnim,
                nextPageAnim = PageAnim.simulationPageAnim
            )
        )
        assertFalse(
            EpubDirectPageAnimationPolicy.requiresLayoutReload(
                previousPageAnim = PageAnim.noAnim,
                nextPageAnim = PageAnim.linkedCoverPageAnim
            )
        )
    }

    @Test
    fun changingAcrossScrollBoundaryRequiresLayoutReload() {
        assertTrue(
            EpubDirectPageAnimationPolicy.requiresLayoutReload(
                previousPageAnim = PageAnim.coverPageAnim,
                nextPageAnim = PageAnim.scrollPageAnim
            )
        )
        assertTrue(
            EpubDirectPageAnimationPolicy.requiresLayoutReload(
                previousPageAnim = PageAnim.scrollPageAnim,
                nextPageAnim = PageAnim.noAnim
            )
        )
    }

    @Test
    fun liveTargetTranslationAndDurationFollowAnimationStyle() {
        assertEquals(
            1080f,
            EpubDirectPageAnimationPolicy.liveTargetTranslationX(
                EpubDirectPageAnimationPolicy.Style.Slide,
                EpubDirectPageAnimationPolicy.TurnAction.Next,
                visualDirection = 1,
                progress = 0f,
                viewportWidth = 1080
            ),
            0.001f
        )
        assertEquals(
            -540f,
            EpubDirectPageAnimationPolicy.liveTargetTranslationX(
                EpubDirectPageAnimationPolicy.Style.Slide,
                EpubDirectPageAnimationPolicy.TurnAction.Previous,
                visualDirection = -1,
                progress = 0.5f,
                viewportWidth = 1080
            ),
            0.001f
        )
        assertEquals(
            0f,
            EpubDirectPageAnimationPolicy.liveTargetTranslationX(
                EpubDirectPageAnimationPolicy.Style.Simulation,
                EpubDirectPageAnimationPolicy.TurnAction.Next,
                visualDirection = 1,
                progress = 0.5f,
                viewportWidth = 1080
            ),
            0.001f
        )
        assertEquals(
            0f,
            EpubDirectPageAnimationPolicy.liveTargetTranslationX(
                EpubDirectPageAnimationPolicy.Style.Slide,
                EpubDirectPageAnimationPolicy.TurnAction.Next,
                visualDirection = 1,
                progress = 1f,
                viewportWidth = 1080
            ),
            0.001f
        )
        assertEquals(
            600L,
            EpubDirectPageAnimationPolicy.durationMillis(
                EpubDirectPageAnimationPolicy.Style.Simulation,
                PageAnimationSpeed.STANDARD.durationMillis
            )
        )
        assertEquals(
            405L,
            EpubDirectPageAnimationPolicy.durationMillis(
                EpubDirectPageAnimationPolicy.Style.LinkedCover,
                PageAnimationSpeed.STANDARD.durationMillis
            )
        )
        assertEquals(
            360L,
            EpubDirectPageAnimationPolicy.durationMillis(
                EpubDirectPageAnimationPolicy.Style.Simulation,
                PageAnimationSpeed.EXTREME.durationMillis
            )
        )
        assertEquals(
            567L,
            EpubDirectPageAnimationPolicy.durationMillis(
                EpubDirectPageAnimationPolicy.Style.LinkedCover,
                PageAnimationSpeed.RELAXED.durationMillis
            )
        )
        assertEquals(
            0L,
            EpubDirectPageAnimationPolicy.durationMillis(
                EpubDirectPageAnimationPolicy.Style.None,
                PageAnimationSpeed.ELEGANT.durationMillis
            )
        )
    }

    @Test
    fun linkedCoverSettlementMatchesTheReaderDrawEasing() {
        assertEquals(
            0.8f,
            EpubDirectPageAnimationPolicy.settledProgress(
                EpubDirectPageAnimationPolicy.Style.LinkedCover,
                0.5f
            ),
            0.001f
        )
        assertEquals(
            0.5f,
            EpubDirectPageAnimationPolicy.settledProgress(
                EpubDirectPageAnimationPolicy.Style.Cover,
                0.5f
            ),
            0.001f
        )
    }

    @Test
    fun settlementStartsAtTheDraggedPositionAndStaysWithinTheRemainingSegment() {
        for (style in EpubDirectPageAnimationPolicy.Style.entries) {
            for (target in listOf(0f, 1f)) {
                val start = 0.37f
                val motion = EpubDirectPageAnimationPolicy.settleMotion(style, 300, start, target, 1080)
                assertEquals(start, motion.progress(0f), 0f)
                assertEquals(target, motion.progress(1f), 0f)
                var previous = start
                for (step in 1..100) {
                    val next = motion.progress(step / 100f)
                    assertTrue(next in minOf(start, target)..maxOf(start, target))
                    assertTrue(if (target > start) next >= previous else next <= previous)
                    previous = next
                }
            }
        }
    }

    @Test
    fun settlementUsesRemainingDistanceAndOnlySpeedsUpForAForwardFling() {
        val style = EpubDirectPageAnimationPolicy.Style.Slide
        fun duration(velocity: Float) = EpubDirectPageAnimationPolicy.settleDurationMillis(
            style, 300, 0.4f, 1f, 1000, velocity
        )
        assertEquals(180L, duration(0f))
        assertEquals(180L, duration(-8000f))
        assertEquals(150L, duration(8000f))
        assertEquals(72L, duration(100000f))
        assertEquals(
            360L,
            EpubDirectPageAnimationPolicy.settleDurationMillis(
                EpubDirectPageAnimationPolicy.Style.Simulation, 300, 0.25f, 1f, 1000
            )
        )
        assertEquals(
            72L,
            EpubDirectPageAnimationPolicy.settleDurationMillis(style, 300, 0.01f, 0f, 1000)
        )
    }

    @Test
    fun releaseKeepsFingerSpeedForBothCompletionAndPullBack() {
        for (style in EpubDirectPageAnimationPolicy.Style.entries.filter { it != EpubDirectPageAnimationPolicy.Style.None }) {
            val travelWidth = if (style == EpubDirectPageAnimationPolicy.Style.Simulation) 2000f else 1000f
            for (target in listOf(0f, 1f)) {
                for (speed in listOf(1200f, 6000f)) {
                    val start = 0.37f
                    val motion = EpubDirectPageAnimationPolicy.settleMotion(style, 300, start, target, 1000, speed)
                    val distance = kotlin.math.abs(motion.progress(0.001f) - start) * travelWidth
                    val measuredSpeed = distance / (motion.durationMillis * 0.000001f)
                    assertEquals("$style, target=$target", speed, measuredSpeed, speed * 0.005f)
                }
            }
        }
    }

    @Test
    fun releasingAStationaryPageDoesNotKickItIntoMotion() {
        val motion = EpubDirectPageAnimationPolicy.settleMotion(
            EpubDirectPageAnimationPolicy.Style.Slide, 300, 0.4f, 1f, 1000, 0f
        )
        assertTrue((motion.progress(0.01f) - 0.4f) * 1000f < 0.02f)
        assertTrue(motion.progress(0.5f) > 0.4f)
    }

    @Test
    fun fastReleaseDeceleratesToRestInsteadOfStoppingAtFullSpeed() {
        val motion = EpubDirectPageAnimationPolicy.settleMotion(
            EpubDirectPageAnimationPolicy.Style.Slide, 300, 0.4f, 1f, 1000, 8000f
        )
        val segmentSpeeds = (0 until 100).map { step ->
            (motion.progress((step + 1) / 100f) - motion.progress(step / 100f)) *
                1000f / (motion.durationMillis * 0.00001f)
        }
        assertEquals(8000f, segmentSpeeds.first(), 5f)
        segmentSpeeds.zipWithNext().forEach { (before, after) ->
            assertTrue("a fast fling should keep slowing down", after <= before + 1f)
        }
        assertTrue("last frame should arrive almost at rest", segmentSpeeds.last() < 5f)
        assertEquals(1f, motion.progress(1f), 0f)
    }

    @Test
    fun extremeFlingAndVeryShortTravelCannotOvershootOrBounce() {
        for (style in EpubDirectPageAnimationPolicy.Style.entries) {
            for (start in listOf(0.001f, 0.01f, 0.37f, 0.99f, 0.999f)) {
                for (target in listOf(0f, 1f)) {
                    for (velocity in listOf(-8000f, 0f, 8000f, 100000f, Float.NaN, Float.POSITIVE_INFINITY)) {
                        val motion = EpubDirectPageAnimationPolicy.settleMotion(style, 300, start, target, 320, velocity)
                        var previous = start
                        for (step in 0..120) {
                            val progress = motion.progress(step / 120f)
                            assertTrue(progress.isFinite())
                            assertTrue(progress in minOf(start, target)..maxOf(start, target))
                            assertTrue(if (target > start) progress >= previous else progress <= previous)
                            previous = progress
                        }
                    }
                }
            }
        }
    }

    @Test
    fun simulationTailIsBoundedWhileAllFourSpeedPreferencesRemainDistinct() {
        val durations = PageAnimationSpeed.entries.map { speed ->
            val motion = EpubDirectPageAnimationPolicy.settleMotion(
                EpubDirectPageAnimationPolicy.Style.Simulation, speed.durationMillis, 0.1f, 1f, 1080
            )
            assertTrue(motion.durationMillis <= speed.durationMillis * 1.2f)
            motion.durationMillis
        }
        durations.zipWithNext().forEach { (faster, slower) -> assertTrue(faster < slower) }
    }

    @Test
    fun invalidReleaseVelocityUsesTheSameGentleMotionAsAStationaryFinger() {
        val style = EpubDirectPageAnimationPolicy.Style.LinkedCover
        val stationary = EpubDirectPageAnimationPolicy.settleMotion(style, 300, 0.37f, 1f, 1000)
        for (velocity in listOf(-8000f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val motion = EpubDirectPageAnimationPolicy.settleMotion(style, 300, 0.37f, 1f, 1000, velocity)
            assertEquals(stationary.durationMillis, motion.durationMillis)
            for (step in 0..100) assertEquals(stationary.progress(step / 100f), motion.progress(step / 100f), 0f)
        }
    }

    @Test
    fun rtlReversesTheVisualPageDirection() {
        assertEquals(1, EpubDirectPageAnimationPolicy.visualDirection(1, rtl = false))
        assertEquals(-1, EpubDirectPageAnimationPolicy.visualDirection(-1, rtl = false))
        assertEquals(-1, EpubDirectPageAnimationPolicy.visualDirection(1, rtl = true))
        assertEquals(1, EpubDirectPageAnimationPolicy.visualDirection(-1, rtl = true))
    }

    @Test
    fun logicalTurnActionDoesNotChangeWithLayoutDirection() {
        assertEquals(
            EpubDirectPageAnimationPolicy.TurnAction.Next,
            EpubDirectPageAnimationPolicy.turnAction(1)
        )
        assertEquals(
            EpubDirectPageAnimationPolicy.TurnAction.Previous,
            EpubDirectPageAnimationPolicy.turnAction(-1)
        )
    }

    @Test
    fun coverUsesDifferentLayerMotionForNextAndPrevious() {
        val next = EpubDirectPageAnimationPolicy.frame(
            EpubDirectPageAnimationPolicy.Style.Cover,
            EpubDirectPageAnimationPolicy.TurnAction.Next,
            visualDirection = 1,
            progress = 0.25f,
            viewportWidth = 1000
        )
        assertEquals(-250f, next.sourceTranslationX, 0.001f)
        assertEquals(0f, next.targetTranslationX, 0.001f)
        assertEquals(750f, next.edgeX ?: -1f, 0.001f)
        assertEquals(1, next.shadowDirection)

        val previous = EpubDirectPageAnimationPolicy.frame(
            EpubDirectPageAnimationPolicy.Style.Cover,
            EpubDirectPageAnimationPolicy.TurnAction.Previous,
            visualDirection = -1,
            progress = 0.25f,
            viewportWidth = 1000
        )
        assertEquals(0f, previous.sourceTranslationX, 0.001f)
        assertEquals(-750f, previous.targetTranslationX, 0.001f)
        assertEquals(250f, previous.sourceClipLeft, 0.001f)
        assertEquals(1000f, previous.sourceClipRight, 0.001f)
        assertEquals(1, previous.shadowDirection)
    }

    @Test
    fun rtlPreviousCoverMirrorsTheEdgeWithoutChangingTheAction() {
        val previous = EpubDirectPageAnimationPolicy.frame(
            EpubDirectPageAnimationPolicy.Style.Cover,
            EpubDirectPageAnimationPolicy.TurnAction.Previous,
            visualDirection = 1,
            progress = 0.25f,
            viewportWidth = 1000
        )
        assertEquals(750f, previous.targetTranslationX, 0.001f)
        assertEquals(0f, previous.sourceClipLeft, 0.001f)
        assertEquals(750f, previous.sourceClipRight, 0.001f)
        assertEquals(-1, previous.shadowDirection)
    }

    @Test
    fun linkedCoverKeepsTheReferenceParallaxAndMasks() {
        val next = EpubDirectPageAnimationPolicy.frame(
            EpubDirectPageAnimationPolicy.Style.LinkedCover,
            EpubDirectPageAnimationPolicy.TurnAction.Next,
            visualDirection = 1,
            progress = 0.5f,
            viewportWidth = 1000
        )
        assertEquals(-500f, next.sourceTranslationX, 0.001f)
        assertEquals(100f, next.targetTranslationX, 0.001f)
        assertEquals(51, next.targetMaskAlpha)

        val previous = EpubDirectPageAnimationPolicy.frame(
            EpubDirectPageAnimationPolicy.Style.LinkedCover,
            EpubDirectPageAnimationPolicy.TurnAction.Previous,
            visualDirection = -1,
            progress = 0.5f,
            viewportWidth = 1000
        )
        assertEquals(100f, previous.sourceTranslationX, 0.001f)
        assertEquals(-500f, previous.targetTranslationX, 0.001f)
        assertEquals(500f, previous.sourceClipLeft, 0.001f)
        assertEquals(38, previous.sourceMaskAlpha)
    }

    @Test
    fun onlyStylesWithATranslatedTargetPrepareACompositedLayer() {
        assertFalse(
            EpubDirectPageAnimationPolicy.requiresMovingLiveTarget(
                EpubDirectPageAnimationPolicy.Style.Simulation,
                EpubDirectPageAnimationPolicy.TurnAction.Previous
            )
        )
        assertFalse(
            EpubDirectPageAnimationPolicy.requiresMovingLiveTarget(
                EpubDirectPageAnimationPolicy.Style.Cover,
                EpubDirectPageAnimationPolicy.TurnAction.Next
            )
        )
        assertTrue(
            EpubDirectPageAnimationPolicy.requiresMovingLiveTarget(
                EpubDirectPageAnimationPolicy.Style.Cover,
                EpubDirectPageAnimationPolicy.TurnAction.Previous
            )
        )
        assertTrue(
            EpubDirectPageAnimationPolicy.requiresMovingLiveTarget(
                EpubDirectPageAnimationPolicy.Style.LinkedCover,
                EpubDirectPageAnimationPolicy.TurnAction.Next
            )
        )
    }

    @Test
    fun overlayAndLiveTargetResolveTheSameTranslationForEveryDirection() {
        EpubDirectPageAnimationPolicy.Style.entries.forEach { style ->
            EpubDirectPageAnimationPolicy.TurnAction.entries.forEach { action ->
                listOf(-1, 1).forEach { direction ->
                    listOf(0f, 0.25f, 0.5f, 1f).forEach { progress ->
                        val frame = EpubDirectPageAnimationPolicy.frame(
                            style,
                            action,
                            direction,
                            progress,
                            viewportWidth = 1080
                        )
                        assertEquals(
                            frame.targetTranslationX,
                            EpubDirectPageAnimationPolicy.liveTargetTranslationX(
                                style,
                                action,
                                direction,
                                progress,
                                viewportWidth = 1080
                            ),
                            0.001f
                        )
                    }
                }
            }
        }
    }

    @Test
    fun simulationPreviousRequiresThePreviousPageBitmapToFold() {
        assertFalse(
            EpubDirectPageAnimationPolicy.hasRequiredBitmapFrames(
                EpubDirectPageAnimationPolicy.Style.Simulation,
                EpubDirectPageAnimationPolicy.TurnAction.Previous,
                hasTargetBitmap = false
            )
        )
        assertTrue(
            EpubDirectPageAnimationPolicy.hasRequiredBitmapFrames(
                EpubDirectPageAnimationPolicy.Style.Simulation,
                EpubDirectPageAnimationPolicy.TurnAction.Previous,
                hasTargetBitmap = true
            )
        )
        assertTrue(
            EpubDirectPageAnimationPolicy.hasRequiredBitmapFrames(
                EpubDirectPageAnimationPolicy.Style.Simulation,
                EpubDirectPageAnimationPolicy.TurnAction.Next,
                hasTargetBitmap = false
            )
        )
        assertTrue(
            EpubDirectPageAnimationPolicy.hasRequiredBitmapFrames(
                EpubDirectPageAnimationPolicy.Style.Cover,
                EpubDirectPageAnimationPolicy.TurnAction.Previous,
                hasTargetBitmap = false
            )
        )
    }

    @Test
    fun repeatedAnimatedTurnIsConsumedUntilCommittedFrameFinishes() {
        assertTrue(EpubDirectPageAnimationPolicy.consumesRepeatedTurn(true))
        assertFalse(EpubDirectPageAnimationPolicy.consumesRepeatedTurn(false))
    }
}
