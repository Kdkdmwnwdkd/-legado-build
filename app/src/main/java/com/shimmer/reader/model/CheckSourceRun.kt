package com.shimmer.reader.model

import com.shimmer.reader.data.entities.BookSourcePart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 校验走到哪一步。顺序与 [com.shimmer.reader.service.CheckSourceService] 的检查顺序一致，
 * 只用于在进度页告诉用户「这个源正在查搜索还是查正文」，不参与任何判定。
 */
enum class CheckStage {
    PREPARE,
    DOMAIN,
    SEARCH,
    DISCOVERY,
    INFO,
    TOC,
    CONTENT,
}

/** 一轮校验的整体状态。 */
enum class CheckRunStatus {
    IDLE,
    RUNNING,
    FINISHED,
    CANCELLED,
}

/** 单个书源的状态。 */
enum class CheckItemStatus {
    WAITING,
    RUNNING,
    PASSED,
    FAILED,
    CANCELLED,
}

data class CheckItem(
    val origin: String,
    val name: String = "",
    val type: Int = 0,
    val status: CheckItemStatus = CheckItemStatus.WAITING,
    val stage: CheckStage = CheckStage.PREPARE,
    /** 失败原因，通过时为空。 */
    val reason: String = "",
    val durationMs: Long = 0L,
)

/**
 * 一轮校验的完整快照。
 *
 * 这里存的是「每个源现在怎么样」，不是最终结论；收尾用的数字仍然由
 * [CheckSourceSummary] 按失效分组算，两者来源不同，互不覆盖。
 */
data class CheckRun(
    val status: CheckRunStatus = CheckRunStatus.IDLE,
    val items: List<CheckItem> = emptyList(),
    val startedAt: Long = 0L,
    val endedAt: Long = 0L,
    /** 本轮因校验失败被自动关掉的源数量，结束时才有值。 */
    val autoDisabled: Int = 0,
) {
    val total: Int get() = items.size
    val passed: Int get() = items.count { it.status == CheckItemStatus.PASSED }
    val failed: Int get() = items.count { it.status == CheckItemStatus.FAILED }
    val cancelled: Int get() = items.count { it.status == CheckItemStatus.CANCELLED }
    val done: Int
        get() = items.count {
            it.status == CheckItemStatus.PASSED ||
                it.status == CheckItemStatus.FAILED ||
                it.status == CheckItemStatus.CANCELLED
        }
    val remaining: Int get() = (total - done).coerceAtLeast(0)
    val settled: Boolean
        get() = status == CheckRunStatus.FINISHED || status == CheckRunStatus.CANCELLED
    val progress: Float
        get() = if (total <= 0) 0f else done.toFloat() / total
}

/**
 * 进程内保存最近一轮校验的进度。
 *
 * 原来进度只有一条 EventBus 文本消息：换页面、旋转屏幕、从通知点回来全都接不上，
 * 每个源的结果也只活在 Debug 的临时 map 里。服务改成往这里打点，页面订阅同一份状态，
 * 谁先谁后都不影响结果。进程被杀就没了，这一点和校验任务本身的寿命一致。
 */
object CheckSourceRunStore {

    private val mutableState = MutableStateFlow(CheckRun())
    val state: StateFlow<CheckRun> = mutableState.asStateFlow()

    @Synchronized
    fun begin(sources: List<BookSourcePart>) {
        mutableState.value = CheckRun(
            status = CheckRunStatus.RUNNING,
            startedAt = System.currentTimeMillis(),
            items = sources
                .distinctBy { it.bookSourceUrl }
                .map {
                    CheckItem(
                        origin = it.bookSourceUrl,
                        name = it.bookSourceName,
                        type = it.bookSourceType,
                    )
                },
        )
    }

    @Synchronized
    fun markRunning(origin: String, name: String, type: Int) {
        updateItem(origin) {
            it.copy(
                name = name.ifBlank { it.name },
                type = type,
                status = CheckItemStatus.RUNNING,
                stage = CheckStage.PREPARE,
                reason = "",
                durationMs = 0L,
            )
        }
    }

    @Synchronized
    fun markStage(origin: String, stage: CheckStage) {
        updateItem(origin) {
            if (it.status != CheckItemStatus.RUNNING || it.stage == stage) {
                it
            } else {
                it.copy(stage = stage)
            }
        }
    }

    @Synchronized
    fun markPassed(origin: String, durationMs: Long) {
        updateItem(origin) {
            it.copy(
                status = CheckItemStatus.PASSED,
                reason = "",
                durationMs = durationMs,
            )
        }
    }

    @Synchronized
    fun markFailed(origin: String, reason: String, durationMs: Long) {
        updateItem(origin) {
            it.copy(
                status = CheckItemStatus.FAILED,
                reason = reason,
                durationMs = durationMs,
            )
        }
    }

    /**
     * 收尾。取消时把还在跑的源标成已取消，避免页面上留着几个永远转圈的条目。
     *
     * @param autoDisabled 本轮被自动关掉的源数量，仅用于结果提示
     */
    @Synchronized
    fun finish(cancelled: Boolean, autoDisabled: Int = 0) {
        val current = mutableState.value
        if (current.status != CheckRunStatus.RUNNING) return
        val items = if (cancelled) {
            current.items.map {
                if (it.status == CheckItemStatus.RUNNING || it.status == CheckItemStatus.WAITING) {
                    it.copy(status = CheckItemStatus.CANCELLED, reason = "")
                } else {
                    it
                }
            }
        } else {
            current.items
        }
        mutableState.value = current.copy(
            status = if (cancelled) CheckRunStatus.CANCELLED else CheckRunStatus.FINISHED,
            items = items,
            endedAt = System.currentTimeMillis(),
            autoDisabled = autoDisabled,
        )
    }

    @Synchronized
    fun reset() {
        mutableState.value = CheckRun()
    }

    private inline fun updateItem(origin: String, transform: (CheckItem) -> CheckItem) {
        val current = mutableState.value
        if (current.status != CheckRunStatus.RUNNING) return
        val index = current.items.indexOfFirst { it.origin == origin }
        if (index < 0) return
        val old = current.items[index]
        val new = transform(old)
        if (new === old) return
        val items = current.items.toMutableList()
        items[index] = new
        mutableState.value = current.copy(items = items)
    }
}
