package io.legado.app.ui.book.read.epub

internal class EpubLayoutReadyActionGate {

    private var nextRegistrationId = 0L
    private var pendingRegistrationId: Long? = null
    private var latestAction: (() -> Unit)? = null

    /**
     * Stores the latest action and returns a registration id when the caller must
     * attach a new layout callback. A null result means an existing valid callback
     * will consume the replacement action.
     */
    fun submit(action: () -> Unit): Long? {
        latestAction = action
        if (pendingRegistrationId != null) return null
        val registrationId = ++nextRegistrationId
        pendingRegistrationId = registrationId
        return registrationId
    }

    fun consume(registrationId: Long): (() -> Unit)? {
        if (pendingRegistrationId != registrationId) return null
        pendingRegistrationId = null
        return latestAction.also { latestAction = null }
    }

    fun cancel() {
        pendingRegistrationId = null
        latestAction = null
    }
}
