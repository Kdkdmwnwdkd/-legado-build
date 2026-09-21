package io.legado.app.model.localBook.epubcore.direct

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EpubDirectDiskResourceCacheTest {

    @Test
    fun `concurrent preparation extracts one bounded file`() {
        withCache(maxEntryBytes = 16, maxTotalBytes = 32) { cache ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val loads = AtomicInteger()
            val bytes = byteArrayOf(1, 2, 3, 4)
            val loader = {
                loads.incrementAndGet()
                entered.countDown()
                release.await()
                ByteArrayInputStream(bytes)
            }

            cache.prepare("media.bin", bytes.size.toLong(), loader)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            cache.prepare("media.bin", bytes.size.toLong(), loader)
            release.countDown()

            val file = awaitFile(cache, "media.bin", bytes.size.toLong())
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(1, loads.get())
        }
    }

    @Test
    fun `oversized resource is not scheduled`() {
        withCache(maxEntryBytes = 3, maxTotalBytes = 8) { cache ->
            val loads = AtomicInteger()

            cache.prepare("large.bin", 4) {
                loads.incrementAndGet()
                ByteArrayInputStream(ByteArray(4))
            }

            Thread.sleep(40)
            assertNull(cache.get("large.bin", 4))
            assertEquals(0, loads.get())
        }
    }

    @Test
    fun `least recently used files are pruned to the byte budget`() {
        withCache(maxEntryBytes = 4, maxTotalBytes = 6) { cache ->
            cache.prepare("first.bin", 4) { ByteArrayInputStream(byteArrayOf(1, 1, 1, 1)) }
            awaitFile(cache, "first.bin", 4)
            Thread.sleep(20)
            cache.prepare("second.bin", 4) { ByteArrayInputStream(byteArrayOf(2, 2, 2, 2)) }
            val second = awaitFile(cache, "second.bin", 4)

            assertNull(cache.get("first.bin", 4))
            assertArrayEquals(byteArrayOf(2, 2, 2, 2), second.readBytes())
        }
    }

    @Test
    fun `archive close callback waits for active extraction to drain`() {
        val directory = Files.createTempDirectory("epub-direct-resource-close-test").toFile()
        val cache = EpubDirectDiskResourceCache(directory, maxEntryBytes = 8, maxTotalBytes = 8)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(1)
        try {
            cache.prepare("media.bin", 1) {
                object : InputStream() {
                    private var emitted = false

                    override fun read(): Int {
                        if (emitted) return -1
                        entered.countDown()
                        var released = false
                        while (!released) {
                            released = try {
                                release.await(10, TimeUnit.MILLISECONDS)
                            } catch (_: InterruptedException) {
                                Thread.interrupted()
                                false
                            }
                        }
                        emitted = true
                        return 7
                    }
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))

            cache.closeWhenDrained(drained::countDown)

            assertFalse(drained.await(50, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(drained.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            cache.close()
            directory.deleteRecursively()
        }
    }

    private fun withCache(
        maxEntryBytes: Long,
        maxTotalBytes: Long,
        block: (EpubDirectDiskResourceCache) -> Unit
    ) {
        val directory = Files.createTempDirectory("epub-direct-resource-test").toFile()
        val cache = EpubDirectDiskResourceCache(directory, maxEntryBytes, maxTotalBytes)
        try {
            block(cache)
        } finally {
            cache.close()
            directory.deleteRecursively()
        }
    }

    private fun awaitFile(
        cache: EpubDirectDiskResourceCache,
        path: String,
        size: Long
    ): java.io.File {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            cache.get(path, size)?.let { return it }
            Thread.sleep(5)
        }
        error("Timed out waiting for cached EPUB resource")
    }
}
