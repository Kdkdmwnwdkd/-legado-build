package io.legado.app.model

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class AutoTaskBookOperationGateTest {

    @Test
    fun serializesOnlyOperationsForTheSameBook() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val activeByBook = ConcurrentHashMap<String, Int>()
        val maxActiveByBook = ConcurrentHashMap<String, Int>()
        suspend fun run(book: String, label: String) =
            AutoTaskBookOperationGate.withBook(book) {
                val active = activeByBook.merge(book, 1, Int::plus) ?: 1
                maxActiveByBook.compute(book) { _, current -> maxOf(current ?: 0, active) }
                events += "$label:start"
                delay(20)
                events += "$label:end"
                activeByBook.computeIfPresent(book) { _, current -> current - 1 }
            }

        val first = async { run("book", "a") }
        val second = async { run("book", "b") }
        val other = async { run("other", "c") }
        first.await()
        second.await()
        other.await()

        assertEquals(1, maxActiveByBook["book"])
        assertEquals(1, maxActiveByBook["other"])
        assertTrue(events.indexOf("a:end") < events.indexOf("b:start") ||
            events.indexOf("b:end") < events.indexOf("a:start"))
    }

    @Test
    fun rejectsBlankBookUrl() = runBlocking {
        try {
            AutoTaskBookOperationGate.withBook("  ") { Unit }
            throw AssertionError("blank URL should be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("blank"))
        }
    }
}
