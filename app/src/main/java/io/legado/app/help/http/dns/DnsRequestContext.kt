package io.legado.app.help.http.dns

/** Only for synchronous URLConnection/Jsoup calls on the current thread.
 * Never keep this context across a suspension or hand it to a worker thread. */
object DnsRequestContext {
    private val current = ThreadLocal<DnsScope>()
    val scope: DnsScope? get() = current.get()

    fun <T> withScope(scope: DnsScope, block: () -> T): T {
        val previous = current.get()
        current.set(scope)
        return try { block() } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}
