package io.legado.app.model.localBook.epubcore.template

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTemplateOperationQueueTest {
    @Test
    fun deliveredImportWaitsForInitialLoadWithoutAnIdleGap() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val releaseLoad = CompletableDeferred<Unit>()
        val probe = Probe(owner)
        val order = arrayListOf<String>()
        try {
            assertTrue(probe.queue.submit {
                order.add("loading")
                releaseLoad.await()
                order.add("loaded")
            })
            assertTrue(probe.queue.submit(enqueue = true) { order.add("imported") })
            assertEquals(listOf("loading"), order)
            assertEquals(listOf(true), probe.busyChanges)
            assertEquals(listOf("start"), probe.callbacks)

            releaseLoad.complete(Unit)
            withTimeout(5_000) { probe.idle.await() }

            assertEquals(listOf("loading", "loaded", "imported"), order)
            assertEquals(listOf(true, false), probe.busyChanges)
            assertEquals(listOf("start", "start"), probe.callbacks)
            assertTrue(probe.errors.isEmpty())
        } finally {
            releaseLoad.complete(Unit)
            owner.cancel()
        }
    }

    @Test
    fun repeatedUserActionsAreRejectedUntilAcceptedWorkFinishes() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val releaseSave = CompletableDeferred<Unit>()
        val probe = Probe(owner)
        var writes = 0
        try {
            assertTrue(probe.queue.submit { releaseSave.await(); writes++ })
            assertFalse(probe.queue.submit { writes++ })
            assertFalse(probe.queue.submit { writes++ })
            assertEquals(0, writes)
            assertEquals(listOf("start"), probe.callbacks)

            releaseSave.complete(Unit)
            withTimeout(5_000) { probe.idle.await() }
            assertEquals(1, writes)
            assertTrue(probe.queue.submit { writes++ })
            assertEquals(2, writes)
            assertEquals(listOf(true, false, true, false), probe.busyChanges)
        } finally {
            releaseSave.complete(Unit)
            owner.cancel()
        }
    }

    @Test
    fun queuedFileResultSurvivesOwnerCancellationWhileWaitingForACommit() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val releaseCommit = CompletableDeferred<Unit>()
        val probe = Probe(owner)
        val order = arrayListOf<String>()
        try {
            assertTrue(probe.queue.submit {
                withContext(NonCancellable) {
                    order.add("saving")
                    releaseCommit.await()
                    order.add("saved")
                }
            })
            assertTrue(probe.queue.submit(enqueue = true) { order.add("imported") })
            owner.cancel()
            assertFalse(owner.isActive)
            assertEquals(listOf("saving"), order)
            assertEquals(listOf(true), probe.busyChanges)

            releaseCommit.complete(Unit)
            withTimeout(5_000) { probe.idle.await() }

            assertEquals(listOf("saving", "saved", "imported"), order)
            assertEquals(listOf(true, false), probe.busyChanges)
            assertTrue(probe.errors.isEmpty())
        } finally {
            releaseCommit.complete(Unit)
            owner.cancel()
        }
    }

    @Test
    fun failedLoadReportsBeforeTheQueuedImportStartsAndDoesNotDropIt() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val releaseLoad = CompletableDeferred<Unit>()
        val failure = IllegalArgumentException("invalid saved library")
        val probe = Probe(owner)
        var imported = false
        try {
            assertTrue(probe.queue.submit { releaseLoad.await(); throw failure })
            assertTrue(probe.queue.submit(enqueue = true) { imported = true })
            releaseLoad.complete(Unit)
            withTimeout(5_000) { probe.idle.await() }

            assertTrue(imported)
            assertSame(failure, probe.errors.single())
            assertEquals(listOf("start", "error", "start"), probe.callbacks)
            assertEquals(listOf(true, false), probe.busyChanges)
            assertTrue(owner.isActive)
        } finally {
            releaseLoad.complete(Unit)
            owner.cancel()
        }
    }

    @Test
    fun cancellationOfOrdinaryLoadingReleasesTheNextAcceptedResult() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val neverLoaded = CompletableDeferred<Unit>()
        val probe = Probe(owner)
        var imported = false
        try {
            assertTrue(probe.queue.submit { neverLoaded.await() })
            assertTrue(probe.queue.submit(enqueue = true) { imported = true })
            owner.cancel()
            withTimeout(5_000) { probe.idle.await() }

            assertTrue(imported)
            assertTrue(probe.errors.isEmpty())
            assertEquals(listOf(true, false), probe.busyChanges)
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun clearedOwnerRejectsNewUiWorkButCanFinishAnAlreadyDeliveredFileResult() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val probe = Probe(owner)
        var executed = 0
        owner.cancel()

        assertFalse(probe.queue.submit { executed++ })
        assertTrue(probe.queue.submit(enqueue = true) { executed++ })
        withTimeout(5_000) { probe.idle.await() }

        assertEquals(1, executed)
        assertEquals(listOf(true, false), probe.busyChanges)
        assertTrue(probe.errors.isEmpty())
    }

    private class Probe(scope: CoroutineScope) {
        val busyChanges = arrayListOf<Boolean>()
        val callbacks = arrayListOf<String>()
        val errors = arrayListOf<Throwable>()
        val idle = CompletableDeferred<Unit>()
        val queue = ReaderTemplateOperationQueue(
            scope = scope,
            onBusyChanged = { busy ->
                busyChanges.add(busy)
                if (!busy) idle.complete(Unit)
            },
            onStart = { callbacks.add("start") },
            onError = { error -> errors.add(error); callbacks.add("error") }
        )
    }
}
