package io.legado.app.ui.book.read.epub

internal object EpubSnapshotSizePolicy {

    data class Size(val width: Int, val height: Int)

    fun sizeFor(width: Int, height: Int, pixelBudget: Long): Size {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        // Active page animation frames must stay pixel-identical to the physical
        // viewport. Memory pressure is handled by retaining fewer frames, never by
        // blurring the source and target pages.
        pixelBudget.coerceAtLeast(1L)
        return Size(width = safeWidth, height = safeHeight)
    }
}
