package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDirectPreloadCacheTest {

    @Test
    fun `directory jumps free all obsolete slots before warming the new next chapter`() {
        val evicted = mutableListOf<Int>()
        val cache = EpubDirectPreloadCache<Int>(2, evicted::add)
        cache.put("old-next", 6)
        cache.put("old-previous", 4)
        val candidates = EpubDirectPrefetchPolicy.candidates(20, 30, capacity = 2)

        cache.evictWhere { it !in candidates }
        assertEquals(0, cache.size)
        cache.put("next", 21)
        cache.put("previous", 19)

        assertEquals(listOf(6, 4), evicted)
        assertEquals(21, cache.take("next"))
        assertEquals(19, cache.take("previous"))
    }

    @Test
    fun `rolling the neighbour window preserves a matching preload and evicts each old value once`() {
        val evicted = mutableListOf<Int>()
        val cache = EpubDirectPreloadCache<Int>(2, evicted::add)
        cache.put("next", 6)
        cache.put("previous", 4)

        cache.evictWhere { it != 6 }
        cache.evictWhere { it != 6 }
        assertEquals(listOf(4), evicted)
        assertEquals(6, cache.take("next"))
        cache.clear()
        assertEquals(listOf(4), evicted)
    }

    @Test
    fun `shrinking capacity evicts only the least recently used entries`() {
        val evicted = mutableListOf<Any>()
        val first = Any()
        val second = Any()
        val cache = EpubDirectPreloadCache<Any>(2, evicted::add)
        cache.put("first", first)
        cache.put("second", second)

        cache.resize(1)

        assertEquals(listOf(first), evicted)
        assertTrue(cache.contains("second"))
    }

    @Test
    fun takeTransfersOwnershipWithoutEviction() {
        val evicted = mutableListOf<Any>()
        val cache = EpubDirectPreloadCache<Any>(2, evicted::add)
        val value = Any()

        cache.put("chapter", value)

        assertSame(value, cache.take("chapter"))
        assertEquals(0, cache.size)
        assertTrue(evicted.isEmpty())
    }

    @Test
    fun replacingKeyEvictsPreviousValueOnce() {
        val evicted = mutableListOf<Any>()
        val cache = EpubDirectPreloadCache<Any>(2, evicted::add)
        val first = Any()
        val second = Any()

        cache.put("chapter", first)
        cache.put("chapter", second)

        assertEquals(listOf(first), evicted)
        assertSame(second, cache.take("chapter"))
    }

    @Test
    fun capacityEvictsOldestUnclaimedValue() {
        val evicted = mutableListOf<Any>()
        val cache = EpubDirectPreloadCache<Any>(2, evicted::add)
        val first = Any()
        val second = Any()
        val third = Any()

        cache.put("first", first)
        cache.put("second", second)
        cache.put("third", third)

        assertEquals(listOf(first), evicted)
        assertSame(second, cache.take("second"))
        assertSame(third, cache.take("third"))
    }

    @Test
    fun containsKeepsAHotValueAheadOfAnUnusedValue() {
        val evicted = mutableListOf<Any>()
        val cache = EpubDirectPreloadCache<Any>(2, evicted::add)
        val hot = Any()
        val unused = Any()
        val newest = Any()

        cache.put("hot", hot)
        cache.put("unused", unused)
        assertTrue(cache.contains("hot"))
        cache.put("newest", newest)

        assertEquals(listOf(unused), evicted)
        assertSame(hot, cache.take("hot"))
        assertSame(newest, cache.take("newest"))
    }

    @Test
    fun movingSameValueToAnotherKeyDoesNotEvictIt() {
        val evicted = mutableListOf<Any>()
        val cache = EpubDirectPreloadCache<Any>(2, evicted::add)
        val value = Any()

        cache.put("old", value)
        cache.put("new", value)

        assertNull(cache.take("old"))
        assertSame(value, cache.take("new"))
        assertTrue(evicted.isEmpty())
    }

    @Test
    fun removeValueDropsEntryWithoutEviction() {
        val evicted = mutableListOf<Any>()
        val cache = EpubDirectPreloadCache<Any>(2, evicted::add)
        val value = Any()

        cache.put("chapter", value)

        assertTrue(cache.removeValue(value))
        assertFalse(cache.removeValue(value))
        assertNull(cache.take("chapter"))
        assertTrue(evicted.isEmpty())
    }

    @Test
    fun clearEvictsEveryOwnedValue() {
        val evicted = mutableListOf<Any>()
        val cache = EpubDirectPreloadCache<Any>(2, evicted::add)
        val first = Any()
        val second = Any()

        cache.put("first", first)
        cache.put("second", second)
        cache.clear()
        cache.clear()

        assertEquals(listOf(first, second), evicted)
        assertEquals(0, cache.size)
    }
}
