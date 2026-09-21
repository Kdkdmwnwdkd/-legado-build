package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDirectAutoPagerTest {

    @Test
    fun `accepted turn waits for a different committed page before scheduling again`() {
        val scheduler = TestScheduler()
        var requests = 0
        val pager = EpubDirectAutoPager(
            postDelayed = scheduler::postDelayed,
            removeCallbacks = scheduler::removeCallbacks,
            intervalMillis = { 4_000L },
            requestNextPage = {
                requests++
                EpubPageTurnResult.MovedWithinChapter
            },
            onStopped = {}
        )

        assertTrue(pager.start(EpubDirectAutoPager.Position(3, 4)))
        assertEquals(4_000L, scheduler.delayMillis)
        scheduler.runPending()

        assertEquals(1, requests)
        assertEquals(EpubDirectAutoPager.State.WaitingForCommit, pager.state)
        assertEquals(EpubDirectAutoPager.WAITING_COMMIT_TIMEOUT_MS, scheduler.nextDelayMillis)

        pager.onPageCommitted(EpubDirectAutoPager.Position(3, 4))
        assertTrue(scheduler.hasPending)
        pager.onPageCommitted(EpubDirectAutoPager.Position(3, 5))

        assertEquals(EpubDirectAutoPager.State.Running, pager.state)
        assertEquals(4_000L, scheduler.delayMillis)
    }

    @Test
    fun `chapter boundary commit restarts one timer and never queues a second request`() {
        val scheduler = TestScheduler()
        var requests = 0
        val pager = EpubDirectAutoPager(
            postDelayed = scheduler::postDelayed,
            removeCallbacks = scheduler::removeCallbacks,
            intervalMillis = { 2_000L },
            requestNextPage = {
                requests++
                EpubPageTurnResult.BoundaryRequired
            },
            onStopped = {}
        )

        pager.start(EpubDirectAutoPager.Position(8, 11))
        scheduler.runPending()
        pager.run()
        assertEquals(1, requests)

        pager.onPageCommitted(EpubDirectAutoPager.Position(9, 0))
        assertEquals(EpubDirectAutoPager.State.Running, pager.state)
        assertTrue(scheduler.hasPending)
    }

    @Test
    fun `pause holds a completed turn until resume`() {
        val scheduler = TestScheduler()
        val pager = EpubDirectAutoPager(
            postDelayed = scheduler::postDelayed,
            removeCallbacks = scheduler::removeCallbacks,
            intervalMillis = { 3_000L },
            requestNextPage = { EpubPageTurnResult.MovedWithinChapter },
            onStopped = {}
        )

        pager.start(EpubDirectAutoPager.Position(1, 0))
        scheduler.runPending()
        pager.pause()
        assertEquals(EpubDirectAutoPager.State.Running, pager.state)
        pager.onPageCommitted(EpubDirectAutoPager.Position(1, 1))

        assertEquals(EpubDirectAutoPager.State.Running, pager.state)
        assertFalse(scheduler.hasPending)

        pager.resume()
        assertEquals(3_000L, scheduler.delayMillis)
    }

    @Test
    fun `rejected turn stops instead of polling or waiting forever`() {
        val scheduler = TestScheduler()
        var stopped = 0
        val pager = EpubDirectAutoPager(
            postDelayed = scheduler::postDelayed,
            removeCallbacks = scheduler::removeCallbacks,
            intervalMillis = { 0L },
            requestNextPage = { EpubPageTurnResult.Rejected },
            onStopped = { stopped++ }
        )

        pager.start(EpubDirectAutoPager.Position(0, 0))
        assertEquals(1_000L, scheduler.delayMillis)
        scheduler.runPending()

        assertEquals(EpubDirectAutoPager.State.Stopped, pager.state)
        assertEquals(1, stopped)
        assertFalse(scheduler.hasPending)
    }

    @Test
    fun `accepted turn stops when its commit never arrives`() {
        val scheduler = TestScheduler()
        var stopped = 0
        val pager = EpubDirectAutoPager(
            postDelayed = scheduler::postDelayed,
            removeCallbacks = scheduler::removeCallbacks,
            intervalMillis = { 1_000L },
            requestNextPage = { EpubPageTurnResult.Queued },
            onStopped = { stopped++ }
        )

        pager.start(EpubDirectAutoPager.Position(2, 7))
        scheduler.runPending()
        assertEquals(EpubDirectAutoPager.State.WaitingForCommit, pager.state)

        scheduler.runPending()

        assertEquals(EpubDirectAutoPager.State.Stopped, pager.state)
        assertEquals(1, stopped)
        assertFalse(scheduler.hasPending)
    }

    @Test
    fun `cancelled turn restarts exactly one interval`() {
        val scheduler = TestScheduler()
        val pager = EpubDirectAutoPager(
            postDelayed = scheduler::postDelayed,
            removeCallbacks = scheduler::removeCallbacks,
            intervalMillis = { 2_500L },
            requestNextPage = { EpubPageTurnResult.BoundaryRequired },
            onStopped = {}
        )

        pager.start(EpubDirectAutoPager.Position(5, 9))
        scheduler.runPending()
        pager.onTurnCancelled()

        assertEquals(EpubDirectAutoPager.State.Running, pager.state)
        assertEquals(2_500L, scheduler.nextDelayMillis)
        assertEquals(1, scheduler.pendingCount)
    }

    private class TestScheduler {
        private val pending = linkedMapOf<Runnable, Long>()
        val delayMillis: Long
            get() = nextDelayMillis
        val nextDelayMillis: Long
            get() = pending.values.minOrNull() ?: -1L
        val pendingCount: Int
            get() = pending.size
        val hasPending: Boolean
            get() = pending.isNotEmpty()

        fun postDelayed(runnable: Runnable, delayMillis: Long) {
            pending[runnable] = delayMillis
        }

        fun removeCallbacks(runnable: Runnable) {
            pending.remove(runnable)
        }

        fun runPending() {
            val runnable = pending.minByOrNull { it.value }?.key ?: return
            pending.remove(runnable)
            runnable.run()
        }
    }
}
