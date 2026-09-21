package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubPageTurnQueueTest {

    @Test
    fun `peek does not consume a busy turn`() {
        val queue = EpubPageTurnQueue(maxRuns = 2)

        assertTrue(queue.enqueue(1))
        assertEquals(1, queue.peekDirection())
        assertEquals(1, queue.peekDirection())
        assertFalse(queue.isEmpty)
    }

    @Test
    fun `handled turns are consumed exactly once`() {
        val queue = EpubPageTurnQueue(maxRuns = 2)

        assertTrue(queue.enqueue(1))
        assertTrue(queue.enqueue(1))
        assertEquals(1, queue.runCount)

        assertTrue(queue.markHeadHandled())
        assertEquals(1, queue.peekDirection())
        assertTrue(queue.markHeadHandled())
        assertNull(queue.peekDirection())
        assertFalse(queue.markHeadHandled())
    }

    @Test
    fun `direction changes preserve fifo order`() {
        val queue = EpubPageTurnQueue(maxRuns = 3)

        assertTrue(queue.enqueue(1))
        assertTrue(queue.enqueue(-1))
        assertEquals(2, queue.runCount)
        assertEquals(1, queue.peekDirection())
        queue.markHeadHandled()
        assertEquals(-1, queue.peekDirection())
    }

    @Test
    fun `full queue rejects only a new run`() {
        val queue = EpubPageTurnQueue(maxRuns = 1)

        assertTrue(queue.enqueue(1))
        assertTrue(queue.enqueue(1))
        assertFalse(queue.enqueue(-1))
        assertEquals(1, queue.runCount)
        assertEquals(1, queue.peekDirection())
    }

    @Test
    fun `same direction turns have a total pending limit`() {
        val queue = EpubPageTurnQueue(maxRuns = 2, maxTurns = 2)

        assertTrue(queue.enqueue(1))
        assertTrue(queue.enqueue(1))
        assertFalse(queue.enqueue(1))
        assertTrue(queue.markHeadHandled())
        assertTrue(queue.enqueue(-1))
    }
}
