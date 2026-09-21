package io.legado.app.model.localBook.epubcore.direct

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EpubDirectResourceCacheTest {

    @Test
    fun `reuses small resources without loading twice`() {
        val cache = EpubDirectResourceCache(maxEntryBytes = 8, maxTotalBytes = 16)
        var loads = 0

        repeat(2) {
            assertArrayEquals(byteArrayOf(1, 2, 3), cache.getOrLoad("image", 3) {
                loads++
                byteArrayOf(1, 2, 3)
            })
        }

        assertEquals(1, loads)
    }

    @Test
    fun `skips oversized and unknown resources`() {
        val cache = EpubDirectResourceCache(maxEntryBytes = 4, maxTotalBytes = 8)
        var loads = 0

        assertNull(cache.getOrLoad("large", 5) { loads++; ByteArray(5) })
        assertNull(cache.getOrLoad("unknown", null) { loads++; ByteArray(1) })
        assertEquals(0, loads)
    }

    @Test
    fun `evicts least recently used entries within byte budget`() {
        val cache = EpubDirectResourceCache(maxEntryBytes = 4, maxTotalBytes = 6)
        cache.getOrLoad("a", 3) { byteArrayOf(1, 1, 1) }
        cache.getOrLoad("b", 3) { byteArrayOf(2, 2, 2) }
        cache.getOrLoad("a", 3) { error("a must be cached") }
        cache.getOrLoad("c", 3) { byteArrayOf(3, 3, 3) }
        var reloads = 0

        cache.getOrLoad("b", 3) { reloads++; byteArrayOf(2, 2, 2) }

        assertEquals(1, reloads)
    }

    @Test
    fun `coalesces concurrent loads for the same resource`() {
        val cache = EpubDirectResourceCache(maxEntryBytes = 8, maxTotalBytes = 16)
        val pool = Executors.newFixedThreadPool(2)
        val loaderEntered = CountDownLatch(1)
        val releaseLoader = CountDownLatch(1)
        val loads = AtomicInteger()
        try {
            val first = pool.submit<ByteArray?> {
                cache.getOrLoad("shared.css", 3) {
                    loads.incrementAndGet()
                    loaderEntered.countDown()
                    releaseLoader.await()
                    byteArrayOf(1, 2, 3)
                }
            }
            assertTrue(loaderEntered.await(2, TimeUnit.SECONDS))
            val second = pool.submit<ByteArray?> {
                cache.getOrLoad("shared.css", 3) {
                    loads.incrementAndGet()
                    byteArrayOf(4, 5, 6)
                }
            }
            releaseLoader.countDown()

            assertArrayEquals(byteArrayOf(1, 2, 3), first.get(2, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(1, 2, 3), second.get(2, TimeUnit.SECONDS))
            assertEquals(1, loads.get())
        } finally {
            releaseLoader.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `different resources still load concurrently`() {
        val cache = EpubDirectResourceCache(maxEntryBytes = 8, maxTotalBytes = 16)
        val pool = Executors.newFixedThreadPool(2)
        val loadersEntered = CountDownLatch(2)
        val releaseLoaders = CountDownLatch(1)
        try {
            val first = pool.submit<ByteArray?> {
                cache.getOrLoad("a.css", 1) {
                    loadersEntered.countDown()
                    releaseLoaders.await()
                    byteArrayOf(1)
                }
            }
            val second = pool.submit<ByteArray?> {
                cache.getOrLoad("b.css", 1) {
                    loadersEntered.countDown()
                    releaseLoaders.await()
                    byteArrayOf(2)
                }
            }

            assertTrue(loadersEntered.await(2, TimeUnit.SECONDS))
            releaseLoaders.countDown()
            assertArrayEquals(byteArrayOf(1), first.get(2, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(2), second.get(2, TimeUnit.SECONDS))
        } finally {
            releaseLoaders.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `failed load can be retried`() {
        val cache = EpubDirectResourceCache(maxEntryBytes = 8, maxTotalBytes = 16)
        val loads = AtomicInteger()

        val failure = runCatching {
            cache.getOrLoad("retry.css", 3) {
                loads.incrementAndGet()
                error("first load failed")
            }
        }.exceptionOrNull()
        val recovered = cache.getOrLoad("retry.css", 3) {
            loads.incrementAndGet()
            byteArrayOf(1, 2, 3)
        }

        assertTrue(failure is IllegalStateException)
        assertArrayEquals(byteArrayOf(1, 2, 3), recovered)
        assertEquals(2, loads.get())
    }
}
