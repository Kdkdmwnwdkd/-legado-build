package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.data.entities.BookChapter

/** An immutable index over the original TOC; titles, volume flags and URLs are not rewritten. */
internal class TextReaderChapterIndex(chapters: List<BookChapter>) {
    private val chaptersByIndex = chapters.associateBy { it.index }
    private val indexes = chaptersByIndex.keys.sorted()

    init {
        require(chaptersByIndex.size == chapters.size) { "章节目录包含重复序号" }
    }

    fun chapter(index: Int): BookChapter? = chaptersByIndex[index]

    fun resolve(index: Int): Int? = index.takeIf(chaptersByIndex::containsKey)

    fun matches(index: Int, current: BookChapter?): Boolean {
        val original = chapter(index) ?: return false
        return current != null && current.index == original.index &&
            current.bookUrl == original.bookUrl && current.url == original.url
    }

    fun adjacent(index: Int, direction: Int): Int? {
        val position = indexes.binarySearch(index)
        val insertion = if (position >= 0) position else -position - 1
        val target = if (direction < 0) insertion - 1 else if (position >= 0) position + 1 else insertion
        return indexes.getOrNull(target)
    }
}
