package io.legado.app.ui.book.read.epub

internal data class EpubAdjacentPageRequest(
    val chapterIndex: Int,
    val pageIndex: Int,
    val openAtEnd: Boolean
)

internal data class EpubAdjacentPagePlan(
    val previous: EpubAdjacentPageRequest?,
    val next: EpubAdjacentPageRequest?,
    val previousPrefetch: EpubAdjacentPageRequest? = null,
    val nextPrefetch: EpubAdjacentPageRequest? = null
)

internal object EpubAdjacentPageTargetPolicy {

    fun plan(
        chapterIndex: Int,
        pageIndex: Int,
        pageCount: Int,
        previousChapterIndex: Int?,
        nextChapterIndex: Int?
    ): EpubAdjacentPagePlan {
        if (chapterIndex < 0 || pageCount <= 0 || pageIndex !in 0 until pageCount) {
            return EpubAdjacentPagePlan(null, null)
        }
        val previous = if (pageIndex > 0) {
            EpubAdjacentPageRequest(chapterIndex, pageIndex - 1, openAtEnd = false)
        } else {
            previousChapterIndex?.let { EpubAdjacentPageRequest(it, 0, openAtEnd = true) }
        }
        val next = if (pageIndex < pageCount - 1) {
            EpubAdjacentPageRequest(chapterIndex, pageIndex + 1, openAtEnd = false)
        } else {
            nextChapterIndex?.let { EpubAdjacentPageRequest(it, 0, openAtEnd = false) }
        }
        val previousPrefetch = if (pageIndex >= 2) {
            EpubAdjacentPageRequest(chapterIndex, pageIndex - 2, openAtEnd = false)
        } else {
            previousChapterIndex
                ?.takeIf { pageIndex == 1 }
                ?.let { EpubAdjacentPageRequest(it, 0, openAtEnd = true) }
        }
        val nextPrefetch = if (pageIndex + 2 < pageCount) {
            EpubAdjacentPageRequest(chapterIndex, pageIndex + 2, openAtEnd = false)
        } else {
            nextChapterIndex
                ?.takeIf { pageIndex == pageCount - 2 }
                ?.let { EpubAdjacentPageRequest(it, 0, openAtEnd = false) }
        }
        return EpubAdjacentPagePlan(previous, next, previousPrefetch, nextPrefetch)
    }

    fun plan(
        chapterIndex: Int,
        pageIndex: Int,
        pageCount: Int,
        previousChapterAvailable: Boolean,
        nextChapterAvailable: Boolean
    ): EpubAdjacentPagePlan {
        return plan(
            chapterIndex = chapterIndex,
            pageIndex = pageIndex,
            pageCount = pageCount,
            previousChapterIndex = (chapterIndex - 1).takeIf { previousChapterAvailable && it >= 0 },
            nextChapterIndex = (chapterIndex + 1).takeIf { nextChapterAvailable }
        )
    }
}
