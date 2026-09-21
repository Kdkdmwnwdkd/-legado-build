package io.legado.app.ui.book.read.epub

import android.graphics.Color
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EpubVirtualDisplayFrameHostTest {

    private var host: EpubVirtualDisplayFrameHost? = null

    @After
    fun tearDown() {
        val activeHost = host ?: return
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            activeHost.close()
        }
        host = null
    }

    @Test
    fun capturesPixelsFromIsolatedHardwareDisplay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val latch = CountDownLatch(1)
        var result: Result<android.graphics.Bitmap>? = null
        instrumentation.runOnMainSync {
            val frameHost = EpubVirtualDisplayFrameHost(
                context = context,
                width = FRAME_WIDTH,
                height = FRAME_HEIGHT,
                densityDpi = context.resources.displayMetrics.densityDpi
            )
            host = frameHost
            frameHost.setContent(
                View(frameHost.renderContext).apply {
                    setBackgroundColor(EXPECTED_COLOR)
                }
            )
            frameHost.capture { captured ->
                result = captured
                latch.countDown()
            }
        }

        assertTrue("virtual frame capture timed out", latch.await(5, TimeUnit.SECONDS))
        val bitmap = assertNotNull(result).let { checkNotNull(result).getOrThrow() }
        try {
            assertEquals(FRAME_WIDTH, bitmap.width)
            assertEquals(FRAME_HEIGHT, bitmap.height)
            assertEquals(EXPECTED_COLOR, bitmap.getPixel(FRAME_WIDTH / 2, FRAME_HEIGHT / 2))
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val FRAME_WIDTH = 320
        const val FRAME_HEIGHT = 480
        val EXPECTED_COLOR: Int = Color.rgb(37, 91, 143)
    }
}
