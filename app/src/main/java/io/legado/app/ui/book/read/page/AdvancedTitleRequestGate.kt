package io.legado.app.ui.book.read.page

internal enum class AdvancedTitleSlot {
    PRIMARY,
    PAIR
}

internal data class AdvancedTitleRequestToken(
    val contentGeneration: Long,
    val requestGeneration: Long,
    val slot: AdvancedTitleSlot,
    val compositionKey: String,
    val sourceId: String
)

/** Rejects stale Lottie callbacks and tracks each title from load through first presentation. */
internal class AdvancedTitleRequestGate {

    var contentGeneration: Long = 0L
        private set

    private var requestGeneration: Long = 0L
    private val currentRequests = arrayOfNulls<AdvancedTitleRequestToken>(AdvancedTitleSlot.entries.size)
    private val readyRequests = arrayOfNulls<AdvancedTitleRequestToken>(AdvancedTitleSlot.entries.size)
    private val presentedRequests =
        arrayOfNulls<AdvancedTitleRequestToken>(AdvancedTitleSlot.entries.size)
    private val failedRequests =
        arrayOfNulls<AdvancedTitleRequestToken>(AdvancedTitleSlot.entries.size)

    fun nextContent(): Long {
        contentGeneration++
        currentRequests.fill(null)
        readyRequests.fill(null)
        presentedRequests.fill(null)
        failedRequests.fill(null)
        return contentGeneration
    }

    fun begin(
        slot: AdvancedTitleSlot,
        compositionKey: String,
        sourceId: String
    ): AdvancedTitleRequestToken {
        val token = AdvancedTitleRequestToken(
            contentGeneration = contentGeneration,
            requestGeneration = ++requestGeneration,
            slot = slot,
            compositionKey = compositionKey,
            sourceId = sourceId
        )
        currentRequests[slot.ordinal] = token
        readyRequests[slot.ordinal] = null
        presentedRequests[slot.ordinal] = null
        failedRequests[slot.ordinal] = null
        return token
    }

    fun clear(slot: AdvancedTitleSlot) {
        currentRequests[slot.ordinal] = null
        readyRequests[slot.ordinal] = null
        presentedRequests[slot.ordinal] = null
        failedRequests[slot.ordinal] = null
    }

    fun accepts(token: AdvancedTitleRequestToken): Boolean {
        return token.contentGeneration == contentGeneration &&
            currentRequests[token.slot.ordinal] == token
    }

    fun markReady(token: AdvancedTitleRequestToken): Boolean {
        if (!accepts(token)) return false
        readyRequests[token.slot.ordinal] = token
        presentedRequests[token.slot.ordinal] = null
        return true
    }

    fun markPresented(token: AdvancedTitleRequestToken): Boolean {
        if (!accepts(token) || readyRequests[token.slot.ordinal] != token) return false
        presentedRequests[token.slot.ordinal] = token
        return true
    }

    /** Records a current-request failure while keeping its identity for duplicate suppression. */
    fun fail(token: AdvancedTitleRequestToken): Boolean {
        if (!accepts(token)) return false
        readyRequests[token.slot.ordinal] = null
        presentedRequests[token.slot.ordinal] = null
        failedRequests[token.slot.ordinal] = token
        return true
    }

    /**
     * True once this slot's current request has genuinely failed. Callers use it to
     * decide whether the plain-text title may be painted; a slot that is merely still
     * loading must stay blank so the advanced title does not appear to replace it.
     */
    fun hasFailed(slot: AdvancedTitleSlot): Boolean {
        val token = failedRequests[slot.ordinal] ?: return false
        return accepts(token)
    }

    fun readyToken(slot: AdvancedTitleSlot): AdvancedTitleRequestToken? {
        val token = readyRequests[slot.ordinal] ?: return null
        return token.takeIf(::accepts)
    }

    fun presentedToken(slot: AdvancedTitleSlot): AdvancedTitleRequestToken? {
        val token = presentedRequests[slot.ordinal] ?: return null
        return token.takeIf(::accepts)
    }

    fun hasCurrent(
        slot: AdvancedTitleSlot,
        compositionKey: String,
        sourceId: String
    ): Boolean {
        val token = currentRequests[slot.ordinal] ?: return false
        return token.contentGeneration == contentGeneration &&
            token.compositionKey == compositionKey &&
            token.sourceId == sourceId
    }
}
