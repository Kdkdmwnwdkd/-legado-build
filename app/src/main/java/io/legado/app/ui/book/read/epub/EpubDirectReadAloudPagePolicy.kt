package io.legado.app.ui.book.read.epub

import kotlin.math.roundToInt

/**
 * Maps the character coordinate used by the speech engine to the visual page
 * coordinate used by the Direct EPUB renderer. The two paginations can have
 * different page counts, so neither coordinate is stored in the other field.
 */
internal object EpubDirectReadAloudPagePolicy {

    fun pageForChapterPosition(
        chapterPosition: Int,
        chapterLength: Int,
        pageCount: Int
    ): Int {
        val count = pageCount.coerceAtLeast(1)
        if (count == 1 || chapterLength <= 1) return 0
        val position = chapterPosition.coerceIn(0, chapterLength - 1)
        val ratio = position.toDouble() / (chapterLength - 1).toDouble()
        return (ratio * (count - 1)).roundToInt().coerceIn(0, count - 1)
    }

    fun chapterPositionForPage(
        pageIndex: Int,
        pageCount: Int,
        chapterLength: Int
    ): Int {
        if (chapterLength <= 1 || pageCount <= 1) return 0
        val page = pageIndex.coerceIn(0, pageCount - 1)
        val ratio = page.toDouble() / (pageCount - 1).toDouble()
        return (ratio * (chapterLength - 1)).roundToInt().coerceIn(0, chapterLength - 1)
    }
}
