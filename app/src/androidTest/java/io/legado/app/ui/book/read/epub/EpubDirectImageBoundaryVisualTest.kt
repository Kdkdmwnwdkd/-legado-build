package io.legado.app.ui.book.read.epub

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.PageAnim
import io.legado.app.data.entities.Book
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.epubcore.direct.EpubDirectChapter
import io.legado.app.model.localBook.epubcore.direct.EpubDirectDocumentBuilder
import io.legado.app.model.localBook.epubcore.direct.EpubDirectResource
import io.legado.app.model.localBook.epubcore.direct.EpubDirectSession
import io.legado.app.model.localBook.epubcore.direct.TextReaderDocument
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Android input, resource callbacks and window-compositor pixels, not a DOM-only probe. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class EpubDirectImageBoundaryVisualTest {
    @get:Rule val testName = TestName()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var activity: EpubDirectWebResourceTestActivity? = null
    private var layer: EpubDirectWebLayer? = null
    private lateinit var config: EpubCoreLayoutConfig
    private lateinit var chapters: List<EpubDirectChapter>
    private lateinit var session: EpubDirectSession
    private var savedBook: Book? = null
    private var savedPageAnim: Int? = null
    private val gates = List(3) { CountDownLatch(1) }
    private val errors = Collections.synchronizedList(mutableListOf<String>())
    private val trace = JSONArray()
    private var activeGesture: Gesture? = null

    @After
    fun tearDown() {
        runCatching { activeGesture?.send(MotionEvent.ACTION_CANCEL, 0.18f) }
        gates.forEach(CountDownLatch::countDown)
        runCatching { record("teardown", state()) }
        runCatching { File(evidenceDirectory(), "events.json").writeText(trace.toString(2)) }
        onMain {
            layer?.destroy()
            activity?.finish()
            if (savedPageAnim != null) {
                ReadBook.book = savedBook
                ReadBookConfig.pageAnim = savedPageAnim!!
            }
        }
        layer = null
        activity = null
    }

    @Test
    fun cancelledTransparentBoundaryPreviewRestoresBothViewsAndWindowPixels() {
        gates.forEach(CountDownLatch::countDown)
        startReader()
        val original = currentView()
        val target = awaitPreparedChapter(1)
        val originalTargetState = onMain { viewState(target) }
        assertTrue("fixture did not use a transparent native surface", onMain {
            (original.background as? ColorDrawable)?.color == Color.TRANSPARENT
        })
        val before = capture("before-preview")
        val beforePosition = onMain { layer!!.position!! }

        val gesture = beginBoundaryDrag()
        await("live target was not revealed") {
            onMain {
                val live = readField(layer!!, "livePageAnimationTarget")
                live != null && readField(live, "view") === target && readField(live, "revealed") == true
            }
        }
        onMain {
            assertSame(original, readField(layer!!, "currentWebView"))
            // An alpha-zero source may remain VISIBLE to keep receiving this gesture.
            assertTrue("old source was still composited below transparent target",
                original.visibility != View.VISIBLE || original.alpha == 0f)
            assertEquals(1f, target.alpha, 0.0001f)
        }
        capture("live-preview").recycle()
        gesture.send(MotionEvent.ACTION_CANCEL, 0.18f)
        awaitStable(0, beforePosition.pageIndex)
        onMain {
            assertSame(original, readField(layer!!, "currentWebView"))
            assertSame(layer, target.parent)
            assertEquals("cancelled target visibility", originalTargetState.visibility, target.visibility)
            assertEquals("cancelled target alpha", originalTargetState.alpha, target.alpha, 0.0001f)
            assertEquals("cancelled target layer", originalTargetState.layerType, target.layerType)
            assertEquals("cancelled target background", originalTargetState.background, viewState(target).background)
            assertEquals(0f, target.translationX, 0.01f)
            assertEquals(1f, original.alpha, 0.0001f)
        }
        val after = capture("after-cancel")
        try {
            assertSameComposedPage(before, after, "cancelling a chapter preview changed the source page")
        } finally {
            before.recycle()
            after.recycle()
        }
        assertTrue("cancel navigated chapters", onMain { layer!!.position!!.chapterIndex == 0 })
    }

    @Test
    fun lateImagesAcrossChapterBoundaryRefreshNativeFrameBeforeNextPage() {
        gates[2].countDown()
        startReader()
        assertEquals("pending", imageState(currentView()))
        val initial = state()
        assertTrue("fixture must open the chapter's last page", onMain {
            layer!!.position!!.let { it.pageIndex == it.pageCount - 1 }
        })
        val target = awaitPreparedChapter(1)
        val gesture = beginBoundaryDrag()
        await("warm boundary preview did not reveal the incoming view") {
            onMain {
                val live = readField(layer!!, "livePageAnimationTarget")
                live != null && readField(live, "view") === target && readField(live, "revealed") == true
            }
        }
        val alphas = mutableListOf<Float>()
        val alphaObserver = ViewTreeObserver.OnPreDrawListener {
            val live = readField(layer!!, "livePageAnimationTarget")
            if (readField(layer!!, "currentWebView") === target ||
                live != null && readField(live, "view") === target && readField(live, "revealed") == true
            ) alphas.add(target.alpha)
            true
        }
        onMain { layer!!.viewTreeObserver.addOnPreDrawListener(alphaObserver) }
        try {
            capture("warm-preview-before-commit").recycle()
            gesture.send(MotionEvent.ACTION_UP, 0.04f)
            awaitStable(1, 0)
        } finally {
            onMain { layer!!.viewTreeObserver.removeOnPreDrawListener(alphaObserver) }
        }
        assertTrue("no real target frames were observed", alphas.isNotEmpty())
        assertTrue("a revealed warm target was faded during promotion: $alphas", alphas.all { it >= 0.99f })
        record("warm-promotion-frame-alpha", JSONObject().put("samples", JSONArray(alphas)))
        // A visible text page must be usable while a source image is still queued.
        assertEquals("pending", imageState(currentView()))
        val beforeImage = state()
        record("new-chapter-before-images", beforeImage)
        val firstTarget = currentView()
        gates[0].countDown()
        gates[1].countDown()
        await("visible delayed image did not decode") { imageState(firstTarget) == "ready" }
        awaitStable(1)
        val afterImage = state()
        assertTrue("image completion did not advance the document visual revision",
            afterImage.getJSONObject("metrics").getLong("visualRevision") >
                beforeImage.getJSONObject("metrics").getLong("visualRevision"))
        assertEquals("an old chapter completion replaced the new document", 1,
            afterImage.getInt("chapter"))
        assertTrue("fixture needs a following page", afterImage.getInt("pageCount") > 2)
        if (afterImage.getInt("page") != 0) {
            onMain { assertTrue(layer!!.setPage(0, animate = false)) }
            awaitStable(1, 0)
        }
        capture("chapter-after-late-image").recycle()
        onMain {
            val result = layer!!.nextPage(animate = true)
            assertTrue("next-page request rejected: $result",
                result == EpubPageTurnResult.MovedWithinChapter || result == EpubPageTurnResult.Queued)
        }
        awaitStable(1, 1)
        val animated = capture("next-page-animated")
        onMain { assertTrue(layer!!.setPage(0, animate = false)) }
        awaitStable(1, 0)
        onMain { assertTrue(layer!!.setPage(1, animate = false)) }
        awaitStable(1, 1)
        val direct = capture("next-page-without-animation")
        try {
            assertSameComposedPage(direct, animated, "the page after an image chapter handoff was faded or stale")
        } finally {
            animated.recycle()
            direct.recycle()
        }
        record("old-chapter-before-handoff", initial)
        record("new-chapter-after-images", afterImage)
    }

    private fun startReader() {
        onMain {
            savedBook = ReadBook.book
            savedPageAnim = ReadBookConfig.pageAnim
            ReadBook.book = null
            ReadBookConfig.pageAnim = PageAnim.coverPageAnim
        }
        val intent = Intent(ApplicationProvider.getApplicationContext(),
            EpubDirectWebResourceTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        @Suppress("DEPRECATION")
        activity = instrumentation.startActivitySync(intent) as EpubDirectWebResourceTestActivity
        val density = activity!!.resources.displayMetrics.density
        config = EpubCoreLayoutConfig(
            pageWidthPx = WIDTH, pageHeightPx = HEIGHT,
            readerPaddingLeftPx = 12, readerPaddingRightPx = 12,
            readerPaddingTopPx = 12, readerPaddingBottomPx = 12,
            lineHeightPx = 28f, paragraphSpacingPx = 8f,
            textPaint = TextPaint().apply { textSize = 18f; color = Color.BLACK },
            backgroundColor = BACKGROUND, readerBackgroundImage = true
        )
        chapters = (0..2).map { index ->
            val image = """<img src='https://fixture.invalid/image-$index.png,{"width":"96px"}'>"""
            val prose = (1..36).joinToString("\n") { paragraph ->
                "Chapter $index paragraph $paragraph. The reader must preserve this visible text. ".repeat(3)
            }
            val content = TextReaderDocument.prepare("chapter-$index", if (index == 0) "$prose\n$image" else "$image\n$prose")
            EpubDirectDocumentBuilder.build(
                chapterIndex = index, href = "text/$index/chapter.html", title = content.title,
                sourceHtml = content.html(includeTitle = false, sourceActionsEnabled = false, deferredImages = true) {
                    "https://${EpubDirectSession.HOST}/text-image/$index/fixture/image-0"
                }, config = config, density = density, resourceHost = EpubDirectSession.HOST
            )
        }
        val images = listOf(Color.rgb(190, 35, 35), Color.rgb(35, 65, 190), Color.rgb(35, 150, 70)).map(::png)
        session = EpubDirectSession(
            bookUrl = "stability-visual-fixture", chapterLoader = { index, _ -> chapters[index] },
            resourceLoader = { path, _ ->
                val normalized = path.substringBefore('?').trimStart('/')
                val index = normalized.removePrefix("text-image/").substringBefore('/').toIntOrNull()
                if (!normalized.startsWith("text-image/") || index == null || index !in 0..2) null else {
                    val selected = checkNotNull(index)
                    if (normalized.endsWith("/state")) {
                        val json = if (gates[selected].count > 0L) """{"state":"pending","queued":true}"""
                            else """{"state":"ready","scale":1,"bubble":false}"""
                        resource("application/json", json.toByteArray())
                    } else resource("image/png", images[selected])
                }
            }, linkResolver = { _, _ -> null },
            readableChapterResolver = { index, _ -> index.takeIf { it in 0..2 } },
            adjacentChapterResolver = { index, direction -> (index + direction).takeIf { it in 0..2 } },
            closeAction = {}
        )
        onMain {
            layer = EpubDirectWebLayer(activity!!).apply {
                setListener(object : EpubDirectWebLayer.Listener {
                    override fun onPageBoundary(direction: Int) {
                        val current = layer!!.position ?: return
                        val next = current.chapterIndex + direction
                        if (next !in chapters.indices) return
                        layer!!.showChapter(chapters[next], config, initialPageIndex = 0,
                            openAtEnd = direction < 0, boundaryTransition = true)
                    }
                    override fun onError(message: String, throwable: Throwable?) {
                        errors.add("$message ${throwable?.javaClass?.name.orEmpty()}")
                    }
                })
                bindSession(session)
            }
            activity!!.setContentView(FrameLayout(activity!!).apply {
                setBackgroundColor(BACKGROUND)
                addView(layer, FrameLayout.LayoutParams(WIDTH, HEIGHT))
            })
            layer!!.showChapter(chapters[0], config, initialPageIndex = 0, openAtEnd = true)
        }
        awaitStable(0)
        assertTrue("fixture has no predecessor page", onMain { layer!!.position!!.pageCount > 2 })
        onMain {
            assertTrue("device's selected budget has no chapter preload slot", layer!!.preloadCapacity > 0)
            layer!!.preloadChapter(session, chapters[1], config)
        }
    }

    private fun awaitPreparedChapter(index: Int): WebView {
        var result: WebView? = null
        await("chapter $index was not prepared for a real boundary preview") {
            result = onMain {
                descendants(layer!!).filterIsInstance<WebView>().firstOrNull { view ->
                    (readField(view, "preparedChapter") as? EpubDirectChapter)?.chapterIndex == index &&
                        readField(view, "preloadReady") == true
                }
            }
            result != null
        }
        return checkNotNull(result)
    }

    private fun awaitStable(chapter: Int, page: Int? = null) {
        var latest = JSONObject()
        await("chapter $chapter page $page did not reach a composed, synchronized state", diagnostic = { latest.toString() }) {
            if (!onMain { layer?.hasVisibleDocument == true }) return@await false
            latest = state()
            val metrics = latest.getJSONObject("metrics")
            latest.getInt("chapter") == chapter && (page == null || latest.getInt("page") == page) &&
                !latest.getBoolean("busy") && !latest.getBoolean("needsMetrics") &&
                !metrics.optBoolean("layoutPending", true) && metrics.optBoolean("ready", false) &&
                latest.getLong("nativeLayout") == metrics.optLong("layoutRevision", -2) &&
                latest.getLong("nativeVisual") == metrics.optLong("visualRevision", -2) &&
                latest.getInt("page") == metrics.optInt("pageIndex", -2) &&
                latest.getInt("pageCount") == metrics.optInt("pageCount", -2) &&
                latest.optLong("cachedLayout", -3) == latest.getLong("nativeLayout") &&
                latest.optLong("cachedVisual", -3) == latest.getLong("nativeVisual") &&
                latest.optInt("cachedPage", -3) == latest.getInt("page") &&
                latest.optInt("cachedChapter", -3) == latest.getInt("chapter") &&
                latest.optLong("cachedToken", -3) == latest.getLong("token") &&
                latest.optLong("cachedGeneration", -3) == latest.getLong("generation") &&
                latest.getDouble("alpha") == 1.0
        }
        record("stable", latest)
    }

    private fun state(): JSONObject {
        val view = currentView()
        val metrics = evaluate(view, "JSON.stringify(window.__legadoEpub ? window.__legadoEpub.metrics(true) : {})")
        return onMain {
            val position = layer!!.position
            val renderState = readField(view, "renderState") as EpubRuntimeRenderState
            val entry = readField(readField(layer!!, "committedPageSnapshots")!!, "entry")
            val key = entry?.let { readField(it, "key") } as? EpubCommittedPageSnapshotKey
            JSONObject().apply {
                put("chapter", position?.chapterIndex ?: -1)
                put("page", position?.pageIndex ?: -1)
                put("pageCount", position?.pageCount ?: -1)
                put("nativeLayout", readField(layer!!, "currentLayoutRevision"))
                put("nativeVisual", renderState.visualRevision)
                put("token", renderState.token)
                put("generation", readField(layer!!, "generation"))
                put("needsMetrics", renderState.needsMetrics)
                put("cachedLayout", key?.layoutRevision ?: -1)
                put("cachedVisual", key?.visualRevision ?: -1)
                put("cachedPage", key?.pageIndex ?: -1)
                put("cachedChapter", key?.chapterIndex ?: -1)
                put("cachedToken", key?.token ?: -1)
                put("cachedGeneration", key?.generation ?: -1)
                put("alpha", view.alpha.toDouble())
                put("busy", listOf("pageAnimationOverlay", "pageAnimator", "interactivePageTurn", "pendingActivationView",
                    "pendingChapterTurn", "livePageAnimationTarget", "foregroundRevealRunnable", "pageHandoffRequest",
                    "runtimeMetricsSyncRunnable", "runtimeMetricsSyncTimeout").any { readField(layer!!, it) != null } ||
                    readField(layer!!, "runtimeMetricsSyncInFlight") == true)
                put("metrics", JSONObject(metrics.ifBlank { "{}" }))
            }
        }
    }

    private fun imageState(view: WebView): String = evaluate(view,
        "String((document.querySelector('img[data-legado-image-resource]')||{}).getAttribute ? " +
            "document.querySelector('img[data-legado-image-resource]').getAttribute('data-legado-image-state') : 'missing')")

    private fun evaluate(view: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var value = ""
        onMain {
            view.evaluateJavascript(script) { raw ->
                value = JSONArray("[$raw]").optString(0)
                latch.countDown()
            }
        }
        assertTrue("Android JavaScript callback timed out", latch.await(10, TimeUnit.SECONDS))
        return value
    }

    private fun currentView(): WebView = onMain { readField(checkNotNull(layer), "currentWebView") as WebView }

    private inner class Gesture(private val downTime: Long, private val origin: IntArray) {
        fun send(action: Int, horizontal: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                origin[0] + WIDTH * horizontal, origin[1] + HEIGHT * 0.4f, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) activeGesture = null
        }
    }

    private fun beginBoundaryDrag(): Gesture {
        val origin = IntArray(2)
        onMain { layer!!.getLocationOnScreen(origin) }
        return Gesture(SystemClock.uptimeMillis(), origin).also {
            activeGesture = it
            it.send(MotionEvent.ACTION_DOWN, 0.86f)
            it.send(MotionEvent.ACTION_MOVE, 0.58f)
            it.send(MotionEvent.ACTION_MOVE, 0.18f)
        }
    }

    private fun capture(label: String): Bitmap {
        val drawn = CountDownLatch(1)
        onMain { layer!!.postOnAnimation { layer!!.postOnAnimation { drawn.countDown() } } }
        assertTrue("two window frames did not arrive", drawn.await(10, TimeUnit.SECONDS))
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var result = -1
        onMain {
            val origin = IntArray(2)
            layer!!.getLocationInWindow(origin)
            PixelCopy.request(activity!!.window, Rect(origin[0], origin[1], origin[0] + WIDTH, origin[1] + HEIGHT),
                bitmap, { status -> result = status; copied.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue("window PixelCopy timed out", copied.await(10, TimeUnit.SECONDS))
        assertEquals("window PixelCopy failed", PixelCopy.SUCCESS, result)
        File(evidenceDirectory(), "$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        record("window-capture", JSONObject().put("label", label).put("pixelCopy", result))
        return bitmap
    }

    private fun assertSameComposedPage(expected: Bitmap, actual: Bitmap, reason: String) {
        var totalError = 0L
        var expectedInk = 0L
        var actualInk = 0L
        var darkPixels = 0
        val firstPixels = IntArray(WIDTH * HEIGHT)
        val secondPixels = IntArray(WIDTH * HEIGHT)
        expected.getPixels(firstPixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        actual.getPixels(secondPixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        for (index in firstPixels.indices) {
            val a = firstPixels[index]
            val b = secondPixels[index]
            totalError += abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
            val first = (Color.red(a) + Color.green(a) + Color.blue(a)) / 3
            val second = (Color.red(b) + Color.green(b) + Color.blue(b)) / 3
            expectedInk += (246 - first).coerceAtLeast(0)
            actualInk += (246 - second).coerceAtLeast(0)
            if (first < 128) darkPixels++
        }
        assertTrue("fixture snapshot contains no useful ink", darkPixels > 100 && expectedInk > 0)
        val meanError = totalError.toDouble() / (WIDTH * HEIGHT * 3)
        val contrastRatio = actualInk.toDouble() / expectedInk
        record("pixel-comparison", JSONObject().put("meanRgbError", meanError).put("inkRatio", contrastRatio))
        assertTrue("$reason; meanRgbError=$meanError", meanError < 2.0)
        assertTrue("$reason; inkRatio=$contrastRatio", contrastRatio in 0.97..1.03)
    }

    private fun await(message: String, diagnostic: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 60_000
        do {
            assertTrue("production WebLayer error: $errors", errors.isEmpty())
            if (condition()) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("$message; ${diagnostic()}; errors=$errors")
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun readField(owner: Any, name: String): Any? {
        var type: Class<*>? = owner.javaClass
        while (type != null) {
            val currentType = type
            val field = runCatching { currentType.getDeclaredField(name) }.getOrNull()
            if (field != null) { field.isAccessible = true; return field.get(owner) }
            type = currentType.superclass
        }
        error("Missing observed field ${owner.javaClass.name}.$name")
    }

    private data class ViewState(val visibility: Int, val alpha: Float, val layerType: Int, val background: Int?)
    private fun viewState(view: View) = ViewState(view.visibility, view.alpha, view.layerType,
        (view.background as? ColorDrawable)?.color)

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }

    private fun record(event: String, value: JSONObject) {
        trace.put(JSONObject().put("event", event).put("uptimeMs", SystemClock.uptimeMillis()).put("value", value))
    }

    private fun evidenceDirectory(): File {
        val runId = InstrumentationRegistry.getArguments().getString("stabilityRunId", "manual")
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(instrumentation.targetContext.getExternalFilesDir(null), "stability-visual/$runId/${testName.methodName}")
            .apply { mkdirs() }
    }

    private fun resource(mime: String, bytes: ByteArray) = EpubDirectResource(mime,
        if (mime == "application/json") "utf-8" else null, 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(bytes))

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 120, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 480
        val BACKGROUND = Color.rgb(246, 246, 246)
    }
}
