package io.legado.app.ui.book.read.epub

/** State belongs to one WebView document. Its revision is never compared across WebViews. */
internal class EpubRuntimeRenderState {
    var token: Long = Long.MIN_VALUE
        private set
    var visualRevision: Long = -1L
        private set
    var layoutPending: Boolean = false
        private set
    var needsMetrics: Boolean = true
        private set
    var sequence: Long = 0L
        private set

    val canCapture: Boolean
        get() = visualRevision >= 0L && !layoutPending && !needsMetrics

    fun reset(token: Long) {
        this.token = token
        visualRevision = -1L
        layoutPending = false
        needsMetrics = true
        sequence++
    }

    fun changed(token: Long, revision: Long, pending: Boolean): Boolean {
        if (token != this.token || revision < visualRevision || revision < 0L) return false
        if (revision == visualRevision && pending == layoutPending) return false
        visualRevision = revision
        layoutPending = pending
        needsMetrics = true
        sequence++
        return true
    }

    fun requireMetrics() {
        if (!needsMetrics) sequence++
        needsMetrics = true
    }

    /** A notification delivered during measurement invalidates that measurement too. */
    fun measured(token: Long, revision: Long, pending: Boolean, expectedSequence: Long): Boolean {
        if (token != this.token || expectedSequence != sequence ||
            revision < visualRevision || revision < 0L
        ) return false
        visualRevision = revision
        layoutPending = pending
        needsMetrics = pending
        return !pending
    }
}
