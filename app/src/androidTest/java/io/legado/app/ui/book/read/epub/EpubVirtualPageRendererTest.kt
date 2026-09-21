package io.legado.app.ui.book.read.epub

import android.graphics.Bitmap
import android.graphics.Color
import android.text.TextPaint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.model.localBook.epubcore.direct.EpubDirectChapter
import io.legado.app.model.localBook.epubcore.direct.EpubDirectLayoutMode
import io.legado.app.model.localBook.epubcore.direct.EpubDirectResource
import io.legado.app.model.localBook.epubcore.direct.EpubDirectSession
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class EpubVirtualPageRendererTest {

    private var renderer: EpubVirtualPageRenderer? = null
    private var session: EpubDirectSession? = null

    @After
    fun tearDown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            renderer?.close()
            renderer = null
            session?.close()
            session = null
        }
    }

    @Test
    fun capturesSecondPageWithPublisherBackgroundResource() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val background = checkerboardPng()
        val chapter = testChapter()
        val config = EpubCoreLayoutConfig(
            pageWidthPx = FRAME_WIDTH,
            pageHeightPx = FRAME_HEIGHT,
            readerPaddingLeftPx = 18,
            readerPaddingTopPx = 20,
            readerPaddingRightPx = 18,
            readerPaddingBottomPx = 20,
            paragraphSpacingPx = 12f,
            textPaint = TextPaint().apply {
                color = Color.rgb(24, 31, 38)
                textSize = 24f
            },
            lineHeightPx = 24f * 1.25f,
            backgroundColor = Color.WHITE
        )
        val directSession = EpubDirectSession(
            bookUrl = "virtual-render-test",
            chapterLoader = { _, _ -> chapter },
            resourceLoader = { path, _ ->
                if (path == BACKGROUND_PATH) {
                    EpubDirectResource(
                        mimeType = "image/png",
                        encoding = null,
                        statusCode = 200,
                        reasonPhrase = "OK",
                        headers = mapOf("Content-Length" to background.size.toString()),
                        stream = ByteArrayInputStream(background)
                    )
                } else {
                    null
                }
            },
            linkResolver = { _, _ -> null },
            closeAction = {}
        )
        session = directSession
        val latch = CountDownLatch(1)
        var result: Result<EpubRenderedPageFrame>? = null
        instrumentation.runOnMainSync {
            val pageRenderer = EpubVirtualPageRenderer(
                context = context,
                viewportWidth = FRAME_WIDTH,
                viewportHeight = FRAME_HEIGHT,
                densityDpi = context.resources.displayMetrics.densityDpi
            )
            renderer = pageRenderer
            pageRenderer.render(
                session = directSession,
                chapter = chapter,
                config = config,
                pageIndex = TARGET_PAGE,
                timeoutMillis = 10_000L
            ) { rendered ->
                result = rendered
                latch.countDown()
            }
        }

        assertTrue("EPUB virtual page render timed out", latch.await(15, TimeUnit.SECONDS))
        val frame = assertNotNull(result).let { checkNotNull(result).getOrThrow() }
        try {
            assertEquals(TARGET_PAGE, frame.pageIndex)
            assertTrue("fixture must span multiple pages", frame.pageCount > TARGET_PAGE)
            assertEquals(FRAME_WIDTH, frame.bitmap.width)
            assertEquals(FRAME_HEIGHT, frame.bitmap.height)
            assertTrue(
                "publisher background pixels are absent from captured target page",
                countBackgroundPixels(frame.bitmap) > MIN_BACKGROUND_PIXELS
            )
            val originalStamp = checkNotNull(frame.renderStamp)
            // Forward, backward, repeated-page and chapter-end requests must reuse
            // the committed document while still producing the requested pixels.
            for ((requested, atEnd) in listOf(
                TARGET_PAGE + 1 to false, 0 to false, 0 to false, 0 to true
            )) {
                val nextLatch = CountDownLatch(1)
                var nextResult: Result<EpubRenderedPageFrame>? = null
                instrumentation.runOnMainSync {
                    checkNotNull(renderer).render(
                        session = directSession, chapter = chapter, config = config,
                        pageIndex = requested, openAtEnd = atEnd, timeoutMillis = 10_000L
                    ) {
                        nextResult = it
                        nextLatch.countDown()
                    }
                }
                assertTrue("warm EPUB page render timed out", nextLatch.await(15, TimeUnit.SECONDS))
                val next = checkNotNull(nextResult).getOrThrow()
                try {
                    assertEquals(if (atEnd) frame.pageCount - 1 else requested, next.pageIndex)
                    assertEquals(frame.pageCount, next.pageCount)
                    val stamp = checkNotNull(next.renderStamp)
                    assertEquals("warm document was reactivated", originalStamp.token, stamp.token)
                    assertEquals("warm WebView was replaced", originalStamp.viewIdentity, stamp.viewIdentity)
                    assertTrue(countBackgroundPixels(next.bitmap) > MIN_BACKGROUND_PIXELS)
                } finally {
                    next.close()
                }
            }
        } finally {
            frame.close()
        }
    }

    private fun testChapter(): EpubDirectChapter {
        val paragraphs = (1..100).joinToString(separator = "") { index ->
            "<p>Hardware rendered EPUB page $index keeps publisher backgrounds and text.</p>"
        }
        val html = """
            <!doctype html>
            <html>
              <head>
                <meta name="viewport" content="width=device-width,initial-scale=1" />
                <style>
                  html,body {
                    margin: 0;
                    color: rgb(24,31,38);
                    background-color: rgb(255,255,255);
                    background-image: url('${EpubDirectSession.baseUrl(BACKGROUND_PATH)}');
                    background-repeat: repeat;
                    background-size: 64px 64px;
                  }
                  p { margin: 0 0 12px 0; }
                </style>
              </head>
              <body>$paragraphs</body>
            </html>
        """.trimIndent()
        return EpubDirectChapter(
            chapterIndex = 0,
            href = "chapter.xhtml",
            title = "Virtual render fixture",
            baseUrl = EpubDirectSession.baseUrl("chapter.xhtml"),
            html = html,
            plainText = paragraphs.replace(Regex("<[^>]+>"), " "),
            startFragmentId = null,
            endFragmentId = null,
            layoutMode = EpubDirectLayoutMode.REFLOWABLE,
            viewportWidth = null,
            viewportHeight = null,
            publisherOrientation = "auto",
            publisherSpread = "auto",
            publisherFullscreen = false,
            fullPageArtwork = false,
            implicitSinglePage = false,
            duokanGallery = false,
            scripted = false,
            pageProgressionDirection = "ltr"
        )
    }

    private fun checkerboardPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                bitmap.setPixel(
                    x,
                    y,
                    if ((x / 16 + y / 16) % 2 == 0) BACKGROUND_A else BACKGROUND_B
                )
            }
        }
        return ByteArrayOutputStream().use { output ->
            try {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun countBackgroundPixels(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height step 4) {
            for (x in 0 until bitmap.width step 4) {
                val color = bitmap.getPixel(x, y)
                if (near(color, BACKGROUND_A) || near(color, BACKGROUND_B)) count++
            }
        }
        return count
    }

    private fun near(actual: Int, expected: Int): Boolean {
        return abs(Color.red(actual) - Color.red(expected)) <= COLOR_TOLERANCE &&
            abs(Color.green(actual) - Color.green(expected)) <= COLOR_TOLERANCE &&
            abs(Color.blue(actual) - Color.blue(expected)) <= COLOR_TOLERANCE
    }

    private companion object {
        const val FRAME_WIDTH = 360
        const val FRAME_HEIGHT = 640
        const val TARGET_PAGE = 1
        const val BACKGROUND_PATH = "images/page-background.png"
        const val MIN_BACKGROUND_PIXELS = 400
        const val COLOR_TOLERANCE = 18
        val BACKGROUND_A: Int = Color.rgb(31, 122, 137)
        val BACKGROUND_B: Int = Color.rgb(232, 210, 121)
    }
}
