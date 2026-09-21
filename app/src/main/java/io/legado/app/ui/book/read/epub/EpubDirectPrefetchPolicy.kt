package io.legado.app.ui.book.read.epub

internal object EpubDirectPrefetchPolicy {

    /** Follow the session's reading order, including non-contiguous EPUB spine entries. */
    fun candidates(
        chapterIndex: Int,
        capacity: Int,
        adjacentChapterIndex: (Int, Int) -> Int?
    ): List<Int> {
        if (chapterIndex < 0 || capacity <= 0) return emptyList()
        val limit = capacity.coerceAtMost(6)
        val result = linkedSetOf<Int>()
        var next: Int? = chapterIndex
        var previous: Int? = chapterIndex
        fun advance(from: Int?, direction: Int): Int? {
            val candidate = from?.let { adjacentChapterIndex(it, direction) } ?: return null
            if (candidate < 0 || candidate == chapterIndex || !result.add(candidate)) return null
            return candidate
        }
        while (result.size < limit && (next != null || previous != null)) {
            next = advance(next, 1)
            if (result.size < limit) previous = advance(previous, -1)
        }
        return result.toList()
    }

    fun candidates(
        nextChapterIndex: Int?,
        previousChapterIndex: Int?,
        capacity: Int = Int.MAX_VALUE
    ): List<Int> {
        if (capacity <= 0) return emptyList()
        return listOfNotNull(nextChapterIndex, previousChapterIndex)
            .distinct()
            .take(capacity)
    }

    fun candidates(chapterIndex: Int, chapterCount: Int, capacity: Int = Int.MAX_VALUE): List<Int> {
        if (chapterCount <= 1 || chapterIndex !in 0 until chapterCount || capacity <= 0) return emptyList()
        return candidates(
            nextChapterIndex = (chapterIndex + 1).takeIf { it < chapterCount },
            previousChapterIndex = (chapterIndex - 1).takeIf { it >= 0 },
            capacity = capacity
        )
    }
}
