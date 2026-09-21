package io.legado.app.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

enum class AutoTaskRunStatus {
    SUCCESS,
    NO_ACTION,
    INVALID,
    FAILED,
    TIMED_OUT
}

data class AutoTaskActionResult(
    val handled: Boolean,
    val summaries: List<String> = emptyList(),
    val detail: String? = null
)

data class AutoTaskRunResult(
    val ruleId: String,
    val status: AutoTaskRunStatus,
    val startedAt: Long,
    val finishedAt: Long,
    val summaries: List<String> = emptyList(),
    val detail: String? = null,
    val error: String? = null
) {
    val durationMs: Long get() = (finishedAt - startedAt).coerceAtLeast(0L)
}

fun interface AutoTaskScriptExecutor {
    suspend fun execute(rule: AutoTaskRule): Any?
}

fun interface AutoTaskActionHandler {
    suspend fun handle(
        rule: AutoTaskRule,
        value: Any?,
        logger: (String) -> Unit
    ): AutoTaskActionResult
}

/**
 * Runs one task at a time. The conservative global lock is intentional: all
 * built-in actions may touch books, and serializing here prevents two refresh
 * operations from racing on the same chapter table.
 */
class AutoTaskExecutionCoordinator(
    private val scriptExecutor: AutoTaskScriptExecutor,
    private val actionHandler: AutoTaskActionHandler,
    private val now: () -> Long = System::currentTimeMillis,
    private val executionLock: Mutex = Mutex()
) {
    suspend fun run(
        rule: AutoTaskRule,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        logger: (String) -> Unit = {}
    ): AutoTaskRunResult {
        val startedAt = now()
        val validation = AutoTaskRuleValidator.validate(rule)
        if (validation.isNotEmpty()) {
            val finishedAt = now()
            return AutoTaskRunResult(
                ruleId = rule.id,
                status = AutoTaskRunStatus.INVALID,
                startedAt = startedAt,
                finishedAt = finishedAt,
                error = validation.joinToString(",") { "${it.field}:${it.code}" }
            )
        }

        return try {
            executionLock.withLock {
                currentCoroutineContext().ensureActive()
                val effectiveRule = rule.copy(script = rule.normalizedScript())
                try {
                    val actionResult = withTimeout(timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)) {
                        currentCoroutineContext().ensureActive()
                        val value = scriptExecutor.execute(effectiveRule)
                        currentCoroutineContext().ensureActive()
                        actionHandler.handle(effectiveRule, value, logger)
                    }
                    val finishedAt = now()
                    AutoTaskRunResult(
                        ruleId = rule.id,
                        status = if (actionResult.handled) {
                            AutoTaskRunStatus.SUCCESS
                        } else {
                            AutoTaskRunStatus.NO_ACTION
                        },
                        startedAt = startedAt,
                        finishedAt = finishedAt,
                        summaries = actionResult.summaries.take(MAX_SUMMARIES),
                        detail = actionResult.detail?.take(MAX_DETAIL_LENGTH)
                    )
                } catch (error: TimeoutCancellationException) {
                    val finishedAt = now()
                    AutoTaskRunResult(
                        ruleId = rule.id,
                        status = AutoTaskRunStatus.TIMED_OUT,
                        startedAt = startedAt,
                        finishedAt = finishedAt,
                        error = error.message ?: "task timed out"
                    )
                } catch (error: CancellationException) {
                    // Structured cancellation belongs to the caller. Do not turn
                    // a stopped service into a successful/failed task record.
                    throw error
                } catch (error: Throwable) {
                    val finishedAt = now()
                    AutoTaskRunResult(
                        ruleId = rule.id,
                        status = AutoTaskRunStatus.FAILED,
                        startedAt = startedAt,
                        finishedAt = finishedAt,
                        error = (error.message ?: error.toString()).take(MAX_DETAIL_LENGTH)
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2 * 60_000L
        const val MIN_TIMEOUT_MS = 1_000L
        const val MAX_TIMEOUT_MS = 10 * 60_000L
        private const val MAX_SUMMARIES = 64
        private const val MAX_DETAIL_LENGTH = 4_000
    }
}
