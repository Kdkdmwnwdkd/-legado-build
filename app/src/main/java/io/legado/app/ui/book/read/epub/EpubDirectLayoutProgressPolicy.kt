package io.legado.app.ui.book.read.epub

import kotlin.math.roundToInt

internal object EpubDirectLayoutProgressPolicy {

    fun remapPageIndex(currentPage: Int, oldPageCount: Int, newPageCount: Int): Int {
        val newLast = (newPageCount - 1).coerceAtLeast(0)
        if (newLast == 0 || oldPageCount <= 1) return 0
        return (
            currentPage.coerceIn(0, oldPageCount - 1).toFloat() /
                (oldPageCount - 1).toFloat() * newLast
            ).roundToInt().coerceIn(0, newLast)
    }
}
