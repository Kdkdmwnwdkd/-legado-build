package io.legado.app.model.localBook.epubcore.template

/** One source action per recent native tap; author postMessage calls are not user gestures. */
internal class EpubTemplateTapGate {
    private var token = -1L
    private var tappedAt: Long? = null

    fun reset() { token = -1L; tappedAt = null }

    fun record(token: Long, now: Long) {
        this.token = token
        tappedAt = now
    }

    fun consume(token: Long, now: Long): Boolean {
        val tappedAt = tappedAt ?: return false
        if (token != this.token || now - tappedAt !in 0L..1_500L) return false
        reset()
        return true
    }
}
