package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.data.entities.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TextReaderChapterIndexTest {
    private fun chapter(index: Int, url: String, volume: Boolean = false) =
        BookChapter(bookUrl = "original-book", index = index, url = url, title = "相同标题", isVolume = volume)

    @Test fun `repeated titles and volume entries keep original directory identities`() {
        val volume = chapter(0, "volume-1", true)
        val first = chapter(1, "https://source/one")
        val second = chapter(2, "https://source/two")
        val index = TextReaderChapterIndex(listOf(volume, first, second))
        assertSame(volume, index.chapter(0))
        assertSame(first, index.chapter(1))
        assertSame(second, index.chapter(2))
        assertEquals(0, index.resolve(0))
        assertEquals(1, index.adjacent(0, 1))
        assertEquals(0, index.adjacent(1, -1))
        assertNull(index.adjacent(0, -1))
        assertNull(index.adjacent(2, 1))
    }

    @Test fun `missing explicit target is not remapped and sparse adjacency stays ordered`() {
        val index = TextReaderChapterIndex(listOf(chapter(8, "eight"), chapter(2, "two"), chapter(5, "five")))
        assertNull(index.resolve(4))
        assertEquals(5, index.adjacent(2, 1))
        assertEquals(5, index.adjacent(8, -1))
        assertEquals(2, index.adjacent(4, -1))
        assertEquals(5, index.adjacent(4, 1))
    }

    @Test fun `replaced or deleted target rejects an obsolete fetch result`() {
        val current = chapter(1, "old")
        val index = TextReaderChapterIndex(listOf(current))
        assertTrue(index.matches(1, current.copy(title = "改名")))
        assertFalse(index.matches(1, current.copy(url = "replacement")))
        assertFalse(index.matches(1, current.copy(bookUrl = "another-book")))
        assertFalse(index.matches(1, null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate chapter indices fail instead of silently merging the directory`() {
        TextReaderChapterIndex(listOf(chapter(1, "one"), chapter(1, "two")))
    }
}
