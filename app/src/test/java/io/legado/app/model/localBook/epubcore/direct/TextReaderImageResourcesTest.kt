package io.legado.app.model.localBook.epubcore.direct

import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TextReaderImageResourcesTest {
    private fun image(value: String = "12") = TextReaderImageResource.bytes(
        "<svg xmlns='http://www.w3.org/2000/svg'><text>$value</text></svg>".toByteArray()
    )
    private fun state(store: TextReaderImageResources, key: String, start: Boolean = true): String =
        store.state(key, start).stream.reader().use { it.readText() }
    private suspend fun ready(store: TextReaderImageResources, key: String) = withTimeout(5000) {
        while (!state(store, key).contains("ready")) delay(5)
    }

    @Test fun `ready status distinguishes software bubble sizes from ordinary source images`() = runBlocking {
        val owner = SupervisorJob()
        val bytes = "<svg xmlns='http://www.w3.org/2000/svg'><text>12</text></svg>".toByteArray()
        val store = TextReaderImageResources(owner, { true }, load = { key ->
            val scale = key.toFloatOrNull()
            TextReaderImageResource.bytes(bytes, scale = scale ?: 1f, isBubble = scale != null)
        })
        try {
            for (scale in listOf(.5f, 1f, 1.5f)) {
                val key = scale.toString()
                ready(store, key)
                val status = JsonParser.parseString(state(store, key, start = false)).asJsonObject
                assertTrue(status["bubble"].asBoolean)
                assertEquals(scale, status["scale"].asFloat, 0f)
                assertArrayEquals(bytes, store.resource(key)!!.stream.use { it.readBytes() })
            }
            ready(store, "ordinary")
            val ordinary = JsonParser.parseString(state(store, "ordinary")).asJsonObject
            assertFalse(ordinary["bubble"].asBoolean)
            assertEquals(1f, ordinary["scale"].asFloat, 0f)
            assertArrayEquals(bytes, store.resource("ordinary")!!.stream.use { it.readBytes() })
        } finally { store.close(); owner.cancel() }
    }

    @Test fun `image interception returns before a source rule needs the same WebView callback thread`() {
        val callbackThread = Executors.newSingleThreadExecutor()
        val owner = SupervisorJob()
        val imageStarted = CountDownLatch(1)
        val allowScript = CountDownLatch(1)
        val store = TextReaderImageResources(owner, { true }, load = {
            imageStarted.countDown()
            allowScript.await(3, TimeUnit.SECONDS)
            // Model a source loading rule that asks a WebView to perform work.
            // Synchronously downloading inside interception deadlocks this queue.
            callbackThread.submit<TextReaderImageResource> { image() }.get(3, TimeUnit.SECONDS)
        })
        try {
            val intercepted = callbackThread.submit<String> { state(store, "book-a/image") }
            assertTrue(intercepted.get(1, TimeUnit.SECONDS).contains("pending"))
            assertTrue(imageStarted.await(1, TimeUnit.SECONDS))
            val nextChapter = callbackThread.submit<String> { "book-b 正文" }
            assertEquals("book-b 正文", nextChapter.get(1, TimeUnit.SECONDS))
            allowScript.countDown()
            runBlocking { ready(store, "book-a/image") }
        } finally {
            allowScript.countDown(); store.close(); owner.cancel(); callbackThread.shutdownNow()
        }
    }

    @Test fun `closing book A cancels its pending image while B and reopened A can load`() = runBlocking {
        val firstOwner = SupervisorJob()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val first = TextReaderImageResources(firstOwner, { true }, load = {
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        })
        val nextOwner = SupervisorJob()
        val next = TextReaderImageResources(nextOwner, { true }, load = { image("book B") })
        val reopened = TextReaderImageResources(nextOwner, { true }, load = { image("reopened A") })
        try {
            state(first, "same-image")
            withTimeout(3000) { started.await() }
            first.close()
            withTimeout(3000) { cancelled.await() }
            assertTrue(state(first, "same-image").contains("failed"))
            assertNull(first.resource("same-image"))
            ready(next, "same-image"); ready(reopened, "same-image")
            assertTrue(next.resource("same-image")!!.stream.reader().use { it.readText() }.contains("book B"))
            assertTrue(reopened.resource("same-image")!!.stream.reader().use { it.readText() }.contains("reopened A"))
        } finally { first.close(); next.close(); reopened.close(); firstOwner.cancel(); nextOwner.cancel() }
    }

    @Test fun `duplicate polls coalesce and HEAD cannot start a download`() = runBlocking {
        val owner = SupervisorJob()
        val finish = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val store = TextReaderImageResources(owner, { true }, load = {
            calls.incrementAndGet(); finish.await(); image()
        })
        try {
            repeat(20) { assertTrue(state(store, "image", start = false).contains("pending")) }
            assertEquals(0, calls.get())
            assertNull(store.resource("image", headOnly = true))
            repeat(20) { state(store, "image") }
            withTimeout(3000) { while (calls.get() == 0) delay(5) }
            assertEquals(1, calls.get())
            finish.complete(Unit)
            ready(store, "image")
            assertEquals(-1, store.resource("image", headOnly = true)!!.stream.use { it.read() })
        } finally { store.close(); owner.cancel() }
    }

    @Test fun `timeouts and bad images are local failures and do not exhaust the next book`() = runBlocking {
        val owner = SupervisorJob()
        val store = TextReaderImageResources(owner, { true }, load = {
            when (it) {
                "slow" -> awaitCancellation()
                "bad" -> error("broken image")
                else -> image()
            }
        }, timeoutMs = 100)
        try {
            state(store, "slow"); state(store, "bad")
            withTimeout(3000) {
                while (!state(store, "slow").contains("failed") || !state(store, "bad").contains("failed")) delay(5)
            }
            ready(store, "new chapter")
            assertNotNull(store.resource("new chapter"))
        } finally { store.close(); owner.cancel() }
    }

    @Test fun `a stale chapter result is rejected even before session close`() = runBlocking {
        val owner = SupervisorJob()
        var current = true
        val finish = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val store = TextReaderImageResources(owner, { current }, load = {
            started.complete(Unit); finish.await(); image()
        })
        try {
            state(store, "image"); withTimeout(3000) { started.await() }
            current = false; finish.complete(Unit)
            assertTrue(state(store, "image").contains("failed"))
            assertNull(store.resource("image"))
        } finally { store.close(); owner.cancel() }
    }

    @Test fun `a ready image survives other completions until the WebView requests its body`() = runBlocking {
        val owner = SupervisorJob()
        val firstImage = image("image A")
        val secondImage = image("image B")
        val store = TextReaderImageResources(owner, { true }, load = {
            if (it == "a") firstImage else secondImage
        }, maxRetainedBytes = maxOf(firstImage.retainedBytes, secondImage.retainedBytes))
        try {
            ready(store, "a")
            // Both images cannot fit the cache budget. A has only received the
            // ready response; WebView has not yet issued its separate GET.
            ready(store, "b")
            val first = assertNotNullResource(store.resource("a"))
            assertTrue(first.stream.reader().use { it.readText() }.contains("image A"))
            assertNotNullResource(store.resource("b")).stream.close()
        } finally { store.close(); owner.cancel() }
    }

    @Test fun `a HEAD request does not consume the ready image handoff protection`() = runBlocking {
        val owner = SupervisorJob()
        val firstImage = image("image A")
        val secondImage = image("image B")
        val store = TextReaderImageResources(owner, { true }, load = {
            if (it == "a") firstImage else secondImage
        }, maxRetainedBytes = maxOf(firstImage.retainedBytes, secondImage.retainedBytes))
        try {
            ready(store, "a")
            assertEquals(-1, assertNotNullResource(store.resource("a", headOnly = true)).stream.use { it.read() })
            ready(store, "b")
            val first = assertNotNullResource(store.resource("a"))
            assertTrue(first.stream.reader().use { it.readText() }.contains("image A"))
        } finally { store.close(); owner.cancel() }
    }

    @Test fun `waiting for an image worker does not consume that image's loading timeout`() = runBlocking {
        val owner = SupervisorJob()
        val workersStarted = CountDownLatch(3)
        val releaseWorkers = CompletableDeferred<Unit>()
        val queuedLoads = AtomicInteger()
        val timeoutMs = 500L
        val store = TextReaderImageResources(owner, { true }, load = { path ->
            if (path.startsWith("busy-")) {
                workersStarted.countDown()
                // Model a decoder that must finish cleanup before releasing its
                // permit, even after its individual loading timeout has expired.
                withContext(NonCancellable) { releaseWorkers.await() }
            } else {
                queuedLoads.incrementAndGet()
            }
            image(path)
        }, timeoutMs = timeoutMs)
        try {
            repeat(3) { state(store, "busy-$it") }
            assertTrue(workersStarted.await(5, TimeUnit.SECONDS))
            state(store, "queued-image")
            delay(timeoutMs * 3)
            val queued = state(store, "queued-image", start = false)
            assertTrue("A waiting image must remain retryable: $queued", queued.contains("pending"))
            assertEquals(0, queuedLoads.get())
            releaseWorkers.complete(Unit)
            ready(store, "queued-image")
            assertEquals(1, queuedLoads.get())
            val loaded = assertNotNullResource(store.resource("queued-image"))
            assertTrue(loaded.stream.reader().use { it.readText() }.contains("queued-image"))
        } finally {
            releaseWorkers.complete(Unit)
            store.close()
            owner.cancel()
        }
    }

    private fun assertNotNullResource(resource: EpubDirectResource?): EpubDirectResource {
        assertNotNull("A ready image must remain available for the WebView GET", resource)
        return requireNotNull(resource)
    }

    @Test fun `cache eviction does not invalidate a response body already leased by a WebView`() = runBlocking {
        val owner = SupervisorJob()
        val store = TextReaderImageResources(owner, { true }, load = { image(it) }, maxEntries = 2)
        try {
            ready(store, "one")
            val first = store.resource("one")!!
            ready(store, "two"); ready(store, "three")
            assertNull(store.resource("one"))
            assertTrue(first.stream.reader().use { it.readText() }.contains("one"))
        } finally { store.close(); owner.cancel() }
    }
}
