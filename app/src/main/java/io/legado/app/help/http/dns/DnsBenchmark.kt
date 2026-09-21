package io.legado.app.help.http.dns

import okhttp3.Dns
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

data class DnsProbeSample(val milliseconds: Double?, val addressCount: Int, val error: String?)

data class DnsProbeResult(val name: String, val provider: DohProvider?, val hostname: String, val samples: List<DnsProbeSample>) {
    val successCount: Int get() = samples.count { it.milliseconds != null }
    val firstMilliseconds: Double? get() = samples.firstOrNull()?.milliseconds
    val medianMilliseconds: Double? get() {
        val values = samples.mapNotNull { it.milliseconds }.sorted()
        if (values.isEmpty()) return null
        return if (values.size % 2 == 0) (values[values.size / 2 - 1] + values[values.size / 2]) / 2 else values[values.size / 2]
    }
}

/** A real resolver probe, not HTTP ping. Hosts, routing rules and fallback are
 * deliberately absent. DoH cache is disabled, but the TLS connection is reused. */
object DnsBenchmark {
    private val executor = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(4), { runnable ->
        Thread(runnable, "DNS probe").apply { isDaemon = true }
    }, ThreadPoolExecutor.AbortPolicy())

    fun providers(config: NetworkDnsConfig): List<DohProvider?> = listOf(null) +
        (DohProvider.presets + DohProvider("custom", "当前自定义服务", config.endpoint, config.bootstrap))
            .distinctBy { it.url to it.bootstrap }

    @Throws(InterruptedException::class)
    fun measure(config: NetworkDnsConfig, provider: DohProvider?, hostname: String, count: Int = 3): DnsProbeResult {
        require(count in 1..5)
        val host = requireNotNull(DnsNames.host(hostname)) { "请输入有效域名" }
        require(DnsNames.literal(host) == null) { "测速需要域名，不能填写 IP 地址" }
        val transport = provider?.let { DohTransport(config.copy(endpoint = it.url, bootstrap = it.bootstrap).validated()) }
        try {
            val resolver = transport?.dns ?: Dns.SYSTEM
            val samples = (0 until count).map {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                sample(resolver, host)
            }
            return DnsProbeResult(provider?.name ?: "系统 DNS", provider, host, samples)
        } finally { transport?.close() }
    }

    private fun sample(dns: Dns, host: String): DnsProbeSample {
        val started = System.nanoTime()
        val future = try {
            executor.submit<List<InetAddress>> { dns.lookup(host) }
        } catch (_: RejectedExecutionException) {
            return DnsProbeSample(null, 0, "测速队列繁忙，请稍后重试")
        }
        try {
            val result = future.get(9, TimeUnit.SECONDS)
            if (result.isEmpty()) return DnsProbeSample(null, 0, "没有解析结果")
            return DnsProbeSample((System.nanoTime() - started) / 1_000_000.0, result.size, null)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: Exception) {
            val cause = if (error is ExecutionException) error.cause ?: error else error
            val chain = generateSequence(cause) { it.cause }.take(12).toList()
            val message = when {
                chain.any { it is TimeoutException || it is SocketTimeoutException } -> "查询超时"
                chain.any { it is SSLException } -> "TLS 连接或证书校验失败"
                else -> "解析失败或服务不可达"
            }
            return DnsProbeSample(null, 0, message)
        } finally {
            future.cancel(true)
            executor.purge()
        }
    }
}
