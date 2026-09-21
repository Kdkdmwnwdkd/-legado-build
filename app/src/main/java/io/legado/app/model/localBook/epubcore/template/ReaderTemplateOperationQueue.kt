package io.legado.app.model.localBook.epubcore.template

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Serializes this sheet's operations on its owner dispatcher. Submit from that same dispatcher.
 * A delivered file result may queue behind loading and remains accepted if the sheet is dismissed.
 */
internal class ReaderTemplateOperationQueue(
    private val scope: CoroutineScope,
    private val onBusyChanged: (Boolean) -> Unit,
    private val onStart: () -> Unit,
    private val onError: (Throwable) -> Unit
) {
    private val mutex = Mutex()
    private var pendingOperations = 0

    fun submit(enqueue: Boolean = false, action: suspend () -> Unit): Boolean {
        if (!enqueue && (pendingOperations > 0 || !scope.isActive)) return false
        pendingOperations++
        if (pendingOperations == 1) onBusyChanged(true)
        // Enter the try/finally immediately so cancelling before dispatch cannot strand busy.
        scope.launch(
            context = if (enqueue) NonCancellable else EmptyCoroutineContext,
            start = CoroutineStart.UNDISPATCHED
        ) {
            try {
                mutex.withLock {
                    try {
                        ensureActive()
                        onStart()
                        action()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        // Report before unlocking: a later operation must clear the earlier error.
                        onError(error)
                    }
                }
            } finally {
                pendingOperations--
                if (pendingOperations == 0) onBusyChanged(false)
            }
        }
        return true
    }
}
