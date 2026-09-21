package io.legado.app.service

internal data class TtsQueueToken(
    val sessionId: Long,
    val generation: Long
)

internal data class TtsQueueReservation(
    val cueIndex: Int,
    val flush: Boolean
)

/**
 * Tracks the small set of utterances submitted to Android's TTS engine.
 *
 * The caller owns synchronization. A token makes callbacks from an older playback session or
 * generation harmless after pause, seek, engine reset, or chapter changes.
 */
internal class TtsQueueWindow(private val capacity: Int) {

    init {
        require(capacity > 0)
    }

    private var activeToken: TtsQueueToken? = null
    private var nextCueIndex = 0
    private var started = false
    private val queuedCueIndexes = linkedSetOf<Int>()

    var exhausted: Boolean = false
        private set

    val queuedCount: Int
        get() = queuedCueIndexes.size

    fun reset(token: TtsQueueToken, startCueIndex: Int) {
        activeToken = token
        nextCueIndex = startCueIndex.coerceAtLeast(0)
        started = false
        exhausted = false
        queuedCueIndexes.clear()
    }

    fun clear() {
        activeToken = null
        nextCueIndex = 0
        started = false
        exhausted = false
        queuedCueIndexes.clear()
    }

    fun isActive(token: TtsQueueToken): Boolean = activeToken == token

    fun reserve(
        token: TtsQueueToken,
        cueCount: Int,
        isReadable: (Int) -> Boolean
    ): TtsQueueReservation? {
        if (!isActive(token) || queuedCueIndexes.size >= capacity) return null
        while (nextCueIndex < cueCount) {
            val cueIndex = nextCueIndex++
            if (!isReadable(cueIndex)) continue
            val reservation = TtsQueueReservation(cueIndex, flush = !started)
            started = true
            queuedCueIndexes.add(cueIndex)
            return reservation
        }
        exhausted = true
        return null
    }

    fun complete(token: TtsQueueToken, cueIndex: Int): Boolean {
        return isActive(token) && queuedCueIndexes.remove(cueIndex)
    }
}
