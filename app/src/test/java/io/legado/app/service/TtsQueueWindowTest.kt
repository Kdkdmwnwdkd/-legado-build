package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsQueueWindowTest {

    private val token = TtsQueueToken(sessionId = 7L, generation = 11L)

    @Test
    fun `queue is bounded and completion opens one slot`() {
        val queue = TtsQueueWindow(capacity = 4)
        queue.reset(token, startCueIndex = 0)

        val reservations = List(4) { queue.reserve(token, cueCount = 10) { true }!! }

        assertEquals(listOf(0, 1, 2, 3), reservations.map { it.cueIndex })
        assertTrue(reservations.first().flush)
        assertTrue(reservations.drop(1).none { it.flush })
        assertEquals(4, queue.queuedCount)
        assertNull(queue.reserve(token, cueCount = 10) { true })

        assertTrue(queue.complete(token, cueIndex = 0))
        assertEquals(4, queue.reserve(token, cueCount = 10) { true }!!.cueIndex)
        assertEquals(4, queue.queuedCount)
    }

    @Test
    fun `unreadable cues do not consume queue capacity`() {
        val queue = TtsQueueWindow(capacity = 3)
        queue.reset(token, startCueIndex = 1)

        val reservations = List(3) {
            queue.reserve(token, cueCount = 8) { index -> index % 2 == 0 }!!
        }

        assertEquals(listOf(2, 4, 6), reservations.map { it.cueIndex })
        assertEquals(3, queue.queuedCount)
    }

    @Test
    fun `stale generation cannot complete or refill a reset queue`() {
        val queue = TtsQueueWindow(capacity = 2)
        queue.reset(token, startCueIndex = 0)
        queue.reserve(token, cueCount = 5) { true }

        val resumed = token.copy(generation = token.generation + 1)
        queue.reset(resumed, startCueIndex = 3)

        assertFalse(queue.complete(token, cueIndex = 0))
        assertNull(queue.reserve(token, cueCount = 5) { true })
        assertEquals(3, queue.reserve(resumed, cueCount = 5) { true }!!.cueIndex)
        assertEquals(1, queue.queuedCount)
    }

    @Test
    fun `clear rejects callbacks from paused playback`() {
        val queue = TtsQueueWindow(capacity = 2)
        queue.reset(token, startCueIndex = 0)
        queue.reserve(token, cueCount = 2) { true }

        queue.clear()

        assertFalse(queue.isActive(token))
        assertFalse(queue.complete(token, cueIndex = 0))
        assertNull(queue.reserve(token, cueCount = 2) { true })
        assertEquals(0, queue.queuedCount)
    }

    @Test
    fun `queue reports exhaustion when no readable cue remains`() {
        val queue = TtsQueueWindow(capacity = 2)
        queue.reset(token, startCueIndex = 2)

        assertNull(queue.reserve(token, cueCount = 5) { false })
        assertTrue(queue.exhausted)
        assertEquals(0, queue.queuedCount)
    }
}
