package io.legado.app.model.localBook.epubcore.template

/** Main-thread clock for work which WebView suspends while its host is paused. */
internal class EpubTemplateActiveClock(private val uptimeMillis: () -> Long) {
    private var pausedAt: Long? = null
    private var pausedDuration = 0L

    fun now(): Long = (pausedAt ?: uptimeMillis()) - pausedDuration

    fun pause() {
        if (pausedAt == null) pausedAt = uptimeMillis()
    }

    fun resume() {
        val started = pausedAt ?: return
        pausedDuration += (uptimeMillis() - started).coerceAtLeast(0L)
        pausedAt = null
    }
}
