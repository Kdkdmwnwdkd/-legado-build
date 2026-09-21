package io.legado.app.ui.book.read.epub

internal class EpubDirectAutoPager(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val intervalMillis: () -> Long,
    private val requestNextPage: () -> EpubPageTurnResult,
    private val onStopped: () -> Unit
) : Runnable {

    enum class State {
        Stopped,
        Running,
        WaitingForCommit
    }

    data class Position(
        val chapterIndex: Int,
        val pageIndex: Int
    )

    var state: State = State.Stopped
        private set

    val isRunning: Boolean
        get() = state != State.Stopped

    private var paused = false
    private var committedPosition: Position? = null
    private var requestSource: Position? = null
    private val commitTimeout = Runnable {
        if (state == State.WaitingForCommit) stopInternal(notify = true)
    }

    fun start(position: Position): Boolean {
        if (isRunning) return false
        committedPosition = position
        requestSource = null
        paused = false
        state = State.Running
        scheduleNext()
        return true
    }

    fun stop() {
        stopInternal(notify = true)
    }

    fun pause() {
        if (!isRunning || paused) return
        paused = true
        removeCallbacks(this)
        if (state == State.WaitingForCommit) {
            clearCommitWait()
            state = State.Running
        }
    }

    fun resume() {
        if (!isRunning || !paused) return
        paused = false
        if (state == State.Running) scheduleNext()
    }

    fun onPageCommitted(position: Position) {
        val changed = position != committedPosition
        committedPosition = position
        when {
            state == State.WaitingForCommit && position != requestSource -> {
                clearCommitWait()
                state = State.Running
                if (!paused) scheduleNext()
            }

            state == State.Running && changed && !paused -> scheduleNext()
        }
    }

    override fun run() {
        if (state != State.Running || paused) return
        removeCallbacks(this)
        requestSource = committedPosition
        state = State.WaitingForCommit
        val accepted = runCatching { requestNextPage() }
            .getOrDefault(EpubPageTurnResult.Unavailable)
            .let {
                it == EpubPageTurnResult.MovedWithinChapter ||
                    it == EpubPageTurnResult.BoundaryRequired ||
                    it == EpubPageTurnResult.Queued
            }
        if (accepted) {
            postDelayed(commitTimeout, WAITING_COMMIT_TIMEOUT_MS)
        } else {
            stopInternal(notify = true)
        }
    }

    fun onTurnCancelled() {
        if (state != State.WaitingForCommit) return
        clearCommitWait()
        state = State.Running
        if (!paused) scheduleNext()
    }

    fun onTurnFailed() {
        if (isRunning) stopInternal(notify = true)
    }

    private fun scheduleNext() {
        removeCallbacks(this)
        postDelayed(this, intervalMillis().coerceAtLeast(MIN_INTERVAL_MS))
    }

    private fun stopInternal(notify: Boolean) {
        if (!isRunning) return
        removeCallbacks(this)
        clearCommitWait()
        state = State.Stopped
        paused = false
        committedPosition = null
        requestSource = null
        if (notify) onStopped()
    }

    private fun clearCommitWait() {
        removeCallbacks(commitTimeout)
        requestSource = null
    }

    companion object {
        private const val MIN_INTERVAL_MS = 1_000L
        internal const val WAITING_COMMIT_TIMEOUT_MS = 15_000L
    }
}
