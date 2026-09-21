package io.legado.app.model.localBook.epubcore.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class EpubArchiveLeaseGateTest {

    @Test
    fun `archive close waits for active read`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delegateClosed = AtomicBoolean(false)
        val archive = EpubArchiveLeaseGate(object : StubArchive() {
            override fun readBytes(path: String, maxBytes: Long): ByteArray {
                entered.countDown()
                release.await()
                return byteArrayOf(7)
            }

            override fun close() {
                delegateClosed.set(true)
            }
        })
        val readFinished = CountDownLatch(1)
        Thread {
            archive.readBytes("chapter.xhtml")
            readFinished.countDown()
        }.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val drained = CountDownLatch(1)

        archive.closeWhenDrained { drained.countDown() }

        assertFalse(delegateClosed.get())
        assertFalse(drained.await(50, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(readFinished.await(2, TimeUnit.SECONDS))
        assertTrue(drained.await(2, TimeUnit.SECONDS))
        assertTrue(delegateClosed.get())
    }

    @Test
    fun `stream keeps archive leased until stream close`() {
        val delegateClosed = AtomicBoolean(false)
        val archive = EpubArchiveLeaseGate(object : StubArchive() {
            override fun openStream(path: String): InputStream = ByteArrayInputStream(byteArrayOf(1, 2))

            override fun close() {
                delegateClosed.set(true)
            }
        })
        val stream = archive.openStream("image.png")
        val drained = CountDownLatch(1)

        archive.closeWhenDrained { drained.countDown() }
        assertFalse(delegateClosed.get())
        stream.close()

        assertTrue(drained.await(2, TimeUnit.SECONDS))
        assertTrue(delegateClosed.get())
        stream.close()
    }

    @Test
    fun `closing archive rejects new leases and closes once`() {
        var closes = 0
        val archive = EpubArchiveLeaseGate(object : StubArchive() {
            override fun close() {
                closes++
            }
        })
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)

        archive.closeWhenDrained { first.countDown() }
        archive.closeWhenDrained { second.countDown() }

        assertTrue(first.await(2, TimeUnit.SECONDS))
        assertTrue(second.await(2, TimeUnit.SECONDS))
        assertEquals(1, closes)
        assertThrows(IllegalStateException::class.java) { archive.exists("late") }
    }

    private open class StubArchive : EpubArchive {
        override fun exists(path: String): Boolean = true
        override fun list(): List<String> = emptyList()
        override fun readBytes(path: String, maxBytes: Long): ByteArray = ByteArray(0)
        override fun close() = Unit
    }
}
