package io.legado.app.ui.book.read.epub

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns an isolated hardware-rendered display whose final frames can be captured
 * without including overlays from the visible reader window.
 */
internal class EpubVirtualDisplayFrameHost(
    context: Context,
    val width: Int,
    val height: Int,
    densityDpi: Int
) : Closeable {

    private data class PendingCapture(
        val id: Long,
        val minimumFrameSequence: Long,
        val callback: (Result<Bitmap>) -> Unit
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureThread = HandlerThread("epub-virtual-frame").apply { start() }
    private val captureHandler = Handler(captureThread.looper)
    private val imageReader = ImageReader.newInstance(
        width,
        height,
        PixelFormat.RGBA_8888,
        MAX_IMAGES
    )
    private val frameSequence = AtomicLong(0L)
    private val requestSequence = AtomicLong(0L)
    private val stateLock = Any()
    private var pendingCapture: PendingCapture? = null
    @Volatile
    private var closed = false

    private val virtualDisplay: VirtualDisplay
    private val presentation: RenderPresentation

    val renderContext: Context
        get() = presentation.context

    init {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "EPUB virtual display must be created on the main thread"
        }
        require(width > 0 && height > 0)
        require(densityDpi > 0)

        imageReader.setOnImageAvailableListener(::onImageAvailable, captureHandler)
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        virtualDisplay = checkNotNull(
            displayManager.createVirtualDisplay(
                DISPLAY_NAME,
                width,
                height,
                densityDpi,
                imageReader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            )
        ) { "Unable to create EPUB virtual display" }
        presentation = RenderPresentation(context, virtualDisplay.display, width, height)
        try {
            presentation.show()
        } catch (throwable: Throwable) {
            runCatching { virtualDisplay.release() }
            imageReader.setOnImageAvailableListener(null, null)
            imageReader.close()
            captureThread.quitSafely()
            throw throwable
        }
    }

    fun setContent(view: View) {
        checkMainThread()
        check(!closed) { "EPUB virtual display is closed" }
        (view.parent as? ViewGroup)?.removeView(view)
        presentation.container.removeAllViews()
        presentation.container.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    fun capture(
        timeoutMillis: Long = DEFAULT_CAPTURE_TIMEOUT_MS,
        callback: (Result<Bitmap>) -> Unit
    ) {
        checkMainThread()
        require(timeoutMillis > 0L)
        if (closed) {
            callback(Result.failure(IllegalStateException("EPUB virtual display is closed")))
            return
        }
        val requestId = requestSequence.incrementAndGet()
        captureHandler.post {
            if (closed) {
                mainHandler.post {
                    callback(Result.failure(IllegalStateException("EPUB virtual display is closed")))
                }
                return@post
            }
            drainImages()
            if (closed) {
                mainHandler.post {
                    callback(Result.failure(IllegalStateException("EPUB virtual display is closed")))
                }
                return@post
            }
            val request = PendingCapture(
                id = requestId,
                minimumFrameSequence = frameSequence.get() + 1L,
                callback = callback
            )
            val replaced = synchronized(stateLock) {
                pendingCapture.also { pendingCapture = request }
            }
            replaced?.let {
                deliver(
                    it,
                    Result.failure(IllegalStateException("EPUB frame capture was superseded"))
                )
            }
            captureHandler.postDelayed({ timeout(request) }, timeoutMillis)
            mainHandler.post {
                if (!closed) {
                    presentation.container.invalidate()
                    presentation.container.getChildAt(0)?.invalidate()
                }
            }
        }
    }

    override fun close() {
        checkMainThread()
        if (closed) return
        closed = true
        val pending = synchronized(stateLock) {
            pendingCapture.also { pendingCapture = null }
        }
        pending?.let {
            deliver(it, Result.failure(IllegalStateException("EPUB virtual display was closed")))
        }
        imageReader.setOnImageAvailableListener(null, null)
        runCatching { presentation.dismiss() }
        runCatching { virtualDisplay.setSurface(null) }
        runCatching { virtualDisplay.release() }
        captureHandler.post {
            drainImages()
            imageReader.close()
            captureThread.quitSafely()
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        val sequence = frameSequence.incrementAndGet()
        val request = synchronized(stateLock) {
            pendingCapture?.takeIf { sequence >= it.minimumFrameSequence }
                ?.also { pendingCapture = null }
        }
        if (request == null) {
            image.close()
            return
        }
        val result = runCatching { image.toBitmap(width, height) }
        image.close()
        deliver(request, result)
    }

    private fun timeout(request: PendingCapture) {
        val active = synchronized(stateLock) {
            if (pendingCapture !== request) false else {
                pendingCapture = null
                true
            }
        }
        if (active) {
            deliver(
                request,
                Result.failure(IllegalStateException("EPUB virtual frame capture timed out"))
            )
        }
    }

    private fun deliver(request: PendingCapture, result: Result<Bitmap>) {
        mainHandler.post { request.callback(result) }
    }

    private fun drainImages() {
        while (true) {
            val image = runCatching { imageReader.acquireLatestImage() }.getOrNull() ?: return
            frameSequence.incrementAndGet()
            image.close()
        }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "EPUB virtual display operation must run on the main thread"
        }
    }

    private class RenderPresentation(
        context: Context,
        display: Display,
        width: Int,
        height: Int
    ) : Presentation(context, display) {

        val container = FrameLayout(this.context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = true
            clipToPadding = true
        }

        init {
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(width, height)
                addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            }
            setContentView(
                container,
                ViewGroup.LayoutParams(width, height)
            )
        }
    }

    private fun Image.toBitmap(expectedWidth: Int, expectedHeight: Int): Bitmap {
        require(width == expectedWidth && height == expectedHeight) {
            "Unexpected virtual frame size ${width}x${height}"
        }
        val plane = planes.singleOrNull()
            ?: error("Unexpected virtual frame plane count ${planes.size}")
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val paddedWidth = EpubVirtualFrameLayout.paddedWidth(
            width = expectedWidth,
            pixelStride = pixelStride,
            rowStride = rowStride
        ) ?: error(
            "Unsupported virtual frame stride: width=$expectedWidth, " +
                "pixelStride=$pixelStride, rowStride=$rowStride"
        )
        val buffer = plane.buffer
        buffer.rewind()
        val padded = Bitmap.createBitmap(paddedWidth, expectedHeight, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        if (paddedWidth == expectedWidth) return padded
        return Bitmap.createBitmap(padded, 0, 0, expectedWidth, expectedHeight).also {
            padded.recycle()
        }
    }

    private companion object {
        const val DISPLAY_NAME = "legado-epub-page-render"
        const val MAX_IMAGES = 3
        const val DEFAULT_CAPTURE_TIMEOUT_MS = 1_500L
    }
}

internal object EpubVirtualFrameLayout {

    fun paddedWidth(width: Int, pixelStride: Int, rowStride: Int): Int? {
        if (width <= 0 || pixelStride <= 0 || rowStride <= 0) return null
        val minimumRowBytes = width.toLong() * pixelStride
        if (rowStride < minimumRowBytes || rowStride % pixelStride != 0) return null
        return rowStride / pixelStride
    }
}
