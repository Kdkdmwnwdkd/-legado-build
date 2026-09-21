package io.legado.app.ui.book.read.epub

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.book.read.epub.EpubDirectPageAnimationPolicy.Style
import io.legado.app.ui.book.read.epub.EpubDirectPageAnimationPolicy.TurnAction
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpubDirectPageAnimationOverlayTest {

    @Test
    fun simulationEndpointsContainTheWholeUnmirroredPage() = onMain {
        for (action in TurnAction.entries) {
            for (direction in listOf(-1, 1)) {
                withOverlay(action, direction) { overlay, source, target ->
                    assertFrameMatches(source, render(overlay, 0f))
                    for (step in 1..19) render(overlay, step / 20f).recycle()
                    assertFrameMatches(target, render(overlay, 1f))
                    overlay.prepareSettle(0f)
                    assertFrameMatches(source, render(overlay, 0f))
                }
            }
        }
    }

    @Test
    fun simulationReleaseKeepsTheExactDraggedFrameInBothDirections() = onMain {
        for (action in TurnAction.entries) {
            for (direction in listOf(-1, 1)) {
                for (targetProgress in listOf(0f, 1f)) {
                    withOverlay(action, direction) { overlay, _, _ ->
                        overlay.updateDrag(0.23f)
                        val before = render(overlay, 0.31f)
                        try {
                            overlay.prepareSettle(targetProgress)
                            assertFrameMatches(before, render(overlay, 0.31f))
                        } finally {
                            before.recycle()
                        }
                    }
                }
            }
        }
    }

    @Test
    fun completedOutgoingSimulationRevealsTheVerifiedLivePage() = onMain {
        withOverlay(TurnAction.Next, 1) { overlay, _, target ->
            overlay.revealLiveTarget()
            overlay.progress = 1f
            val frame = checkNotNull(target.copy(Bitmap.Config.ARGB_8888, true))
            try {
                overlay.draw(Canvas(frame))
                assertTrue("finished fold must not cover the live page", target.sameAs(frame))
            } finally {
                frame.recycle()
            }
        }
    }

    private fun withOverlay(
        action: TurnAction,
        direction: Int,
        block: (EpubDirectPageAnimationOverlay, Bitmap, Bitmap) -> Unit
    ) {
        val source = pageBitmap(Color.rgb(240, 226, 198), Color.rgb(80, 20, 10))
        val target = pageBitmap(Color.rgb(201, 224, 238), Color.rgb(10, 30, 95))
        val overlay = EpubDirectPageAnimationOverlay(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            sourceBitmap = source,
            action = action,
            direction = direction,
            style = Style.Simulation,
            backgroundColor = Color.WHITE,
            opaqueBackground = true,
            targetBitmap = target,
            simulationStartYFraction = 0.1f
        )
        overlay.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        overlay.layout(0, 0, WIDTH, HEIGHT)
        try {
            block(overlay, source, target)
        } finally {
            overlay.release()
        }
    }

    private fun pageBitmap(background: Int, ink: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        val paint = Paint().apply { color = ink }
        // Asymmetric content detects accidentally mirrored or partially revealed pages.
        canvas.drawRect(12f, 20f, 31f, 90f, paint)
        canvas.drawRect(12f, 80f, 91f, 90f, paint)
        for (row in 0..8) canvas.drawRect(20f, 120f + row * 22f, 160f + row * 7f, 127f + row * 22f, paint)
        return bitmap
    }

    private fun render(overlay: EpubDirectPageAnimationOverlay, progress: Float): Bitmap {
        overlay.progress = progress
        return Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).also {
            overlay.draw(Canvas(it))
        }
    }

    private fun assertFrameMatches(expected: Bitmap, actual: Bitmap) {
        try {
            assertTrue("page frame differs from its snapshot", expected.sameAs(actual))
        } finally {
            actual.recycle()
        }
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                block()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
    }

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 480
    }
}
