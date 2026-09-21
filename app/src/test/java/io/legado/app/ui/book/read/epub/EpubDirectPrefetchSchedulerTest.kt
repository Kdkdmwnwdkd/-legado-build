package io.legado.app.ui.book.read.epub

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDirectPrefetchSchedulerTest {
    private class Reader(scope: CoroutineScope) {
        var clock = 0L
        val scheduler = EpubDirectPrefetchScheduler<String>(scope, { clock }, Dispatchers.Unconfined)
        val warm = mutableSetOf<Int>()
        val delivered = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()

        fun schedule(
            candidates: List<Int> = listOf(2),
            owner: String = "book-a:font-a",
            label: String = "current",
            prepare: suspend (Int) -> String
        ) = scheduler.schedule(
            owner = owner,
            candidates = candidates,
            staggerMillis = 0,
            isPrepared = { it in warm },
            prepare = prepare,
            onPrepared = { index, value -> warm.add(index); delivered.add("$label:$value") },
            onFailure = { _, error -> errors.add(error) }
        )
    }

    @Test fun `page notifications keep a slow next chapter alive and use the latest callback`() = runBlocking {
        withTimeout(5_000) {
            val reader = Reader(this)
            val entered = CompletableDeferred<Unit>()
            val response = CompletableDeferred<String>()
            var downloads = 0
            reader.schedule(label = "before-render") {
                downloads++
                entered.complete(Unit)
                response.await()
            }
            entered.await()
            repeat(10) {
                reader.schedule(label = "after-render") { error("must join the first preparation") }
                yield()
            }
            assertEquals(1, downloads)
            response.complete("chapter-2")
            repeat(3) { yield() }
            assertEquals(listOf("after-render:chapter-2"), reader.delivered)
            assertTrue(reader.errors.isEmpty())
            reader.scheduler.cancel()
        }
    }

    @Test fun `directory jumps cancel obsolete work and ignore a late old response`() = runBlocking {
        withTimeout(5_000) {
            val reader = Reader(this)
            val entered = CompletableDeferred<Unit>()
            val oldResponse = CompletableDeferred<String>()
            reader.schedule(candidates = listOf(2, 0)) { index ->
                if (index == 2) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { oldResponse.await() }
                } else "chapter-$index"
            }
            entered.await()
            repeat(3) { yield() }
            reader.delivered.clear()
            reader.schedule(candidates = listOf(21, 19)) { "chapter-$it" }
            repeat(3) { yield() }
            oldResponse.complete("obsolete")
            repeat(3) { yield() }
            assertEquals(listOf("current:chapter-21", "current:chapter-19"), reader.delivered)
            assertTrue(reader.errors.isEmpty())
            reader.scheduler.cancel()
        }
    }

    @Test fun `a new book or font cannot receive an old preparation for the same index`() = runBlocking {
        withTimeout(5_000) {
            val reader = Reader(this)
            val entered = CompletableDeferred<Unit>()
            val oldResponse = CompletableDeferred<String>()
            reader.schedule {
                entered.complete(Unit)
                withContext(NonCancellable) { oldResponse.await() }
            }
            entered.await()
            reader.schedule(owner = "book-b:font-b") { "new-layout" }
            repeat(3) { yield() }
            oldResponse.complete("old-layout")
            repeat(3) { yield() }
            assertEquals(listOf("current:new-layout"), reader.delivered)
            reader.scheduler.cancel()
        }
    }

    @Test fun `a foreground handoff survives cancellation of the remaining prefetch plan`() = runBlocking {
        withTimeout(5_000) {
            val reader = Reader(this)
            val entered = CompletableDeferred<Unit>()
            val response = CompletableDeferred<String>()
            val finished = CompletableDeferred<String>()
            reader.schedule {
                entered.complete(Unit)
                response.await().also { finished.complete(it) }
            }
            entered.await()
            reader.scheduler.handoff(2)
            reader.scheduler.cancel()
            response.complete("foreground-result")
            assertEquals("foreground-result", finished.await())
            repeat(3) { yield() }
            assertTrue(reader.delivered.isEmpty())
            assertTrue(reader.errors.isEmpty())
        }
    }

    @Test fun `failed preloads wait before retrying and recover while staying in the chapter`() = runBlocking {
        val reader = Reader(this)
        var downloads = 0
        reader.schedule { downloads++; error("temporary network failure") }
        repeat(3) { yield() }
        repeat(10) { reader.schedule { downloads++; "too-early" } }
        repeat(3) { yield() }
        assertEquals(1, downloads)
        reader.clock = 3_000
        reader.schedule { downloads++; "recovered" }
        repeat(3) { yield() }
        assertEquals(2, downloads)
        assertEquals(listOf("current:recovered"), reader.delivered)
        assertEquals(1, reader.errors.size)
        reader.scheduler.cancel()
    }

    @Test fun `evicted warm chapters refill without restarting an available neighbour`() = runBlocking {
        val reader = Reader(this)
        var downloads = 0
        reader.schedule(candidates = listOf(2, 0)) { downloads++; "chapter-$it" }
        repeat(3) { yield() }
        assertEquals(setOf(2, 0), reader.warm)
        reader.warm.remove(2)
        reader.clock = 5_000
        reader.schedule(candidates = listOf(2, 0)) { downloads++; "chapter-$it" }
        repeat(3) { yield() }
        assertEquals(3, downloads)
        assertEquals(2, reader.delivered.count { it == "current:chapter-2" })
        assertEquals(1, reader.delivered.count { it == "current:chapter-0" })
        reader.scheduler.cancel()
    }

    @Test fun `empty and single slot plans never start extra chapter downloads`() = runBlocking {
        val reader = Reader(this)
        var downloads = 0
        reader.schedule(candidates = emptyList()) { downloads++; "unexpected" }
        repeat(3) { yield() }
        assertEquals(0, downloads)
        val candidates = EpubDirectPrefetchPolicy.candidates(5, 20, capacity = 1)
        reader.schedule(candidates) { downloads++; "chapter-$it" }
        repeat(3) { yield() }
        assertEquals(1, downloads)
        assertTrue(6 in reader.warm)
        assertFalse(4 in reader.warm)
        reader.scheduler.cancel()
    }

    @Test fun `distant preparations start on separate stagger steps`() = runBlocking {
        withTimeout(5_000) {
            val gates = listOf(80L, 160L, 240L).associateWith { CompletableDeferred<Unit>() }
            val waits = mutableListOf<Long>()
            val started = mutableListOf<Int>()
            val delivered = mutableListOf<Int>()
            val scheduler = EpubDirectPrefetchScheduler<Int>(
                this, { 0L }, Dispatchers.Unconfined,
                waitBeforePreparation = { millis -> waits.add(millis); gates.getValue(millis).await() }
            )
            scheduler.schedule(
                owner = "book", candidates = listOf(5, 3, 6, 2), staggerMillis = 80L,
                isPrepared = { false }, prepare = { started.add(it); it },
                onPrepared = { _, result -> delivered.add(result) },
                onFailure = { _, error -> throw error }
            )
            repeat(3) { yield() }
            assertEquals(listOf(5), started)
            assertEquals(listOf(80L, 160L, 240L), waits)
            gates.getValue(80L).complete(Unit)
            repeat(3) { yield() }
            assertEquals(listOf(5, 3), delivered)
            gates.getValue(160L).complete(Unit)
            repeat(3) { yield() }
            assertEquals(listOf(5, 3, 6), delivered)
            scheduler.cancel()
            gates.getValue(240L).complete(Unit)
            repeat(3) { yield() }
            assertEquals(listOf(5, 3, 6), started)
        }
    }
}
