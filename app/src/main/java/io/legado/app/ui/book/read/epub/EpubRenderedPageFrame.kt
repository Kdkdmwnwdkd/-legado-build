package io.legado.app.ui.book.read.epub

import android.graphics.Bitmap
import java.io.Closeable

/** Only compared within the WebView that produced a frame, before and after capture. */
internal data class EpubPageFrameStamp(
    val token: Long,
    val viewIdentity: Int,
    val chapterIndex: Int,
    val pageIndex: Int,
    val layoutRevision: Long,
    val visualRevision: Long,
    val renderSequence: Long,
    val viewportWidth: Int,
    val viewportHeight: Int
)

internal class EpubRenderedPageFrame(
    val chapterIndex: Int,
    val chapterHref: String,
    val pageIndex: Int,
    val pageCount: Int,
    val bitmap: Bitmap,
    /** Full page-layout identity, including reader chrome geometry when enabled. */
    val layoutSignature: String = "",
    val readerChromeContentRevision: Long = 0L,
    val renderStamp: EpubPageFrameStamp? = null
) : Closeable {

    private var bitmapTransferred = false

    fun takeBitmap(): Bitmap? {
        if (bitmapTransferred || bitmap.isRecycled) return null
        bitmapTransferred = true
        return bitmap
    }

    override fun close() {
        if (!bitmapTransferred && !bitmap.isRecycled) bitmap.recycle()
    }
}
