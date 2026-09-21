package io.legado.app.ui.book.read.epub

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owned by the reader's main thread; only chapter preparation runs on IO. */
internal class EpubDirectPrefetchScheduler<T : Any>(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val retryDelayMillis: Long = 3_000L,
    private val waitBeforePreparation: suspend (Long) -> Unit = { delay(it) }
) {
    private class Plan<T>(
        val owner: Any,
        val candidates: Set<Int>,
        val onPrepared: (Int, T) -> Unit,
        val onFailure: (Int, Throwable) -> Unit
    )

    private class Task(val owner: Any) {
        lateinit var job: Job
    }

    private var plan: Plan<T>? = null
    private val tasks = mutableMapOf<Int, Task>()
    private val attemptedAt = mutableMapOf<Int, Long>()

    fun schedule(
        owner: Any,
        candidates: List<Int>,
        staggerMillis: Long,
        isPrepared: (Int) -> Boolean,
        prepare: suspend (Int) -> T,
        onPrepared: (Int, T) -> Unit,
        onFailure: (Int, Throwable) -> Unit
    ) {
        if (plan?.owner != owner) cancel()
        val wanted = candidates.toSet()
        plan = Plan(owner, wanted, onPrepared, onFailure)
        tasks.keys.filter { it !in wanted }.forEach { tasks.remove(it)?.job?.cancel() }
        attemptedAt.keys.retainAll(wanted)
        candidates.distinct().forEachIndexed { order, index ->
            if (index in tasks || isPrepared(index)) return@forEachIndexed
            val now = nowMillis()
            if (attemptedAt[index]?.let { now - it < retryDelayMillis } == true) return@forEachIndexed
            val task = Task(owner)
            tasks[index] = task
            task.job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (order > 0 && staggerMillis > 0) {
                        waitBeforePreparation(staggerMillis.coerceAtMost(Long.MAX_VALUE / order) * order)
                    }
                    ensureActive()
                    if (currentPlan(index, task) != null) attemptedAt[index] = nowMillis()
                    val result = withContext(dispatcher) { prepare(index) }
                    ensureActive()
                    currentPlan(index, task)?.onPrepared?.invoke(index, result)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    currentPlan(index, task)?.onFailure?.invoke(index, error)
                } finally {
                    if (tasks[index] === task) tasks.remove(index)
                }
            }
            task.job.start()
        }
    }

    /** The foreground can join this session load even when other prefetches are cancelled. */
    fun handoff(chapterIndex: Int) {
        tasks.remove(chapterIndex)
        attemptedAt.remove(chapterIndex)
    }

    fun cancel() {
        val cancelled = tasks.values.toList()
        tasks.clear()
        attemptedAt.clear()
        plan = null
        cancelled.forEach { it.job.cancel() }
    }

    private fun currentPlan(index: Int, task: Task): Plan<T>? = plan?.takeIf {
        tasks[index] === task && it.owner == task.owner && index in it.candidates
    }
}
