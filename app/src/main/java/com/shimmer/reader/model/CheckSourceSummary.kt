package com.shimmer.reader.model

/**
 * 书源校验的收尾统计。
 *
 * 只做计数和文案素材整理，不参与判定：每个源校验结束时，把「本次残留的失效分组」
 * 和「是否被自动禁用」交给 [record]，其余一律不碰。
 *
 * 之所以能这么取，是因为校验开始时 [BookSource.removeInvalidGroups] 会把失效分组清空，
 * 结束时还留在源上的失效标签，必然是这一轮加上的，不会把上一轮的结果算进来。
 */
class CheckSourceSummary {

    private val failures = linkedMapOf<String, Int>()

    /** 已校验的源数量。 */
    var checked: Int = 0
        private set

    /** 至少有一个失效分组的源数量。 */
    var failedSources: Int = 0
        private set

    /** 被校验流程自动禁用的源数量。 */
    var disabledSources: Int = 0
        private set

    /**
     * 本轮被自动停用的源地址。
     *
     * 收尾文案要告诉用户「这几本正在读的书打不开了」，所以得知道具体是哪些源，
     * 只记一个数量是不够的。
     */
    private val _disabledOrigins = ArrayList<String>()
    val disabledOrigins: List<String> get() = _disabledOrigins

    /** 没有失效分组的源数量。 */
    val usable: Int get() = (checked - failedSources).coerceAtLeast(0)

    fun reset() {
        failures.clear()
        _disabledOrigins.clear()
        checked = 0
        failedSources = 0
        disabledSources = 0
    }

    /**
     * 记录一个源的结果。
     *
     * @param invalidGroups 本次结束时仍留在源上的失效分组，空表示这个源这轮可用
     * @param wasEnabled 校验开始前是否启用
     * @param enabledNow 校验结束后是否启用
     * @param origin 书源地址，用于事后分析哪些书受影响；不传则只计数
     */
    fun record(
        invalidGroups: List<String>,
        wasEnabled: Boolean,
        enabledNow: Boolean,
        origin: String? = null,
    ) {
        checked++
        // 只统计本轮由开变关的源：用户很久以前手动关掉的源不该算进来
        if (wasEnabled && !enabledNow) {
            disabledSources++
            origin?.takeIf { it.isNotBlank() }?.let { _disabledOrigins.add(it) }
        }
        if (invalidGroups.isEmpty()) return
        failedSources++
        invalidGroups.distinct().forEach { tag ->
            failures[tag] = (failures[tag] ?: 0) + 1
        }
    }

    fun countOf(tag: String): Int = failures[tag] ?: 0

    /**
     * 失败最多的若干项，超出的合并为一项「其他」。
     *
     * 通知栏只有一两行，全量列出来会被截断；这里按数量降序、同数量按名称排，
     * 保证同样的输入永远给出同样的输出。
     */
    fun topFailures(limit: Int = 3): List<FailureCount> {
        if (failures.isEmpty()) return emptyList()
        val sorted = failures.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { it.key }
        )
        if (sorted.size <= limit) {
            return sorted.map { FailureCount(it.key, it.value) }
        }
        val head = sorted.take(limit).map { FailureCount(it.key, it.value) }
        val rest = sorted.drop(limit).sumOf { it.value }
        return head + FailureCount(null, rest)
    }

    /**
     * 拼失败明细。文案与分隔符交给调用方，避免在这里绑死语言。
     */
    fun failureText(
        itemFormat: (String, Int) -> String,
        separator: String,
        restFormat: (Int) -> String
    ): String {
        return topFailures().joinToString(separator) { item ->
            val tag = item.tag
            if (tag == null) restFormat(item.count) else itemFormat(tag, item.count)
        }
    }

    /**
     * @param tag 分组名；为 null 表示「其他」合并项
     */
    data class FailureCount(val tag: String?, val count: Int)
}
