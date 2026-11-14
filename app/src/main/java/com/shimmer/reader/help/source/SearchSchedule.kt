package com.shimmer.reader.help.source

/**
 * 一条搜索任务的发放安排。
 *
 * [delayMs] 是相对搜索开始的等待时间：同一个站点上的第 2 条任务等 600 毫秒再发，
 * 第 3 条等 1200 毫秒，依次往后，封顶 3 秒。
 */
data class ScheduledSource<T>(
    val source: T,
    val delayMs: Long,
    val host: String,
)

/**
 * 搜索时的书源发放顺序。
 *
 * 解决的问题：书源列表按用户手动顺序排，同站十几个源会在同一瞬间向同一个站点发请求，
 * 站点一看就是十几并发，于是限速或排队，结果每个都慢。首屏时间被最慢的那个源拖住。
 *
 * 做法只有两条，都不减少任何书源：
 * 1. 同一个站点内部，响应时间短的先发；没测过的排后面。
 * 2. 同一个站点内部错峰：第 2 个源等 600ms，第 3 个等 1.2s，最多等 3 秒。
 *    不同站点之间是并行的，第一轮所有站点的第一个源同时发。
 *
 * 纯粹是发放顺序，不改解析、不改超时、不改结果合并。
 */
object SearchSchedule {

    /** 同站相邻两个源之间隔多久发 */
    const val STEP_MS = 600L

    /** 同站排队最多等多久，再多就没意义了 */
    const val MAX_DELAY_MS = 3_000L

    /** 书源里 respondTime 表明「没测过」的默认值 */
    private const val UNKNOWN_RESPOND_TIME = 180_000L

    /**
     * @param urlOf 取书源地址，用来归组
     * @param respondTimeOf 取响应时间，越小越先发
     * @param hostOf 取主域，UI 层传 NetworkUtils::getSubDomainOrNull；
     *               取不到时按地址各自成组，不会误合并
     */
    fun <T> order(
        sources: List<T>,
        urlOf: (T) -> String,
        respondTimeOf: (T) -> Long,
        hostOf: (String) -> String?,
        stepMs: Long = STEP_MS,
        maxDelayMs: Long = MAX_DELAY_MS,
    ): List<ScheduledSource<T>> {
        if (sources.size < 2) {
            return sources.map { ScheduledSource(it, 0L, hostOrSelf(urlOf(it), hostOf)) }
        }
        val buckets = LinkedHashMap<String, MutableList<T>>()
        for (source in sources) {
            val url = urlOf(source)
            val host = hostOrSelf(url, hostOf)
            buckets.getOrPut(host) { ArrayList() }.add(source)
        }
        // 组内：响应快的在前，没测过的靠后；sortedWith 是稳定排序，同值时保持用户原来的顺序
        val groups = buckets.map { (host, list) ->
            host to list.sortedWith(compareBy { normalizeRespondTime(respondTimeOf(it)) })
        // 组间：整体更快的站点先开始，让首屏早点出东西
        }.sortedBy { (_, list) -> normalizeRespondTime(respondTimeOf(list.first())) }

        val depth = groups.maxOf { (_, list) -> list.size }
        val result = ArrayList<ScheduledSource<T>>(sources.size)
        for (round in 0 until depth) {
            val delay = (round * stepMs).coerceAtMost(maxDelayMs)
            for ((host, list) in groups) {
                val source = list.getOrNull(round) ?: continue
                result.add(ScheduledSource(source, delay, host))
            }
        }
        return result
    }

    /** 0 或负数当作没测过；没测过的排在测过的后面，但不能影响同组内的相对顺序 */
    private fun normalizeRespondTime(value: Long): Long {
        return if (value <= 0L || value >= UNKNOWN_RESPOND_TIME) Long.MAX_VALUE else value
    }

    private fun hostOrSelf(url: String, hostOf: (String) -> String?): String {
        val host = hostOf(url)?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        return host ?: "url:${url.trim().lowercase()}"
    }
}
