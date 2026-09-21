package io.legado.app.model.localBook.epubcore.archive

import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

class EpubArchiveLeaseGate(
    private val delegate: EpubArchive
) : EpubArchive {

    private var activeLeases = 0
    private var closing = false
    private var closeClaimed = false
    private var drained = false
    private var closeFailure: Throwable? = null
    private val drainCallbacks = arrayListOf<(Throwable?) -> Unit>()

    override fun exists(path: String): Boolean = withLease { delegate.exists(path) }

    override fun list(): List<String> = withLease { delegate.list() }

    override fun canonicalPath(path: String): String? = withLease { delegate.canonicalPath(path) }

    override fun readBytes(path: String, maxBytes: Long): ByteArray {
        return withLease { delegate.readBytes(path, maxBytes) }
    }

    override fun entrySize(path: String): Long? = withLease { delegate.entrySize(path) }

    override fun openStream(path: String): InputStream {
        acquireLease()
        val stream = try {
            delegate.openStream(path)
        } catch (throwable: Throwable) {
            releaseLease()
            throw throwable
        }
        return object : FilterInputStream(stream) {
            private val released = AtomicBoolean(false)

            override fun close() {
                try {
                    super.close()
                } finally {
                    if (released.compareAndSet(false, true)) releaseLease()
                }
            }
        }
    }

    override fun close() {
        closeWhenDrained {}
    }

    fun closeWhenDrained(onDrained: (Throwable?) -> Unit) {
        var invokeImmediately = false
        val closeNow = synchronized(this) {
            if (drained) {
                invokeImmediately = true
                false
            } else {
                drainCallbacks += onDrained
                closing = true
                claimCloseLocked()
            }
        }
        if (invokeImmediately) {
            onDrained(closeFailure)
        } else if (closeNow) {
            closeDelegate()
        }
    }

    private inline fun <T> withLease(block: () -> T): T {
        acquireLease()
        return try {
            block()
        } finally {
            releaseLease()
        }
    }

    private fun acquireLease() {
        synchronized(this) {
            check(!closing) { "EPUB archive is closing" }
            activeLeases++
        }
    }

    private fun releaseLease() {
        val closeNow = synchronized(this) {
            check(activeLeases > 0) { "EPUB archive lease underflow" }
            activeLeases--
            claimCloseLocked()
        }
        if (closeNow) closeDelegate()
    }

    private fun claimCloseLocked(): Boolean {
        if (!closing || activeLeases > 0 || closeClaimed) return false
        closeClaimed = true
        return true
    }

    private fun closeDelegate() {
        val failure = runCatching { delegate.close() }.exceptionOrNull()
        val callbacks = synchronized(this) {
            closeFailure = failure
            drained = true
            drainCallbacks.toList().also { drainCallbacks.clear() }
        }
        callbacks.forEach { callback -> runCatching { callback(failure) } }
    }
}
