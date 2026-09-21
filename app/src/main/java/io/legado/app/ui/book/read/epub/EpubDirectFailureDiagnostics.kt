package io.legado.app.ui.book.read.epub

internal object EpubDirectFailureDiagnostics {

    private const val MAX_CAUSE_DEPTH = 12

    fun userMessage(fallback: String, throwable: Throwable?): String {
        val chain = causeChain(throwable)
        if (chain.isEmpty()) return fallback
        val wrapper = chain.first()
        val root = chain.last()
        val rootSummary = summary(root)
        val message = if (wrapper === root) {
            rootSummary
        } else {
            "$rootSummary\nWrapped by ${summary(wrapper)}"
        }
        val initializationSite = initializationSite(chain)
        return if (initializationSite == null) message else "$message\nAt $initializationSite"
    }

    fun logCauseChain(throwable: Throwable?): String {
        val chain = causeChain(throwable)
        return if (chain.isEmpty()) {
            "none"
        } else {
            chain.joinToString(" <- ", transform = ::summary)
        }
    }

    internal fun causeChain(throwable: Throwable?): List<Throwable> {
        val chain = ArrayList<Throwable>(MAX_CAUSE_DEPTH)
        var current = throwable
        while (current != null && chain.size < MAX_CAUSE_DEPTH && chain.none { it === current }) {
            chain.add(current)
            current = nextCause(current)
        }
        return chain
    }

    private fun nextCause(throwable: Throwable): Throwable? {
        throwable.cause?.let { return it }
        return (throwable as? ExceptionInInitializerError)?.exception
    }

    private fun summary(throwable: Throwable): String {
        val message = throwable.message?.trim().orEmpty()
        return if (message.isEmpty()) throwable.javaClass.name else "${throwable.javaClass.name}: $message"
    }

    private fun initializationSite(chain: List<Throwable>): StackTraceElement? {
        val initializationFailure = chain.firstOrNull {
            it is ExceptionInInitializerError ||
                it is NoClassDefFoundError && it.message?.contains("initialize", ignoreCase = true) == true
        } ?: return null
        return chain.asSequence()
            .flatMap { it.stackTrace.asSequence() }
            .firstOrNull { it.methodName == "<clinit>" }
            ?: initializationFailure.stackTrace.firstOrNull()
    }
}
