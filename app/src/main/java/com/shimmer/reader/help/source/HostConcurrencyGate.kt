package com.shimmer.reader.help.source

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 同一个站点上的并发闸门。
 *
 * [SearchSchedule] 管的是「什么时候发」：同站第 n 个源晚 (n-1) × 600ms 出发。
 * 但一个请求可能挂十几秒，600ms 的间隔根本挡不住——同站十个源照样能在六秒内
 * 全部发出去，站方看到的就是十几个连接同时开着。间隔只是错开了起点，没有
 * 管住同时在飞的数量。
 *
 * 这里补的是后半截：同一个 host 最多 [limit] 个请求并行，多出来的排队。
 *
 * 两条边界：
 * 1. 排队本身有上限。等超过 [waitLimitMs] 就直接放行——限流是尽力而为，
 *    不能因为限流把一个本来能用的源活活卡死，那样用户看到的是「少了几个源的结果」，
 *    比被站点限速更糟。
 * 2. 名额一定释放。正常返回、抛异常、协程被取消，都走同一段 finally。
 *    漏一次就意味着这个站点永久少一个并发位，搜得越多堵得越厉害。
 */
class HostConcurrencyGate(
    private val limit: Int,
    private val waitLimitMs: Long = DEFAULT_WAIT_LIMIT_MS,
) {

    companion object {
        /** 同站并发上限：和浏览器对单站点的连接数差不多，比它更保守一点 */
        const val DEFAULT_LIMIT = 4

        /** 排队最多等这么久，再等下去不如直接发 */
        const val DEFAULT_WAIT_LIMIT_MS = 8_000L
    }

    private val gates = ConcurrentHashMap<String, Semaphore>()

    /** 这个闸门会不会真的限制（limit <= 0 表示关掉） */
    val enabled: Boolean get() = limit > 0

    /**
     * @param host 站点主域；空字符串表示「不知道归属」，不参与限流
     */
    suspend fun <T> withSlot(host: String, block: suspend () -> T): T {
        if (!enabled || host.isBlank()) {
            return block()
        }
        // computeIfAbsent 是原子的；用 getOrPut 的话两个协程可能各建一个信号量，闸门就漏了
        val gate = gates.computeIfAbsent(host) { Semaphore(limit) }
        // withTimeoutOrNull 只会在超时时返回 null；外部取消会直接抛出去。
        // 所以「没拿到」可以放心当成「等太久了」，按放行处理。
        val acquired = withTimeoutOrNull(waitLimitMs) {
            gate.acquire()
            true
        } ?: false
        if (!acquired) {
            return block()
        }
        try {
            return block()
        } finally {
            gate.release()
        }
    }
}
