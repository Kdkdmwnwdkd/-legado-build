package io.legado.app.ui.autoTask

import io.legado.app.model.AutoTaskRunResult
import io.legado.app.model.AutoTaskRule

/** UI state for one foreground task run. Kept separate from the scheduler. */
sealed interface AutoTaskDebugState {
    data object Idle : AutoTaskDebugState

    data class Loading(val taskId: String) : AutoTaskDebugState

    data class Running(
        val task: AutoTaskRule,
        val startedAt: Long,
        val logs: List<String> = emptyList()
    ) : AutoTaskDebugState

    data class Finished(
        val task: AutoTaskRule,
        val result: AutoTaskRunResult,
        val logs: List<String>
    ) : AutoTaskDebugState

    data class Cancelled(
        val task: AutoTaskRule,
        val startedAt: Long,
        val finishedAt: Long,
        val logs: List<String>
    ) : AutoTaskDebugState

    data class Missing(val taskId: String) : AutoTaskDebugState
}
