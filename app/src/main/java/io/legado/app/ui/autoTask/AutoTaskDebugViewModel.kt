package io.legado.app.ui.autoTask

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRunStatus
import io.legado.app.model.AutoTaskRule
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class AutoTaskDebugViewModel(application: Application) : BaseViewModel(application) {

    private val _task = MutableStateFlow<AutoTaskRule?>(null)
    val task = _task.asStateFlow()

    private val _state = MutableStateFlow<AutoTaskDebugState>(AutoTaskDebugState.Idle)
    val state = _state.asStateFlow()

    private var runJob: Job? = null
    private val runGeneration = AtomicLong(0L)
    @Volatile
    private var cancelGeneration: Long? = null

    fun loadAndStart(taskId: String?) {
        val id = taskId?.trim().orEmpty()
        val generation = runGeneration.incrementAndGet()
        cancelGeneration = null
        runJob?.cancel()
        if (id.isBlank()) {
            _state.value = AutoTaskDebugState.Missing(id)
            return
        }
        runJob = viewModelScope.launch(Dispatchers.IO) {
            if (!isCurrent(generation)) return@launch
            _state.value = AutoTaskDebugState.Loading(id)
            val rule = runCatching { AutoTask.get(id) }.getOrNull()
            if (!isCurrent(generation)) return@launch
            if (rule == null) {
                _state.value = AutoTaskDebugState.Missing(id)
                return@launch
            }
            _task.value = rule
            executeRule(rule, generation)
        }
    }

    fun cancel() {
        val job = runJob ?: return
        if (!job.isActive) return
        cancelGeneration = runGeneration.get()
        job.cancel()
    }

    fun clearLiveLogs() {
        _state.update { current ->
            when (current) {
                is AutoTaskDebugState.Running -> current.copy(logs = emptyList())
                is AutoTaskDebugState.Finished -> current.copy(logs = emptyList())
                is AutoTaskDebugState.Cancelled -> current.copy(logs = emptyList())
                else -> current
            }
        }
    }

    override fun onCleared() {
        runGeneration.incrementAndGet()
        runJob?.cancel()
        runJob = null
        super.onCleared()
    }

    private suspend fun executeRule(rule: AutoTaskRule, generation: Long) {
        val startedAt = System.currentTimeMillis()
        val logs = ArrayList<String>(DEFAULT_LOG_CAPACITY)
        if (!isCurrent(generation)) return
        _state.value = AutoTaskDebugState.Running(rule, startedAt)
        appendLog(logs, "[${timeLabel(startedAt)}] ${context.getString(io.legado.app.R.string.auto_task_debug_started)}")
        try {
            val result = AutoTask.executionCoordinator(context).run(
                rule = rule,
                logger = { line ->
                    appendLog(logs, line)
                    if (isCurrent(generation)) {
                        _state.value = AutoTaskDebugState.Running(rule, startedAt, logs.toList())
                    }
                }
            )
            if (!isCurrent(generation)) return
            val finishedAt = result.finishedAt.coerceAtLeast(startedAt)
            appendLog(logs, "[${timeLabel(finishedAt)}] ${context.getString(io.legado.app.R.string.auto_task_debug_finished)}")
            persistResult(rule, result, logs, finishedAt)
            _state.value = AutoTaskDebugState.Finished(rule, result, logs.toList())
        } catch (cancelled: CancellationException) {
            if (cancelGeneration == generation && isCurrent(generation)) {
                val finishedAt = System.currentTimeMillis()
                appendLog(logs, "[${timeLabel(finishedAt)}] ${context.getString(io.legado.app.R.string.auto_task_debug_cancelled)}")
                _state.value = AutoTaskDebugState.Cancelled(
                    task = rule,
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                    logs = logs.toList()
                )
                return
            }
            throw cancelled
        } catch (error: Throwable) {
            val finishedAt = System.currentTimeMillis()
            val message = error.localizedMessage ?: error.toString()
            appendLog(logs, error.stackTraceStr)
            val result = io.legado.app.model.AutoTaskRunResult(
                ruleId = rule.id,
                status = AutoTaskRunStatus.FAILED,
                startedAt = startedAt,
                finishedAt = finishedAt,
                error = message.take(MAX_ERROR_LENGTH)
            )
            if (!isCurrent(generation)) return
            persistResult(rule, result, logs, finishedAt)
            _state.value = AutoTaskDebugState.Finished(rule, result, logs.toList())
        }
    }

    private fun isCurrent(generation: Long): Boolean = runGeneration.get() == generation

    private fun appendLog(logs: ArrayList<String>, line: String) {
        if (line.isBlank()) return
        logs += line.take(MAX_LOG_LINE_LENGTH)
        if (logs.size > DEFAULT_LOG_CAPACITY) {
            logs.removeAt(0)
        }
    }

    private fun persistResult(
        rule: AutoTaskRule,
        result: io.legado.app.model.AutoTaskRunResult,
        logs: List<String>,
        finishedAt: Long
    ) {
        val detail = result.detail ?: result.summaries.joinToString(" | ")
        val error = result.error
        val lastLog = if (result.status == AutoTaskRunStatus.SUCCESS ||
            result.status == AutoTaskRunStatus.NO_ACTION
        ) {
            AutoTask.buildLastLog(logs, detail, result.durationMs, finishedAt)
        } else {
            AutoTask.buildErrorLog(error ?: result.status.name, null, finishedAt)
        }
        AutoTask.updateRuntime(rule.id) {
            it.copy(
                lastRunAt = finishedAt,
                lastResult = detail.take(MAX_ERROR_LENGTH).ifBlank { null },
                lastError = error,
                lastLog = lastLog
            )
        }
    }

    private fun timeLabel(time: Long): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(time))

    companion object {
        private const val DEFAULT_LOG_CAPACITY = 256
        private const val MAX_LOG_LINE_LENGTH = 8_000
        private const val MAX_ERROR_LENGTH = 4_000
    }
}
