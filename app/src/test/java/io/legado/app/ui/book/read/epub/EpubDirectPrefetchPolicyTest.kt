package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDirectPrefetchPolicyTest {

    @Test
    fun `forward chapter is prepared before backward chapter`() {
        assertEquals(listOf(4, 2), EpubDirectPrefetchPolicy.candidates(3, 8))
    }

    @Test
    fun `chapter edges only return valid candidates`() {
        assertEquals(listOf(1), EpubDirectPrefetchPolicy.candidates(0, 3))
        assertEquals(listOf(1), EpubDirectPrefetchPolicy.candidates(2, 3))
        assertTrue(EpubDirectPrefetchPolicy.candidates(0, 1).isEmpty())
    }

    @Test
    fun `single slot keeps forward candidate instead of evicting it with backward`() {
        assertEquals(listOf(4), EpubDirectPrefetchPolicy.candidates(3, 8, capacity = 1))
        assertTrue(EpubDirectPrefetchPolicy.candidates(3, 8, capacity = 0).isEmpty())
    }

    @Test
    fun `prefetch accepts non contiguous readable chapter indexes`() {
        assertEquals(
            listOf(7, 0),
            EpubDirectPrefetchPolicy.candidates(
                nextChapterIndex = 7,
                previousChapterIndex = 0,
                capacity = 2
            )
        )
    }

    private val readableChapters = listOf(0, 3, 7, 12, 19, 20, 24)

    private fun adjacent(index: Int, direction: Int): Int? =
        readableChapters.indexOf(index).takeIf { it >= 0 }
            ?.let { readableChapters.getOrNull(it + direction) }

    @Test
    fun `larger caches warm more than the immediate neighbours in reading order`() {
        assertEquals(listOf(19, 7, 20, 3), EpubDirectPrefetchPolicy.candidates(12, 4, ::adjacent))
        assertEquals(listOf(19, 7, 20, 3, 24, 0), EpubDirectPrefetchPolicy.candidates(12, 6, ::adjacent))
    }

    @Test
    fun `book edges use remaining slots for available chapters`() {
        assertEquals(listOf(3, 7, 12, 19, 20, 24), EpubDirectPrefetchPolicy.candidates(0, 6, ::adjacent))
        assertEquals(listOf(20, 19, 12, 7, 3, 0), EpubDirectPrefetchPolicy.candidates(24, 6, ::adjacent))
        assertEquals(listOf(19), EpubDirectPrefetchPolicy.candidates(12, 1, ::adjacent))
    }

    @Test
    fun `broken adjacency and unlimited budgets cannot cause a prefetch loop`() {
        assertTrue(EpubDirectPrefetchPolicy.candidates(12, 6) { index, _ -> index }.isEmpty())
        assertTrue(EpubDirectPrefetchPolicy.candidates(-1, 6, ::adjacent).isEmpty())
        assertTrue(EpubDirectPrefetchPolicy.candidates(12, 0, ::adjacent).isEmpty())
        assertEquals(listOf(2), EpubDirectPrefetchPolicy.candidates(1, 6) { _, _ -> 2 })
        assertEquals(6, EpubDirectPrefetchPolicy.candidates(100, Int.MAX_VALUE) { index, direction ->
            index + direction
        }.size)
    }
}
