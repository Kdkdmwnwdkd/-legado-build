package io.legado.app.model

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * Serializes task operations for one book while allowing unrelated books to
 * proceed independently.  The gate is deliberately small; callers own the
 * actual refresh/cache work and cancellation remains structured.
 */
object AutoTaskBookOperationGate {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withBook(bookUrl: String, block: suspend () -> T): T {
        val normalized = bookUrl.trim()
        require(normalized.isNotEmpty()) { "bookUrl is blank" }
        val lock = locks.computeIfAbsent(normalized) { Mutex() }
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}
