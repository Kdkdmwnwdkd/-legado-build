package io.legado.app.ui.book.read.epub

internal class EpubDirectEmbeddedInteractionPolicy {

    private var nativeTouchActive = false
    private var interactionId = Long.MIN_VALUE

    var active: Boolean = false
        private set

    fun reset() {
        nativeTouchActive = false
        interactionId = Long.MIN_VALUE
        active = false
    }

    fun onNativeTouchStarted() {
        nativeTouchActive = true
        active = false
    }

    fun onNativeTouchFinished() {
        nativeTouchActive = false
        active = false
    }

    fun onBridgeState(id: Long, requestedActive: Boolean): Boolean {
        if (id < interactionId) return active
        if (requestedActive) {
            if (!nativeTouchActive) return active
            interactionId = id
            active = true
        } else if (id == interactionId) {
            active = false
        }
        return active
    }
}
