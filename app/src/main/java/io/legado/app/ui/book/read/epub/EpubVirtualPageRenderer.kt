package io.legado.app.ui.book.read.epub

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.legado.app.model.localBook.epubcore.direct.EpubDirectChapter
import io.legado.app.model.localBook.epubcore.direct.EpubDirectPosition
import io.legado.app.model.localBook.epubcore.direct.EpubDirectSession
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import io.legado.app.model.localBook.epubcore.layout.EpubReaderChromeData
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

internal class EpubVirtualPageRenderer(
    context: Context,
    private val viewportWidth: Int,
    private val viewportHeight: Int,
    densityDpi: Int
) : Closeable {

    private data class RenderRequest(
        val id: Long,
        val chapter: EpubDirectChapter,
        val config: EpubCoreLayoutConfig,
        val requestedPageIndex: Int,
        val openAtEnd: Boolean,
        val readerChromeContentRevision: Long,
        val deadline: Long,
        val callback: (Result<EpubRenderedPageFrame>) -> Unit,
        var expectedPageIndex: Int? = null,
        var probing: Boolean = false
    )

    private val handler = Handler(Looper.getMainLooper())
    private val requestSequence = AtomicLong(0L)
    private val frameHost = EpubVirtualDisplayFrameHost(
        context = context,
        width = viewportWidth,
        height = viewportHeight,
        densityDpi = densityDpi
    )
    private val renderLayer: EpubDirectWebLayer
    private var boundSession: EpubDirectSession? = null
    private var activeRequest: RenderRequest? = null
    private var retryRunnable: Runnable? = null
    private var timeoutRunnable: Runnable? = null
    private var closed = false

    init {
        checkMainThread()
        renderLayer = try {
            EpubDirectWebLayer(
                context = frameHost.renderContext,
                frameRenderer = true
            )
        } catch (throwable: Throwable) {
            frameHost.close()
            throw throwable
        }
        frameHost.setContent(renderLayer)
        renderLayer.setListener(object : EpubDirectWebLayer.Listener {
            override fun onReady(position: EpubDirectPosition) {
                onPositionReady(position)
            }

            override fun onPositionChanged(position: EpubDirectPosition) {
                onPositionReady(position)
            }

            override fun onError(message: String, throwable: Throwable?) {
                failActive(IllegalStateException(message, throwable))
            }
        })
    }

    fun render(
        session: EpubDirectSession,
        chapter: EpubDirectChapter,
        config: EpubCoreLayoutConfig,
        pageIndex: Int,
        openAtEnd: Boolean = false,
        readerChromeData: EpubReaderChromeData = EpubReaderChromeData(),
        timeoutMillis: Long = DEFAULT_RENDER_TIMEOUT_MS,
        callback: (Result<EpubRenderedPageFrame>) -> Unit
    ) {
        checkMainThread()
        require(pageIndex >= 0)
        require(timeoutMillis > 0L)
        if (closed) {
            callback(Result.failure(IllegalStateException("EPUB page renderer is closed")))
            return
        }
        cancelActive("EPUB page render was superseded")
        if (boundSession !== session) {
            renderLayer.bindBorrowedSession(session)
            boundSession = session
        }
        val request = RenderRequest(
            id = requestSequence.incrementAndGet(),
            chapter = chapter,
            config = config,
            requestedPageIndex = pageIndex,
            openAtEnd = openAtEnd,
            readerChromeContentRevision = EpubPageFrameTarget.readerChromeContentRevision(
                chapter,
                config,
                readerChromeData
            ),
            deadline = SystemClock.uptimeMillis() + timeoutMillis,
            callback = callback
        )
        activeRequest = request
        lateinit var timeout: Runnable
        timeout = Runnable {
            if (timeoutRunnable !== timeout || !isActive(request)) return@Runnable
            timeoutRunnable = null
            failActive(IllegalStateException("EPUB page render timed out"))
        }
        timeoutRunnable = timeout
        handler.postDelayed(timeout, timeoutMillis)
        runCatching {
            renderLayer.updateReaderChromeData(readerChromeData)
            if (!renderLayer.reuseChapterForFrame(chapter, config, pageIndex, openAtEnd)) {
                renderLayer.showChapter(
                    chapter = chapter,
                    config = config,
                    initialPageIndex = pageIndex,
                    openAtEnd = openAtEnd
                )
            }
        }.onFailure(::failActive)
    }

    fun cancel() {
        checkMainThread()
        cancelActive("EPUB page render was cancelled")
    }

    override fun close() {
        checkMainThread()
        if (closed) return
        closed = true
        cancelActive("EPUB page renderer was closed")
        renderLayer.setListener(null)
        renderLayer.destroy()
        boundSession = null
        frameHost.close()
    }

    private fun onPositionReady(position: EpubDirectPosition) {
        val request = activeRequest ?: return
        if (request.id != requestSequence.get() ||
            position.chapterIndex != request.chapter.chapterIndex
        ) {
            return
        }
        val expectedPageIndex = if (request.openAtEnd) {
            position.pageCount.coerceAtLeast(1) - 1
        } else {
            request.requestedPageIndex
        }
        if (!request.openAtEnd && expectedPageIndex !in 0 until position.pageCount.coerceAtLeast(1)) {
            failActive(
                IllegalArgumentException(
                    "EPUB target page $expectedPageIndex is outside ${position.pageCount} pages"
                )
            )
            return
        }
        request.expectedPageIndex = expectedPageIndex
        probe(request)
    }

    private fun probe(request: RenderRequest) {
        if (!isActive(request) || request.probing) return
        val expectedPageIndex = request.expectedPageIndex ?: return
        request.probing = true
        renderLayer.probeCurrentPageFrame(
            expectedChapterIndex = request.chapter.chapterIndex,
            expectedPageIndex = expectedPageIndex
        ) { ready ->
            request.probing = false
            if (!isActive(request)) return@probeCurrentPageFrame
            if (!ready) {
                retryOrFail(request, "EPUB target frame did not become resource-complete")
                return@probeCurrentPageFrame
            }
            capture(request, expectedPageIndex)
        }
    }

    private fun capture(request: RenderRequest, expectedPageIndex: Int) {
        val stamp = renderLayer.currentPageFrameStamp() ?: run {
            retryOrFail(request, "EPUB target layout changed before capture")
            return
        }
        frameHost.capture captureCallback@{ result ->
            if (!isActive(request)) {
                result.getOrNull()?.takeUnless { it.isRecycled }?.recycle()
                return@captureCallback
            }
            val bitmap = result.getOrElse {
                retryOrFail(request, it.message ?: "EPUB target frame capture failed", it)
                return@captureCallback
            }
            // A late image may change the virtual document while the compositor copies
            // its surface. Re-measure that same document before publishing the bitmap.
            renderLayer.probeCurrentPageFrame(request.chapter.chapterIndex, expectedPageIndex) verification@{ ready ->
                if (!isActive(request)) {
                    bitmap.takeUnless { it.isRecycled }?.recycle()
                    return@verification
                }
                if (!ready || renderLayer.currentPageFrameStamp() != stamp) {
                    bitmap.takeUnless { it.isRecycled }?.recycle()
                    retryOrFail(request, "EPUB target changed during hardware capture")
                    return@verification
                }
                finishCapture(request, expectedPageIndex, bitmap, stamp)
            }
        }
    }

    private fun finishCapture(
        request: RenderRequest,
        expectedPageIndex: Int,
        bitmap: Bitmap,
        stamp: EpubPageFrameStamp
    ) {
        val position = renderLayer.position?.takeIf {
            it.chapterIndex == request.chapter.chapterIndex && it.pageIndex == expectedPageIndex
        }
        val requiresPixels = EpubDirectRenderableContentPolicy.requiresRenderableContent(
            hasText = request.chapter.plainText.isNotBlank(),
            singlePage = request.chapter.layoutMode.singlePage,
            fullPageArtwork = request.chapter.fullPageArtwork,
            duokanGallery = request.chapter.duokanGallery
        )
        val hasPixels = !requiresPixels || bitmapHasVisualContent(
            bitmap = bitmap,
            backgroundColor = request.config.backgroundColor
        )
        if (position == null || !hasPixels) {
            bitmap.recycle()
            retryOrFail(
                request,
                if (position == null) {
                    "EPUB target changed before hardware capture"
                } else {
                    "EPUB hardware capture contains only the reader background"
                }
            )
            return
        }
        activeRequest = null
        retryRunnable?.let(handler::removeCallbacks)
        retryRunnable = null
        timeoutRunnable?.let(handler::removeCallbacks)
        timeoutRunnable = null
        request.callback(
            Result.success(
                EpubRenderedPageFrame(
                    chapterIndex = position.chapterIndex,
                    chapterHref = position.chapterHref,
                    pageIndex = position.pageIndex,
                    pageCount = position.pageCount,
                    bitmap = bitmap,
                    layoutSignature = EpubPageFrameTarget.layoutSignature(
                        request.config,
                        viewportWidth,
                        viewportHeight
                    ),
                    readerChromeContentRevision = request.readerChromeContentRevision,
                    renderStamp = stamp
                )
            )
        )
    }

    private fun retryOrFail(
        request: RenderRequest,
        message: String,
        cause: Throwable? = null
    ) {
        if (!isActive(request)) return
        if (SystemClock.uptimeMillis() >= request.deadline) {
            failActive(IllegalStateException(message, cause))
            return
        }
        retryRunnable?.let(handler::removeCallbacks)
        lateinit var retry: Runnable
        retry = Runnable {
            if (retryRunnable !== retry || !isActive(request)) return@Runnable
            retryRunnable = null
            probe(request)
        }
        retryRunnable = retry
        handler.postDelayed(retry, FRAME_READY_RETRY_MS)
    }

    private fun failActive(throwable: Throwable) {
        val request = activeRequest ?: return
        activeRequest = null
        requestSequence.incrementAndGet()
        retryRunnable?.let(handler::removeCallbacks)
        retryRunnable = null
        timeoutRunnable?.let(handler::removeCallbacks)
        timeoutRunnable = null
        request.callback(Result.failure(throwable))
    }

    private fun cancelActive(message: String) {
        failActive(IllegalStateException(message))
    }

    private fun isActive(request: RenderRequest): Boolean {
        return !closed && activeRequest === request && request.id == requestSequence.get()
    }

    private fun bitmapHasVisualContent(bitmap: Bitmap, backgroundColor: Int): Boolean {
        val columns = bitmap.width.coerceAtMost(SNAPSHOT_SAMPLE_GRID)
        val rows = bitmap.height.coerceAtMost(SNAPSHOT_SAMPLE_GRID)
        val colors = IntArray(columns * rows)
        val rowPixels = IntArray(bitmap.width)
        var offset = 0
        for (row in 0 until rows) {
            val y = ((row + 0.5f) * bitmap.height / rows).toInt()
                .coerceAtMost(bitmap.height - 1)
            bitmap.getPixels(rowPixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (column in 0 until columns) {
                val x = ((column + 0.5f) * bitmap.width / columns).toInt()
                    .coerceAtMost(bitmap.width - 1)
                colors[offset++] = rowPixels[x]
            }
        }
        return EpubDirectSnapshotVisualPolicy.hasVisualContent(backgroundColor, colors)
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "EPUB page renderer operation must run on the main thread"
        }
    }

    private companion object {
        const val DEFAULT_RENDER_TIMEOUT_MS = 6_000L
        const val FRAME_READY_RETRY_MS = 48L
        const val SNAPSHOT_SAMPLE_GRID = 64
    }
}
