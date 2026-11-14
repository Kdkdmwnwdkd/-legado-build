package com.shimmer.reader.help.source

import android.os.SystemClock
import com.script.ScriptException
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.BookSource
import com.shimmer.reader.exception.NoStackTraceException
import com.shimmer.reader.model.webBook.WebBook
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.mozilla.javascript.WrappedException
import java.net.ConnectException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

/**
 * 一次轻量实测的结果：只测「域名通不通」和「搜索有没有结果」两段。
 *
 * 不写书源分组、不加注释、不自动停用，唯一的副作用是把成功的搜索耗时写回
 * `respondTime`，让同域名列表能按「快的在前」排序。
 */
data class ProbeResult(
    val url: String,
    val name: String = "",
    val domainOk: Boolean = false,
    val domainMs: Long = 0L,
    val searchOk: Boolean = false,
    val searchMs: Long = 0L,
    val bookCount: Int = 0,
    /** 失败原因，通过时为空 */
    val reason: String = "",
) {
    val passed: Boolean get() = domainOk && searchOk

    /** 两段加起来的耗时，用作「这组里谁快」的判据 */
    val elapsedMs: Long get() = domainMs + searchMs
}

/**
 * 同域名对比里的一次性实测。
 *
 * 与「书源校验」的区别：这里只跑两段、结果只活在内存里、不碰书源数据，
 * 目的是几十秒内回答「这一组里现在哪个还能用」，而不是做一次全量体检。
 */
object SourceProbe {

    /** 域名段上限，和搜索页的连接超时保持一致 */
    private const val DOMAIN_TIMEOUT_MS = 6_000L

    /** 搜索段上限，和搜索页的单源超时保持一致 */
    private const val SEARCH_TIMEOUT_MS = 25_000L

    private const val DOMAIN_CONNECT_TIMEOUT_MS = 3_000

    suspend fun probe(url: String, defaultKeyword: String): ProbeResult {
        val source = withContext(IO) { appDb.bookSourceDao.getBookSource(url) }
            ?: return ProbeResult(url = url, reason = "书源已不存在")
        return probe(source, defaultKeyword)
    }

    suspend fun probe(source: BookSource, defaultKeyword: String): ProbeResult {
        val url = source.bookSourceUrl
        val name = source.bookSourceName
        val domainStart = SystemClock.elapsedRealtime()
        val domainOk = runCatching {
            withTimeout(DOMAIN_TIMEOUT_MS) { reachable(url) }
        }.getOrDefault(false)
        val domainMs = SystemClock.elapsedRealtime() - domainStart
        if (!domainOk) {
            return ProbeResult(
                url = url,
                name = name,
                domainOk = false,
                domainMs = domainMs,
                reason = "域名不可访问"
            )
        }
        if (source.searchUrl.isNullOrBlank()) {
            return ProbeResult(
                url = url,
                name = name,
                domainOk = true,
                domainMs = domainMs,
                reason = "没有搜索规则"
            )
        }
        val keyword = source.getCheckKeyword(defaultKeyword)
        val searchStart = SystemClock.elapsedRealtime()
        val attempt = runCatching {
            withTimeout(SEARCH_TIMEOUT_MS) {
                WebBook.searchBookAwait(
                    bookSource = source,
                    key = keyword,
                    shouldBreak = { it > 0 },
                    // 实测只看「有没有结果」，坏源早点让位，别把整组拖住
                    connectTimeoutMs = DOMAIN_TIMEOUT_MS,
                    callTimeoutMs = SEARCH_TIMEOUT_MS - 5_000
                )
            }
        }
        val searchMs = SystemClock.elapsedRealtime() - searchStart
        return attempt.fold(
            onSuccess = { books ->
                if (books.isEmpty()) {
                    ProbeResult(
                        url = url,
                        name = name,
                        domainOk = true,
                        domainMs = domainMs,
                        searchMs = searchMs,
                        reason = "搜索没有结果"
                    )
                } else {
                    writeRespondTime(source, searchMs)
                    ProbeResult(
                        url = url,
                        name = name,
                        domainOk = true,
                        domainMs = domainMs,
                        searchOk = true,
                        searchMs = searchMs,
                        bookCount = books.size
                    )
                }
            },
            onFailure = { e ->
                ProbeResult(
                    url = url,
                    name = name,
                    domainOk = true,
                    domainMs = domainMs,
                    searchMs = searchMs,
                    reason = e.probeReason()
                )
            }
        )
    }

    /**
     * 只探域名是否活着：先发 HEAD，失败再退回 TCP 连接。
     * 和书源校验用的是同一套判定，避免两处结论不一致。
     */
    private suspend fun reachable(origin: String): Boolean = withContext(IO) {
        runCatching {
            val uri = URI(origin.substringBefore("#"))
            val port = uri.port.takeIf { it > 0 } ?: if (uri.scheme.equals("https", true)) 443 else 80
            runCatching {
                val connection = (URL(uri.toString()).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = DOMAIN_CONNECT_TIMEOUT_MS
                    readTimeout = DOMAIN_CONNECT_TIMEOUT_MS
                    instanceFollowRedirects = true
                    requestMethod = "HEAD"
                }
                connection.responseCode in 200..499
            }.getOrElse {
                Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(uri.host, port), DOMAIN_CONNECT_TIMEOUT_MS)
                    true
                }
            }
        }.getOrDefault(false)
    }

    private suspend fun writeRespondTime(source: BookSource, ms: Long) {
        if (ms <= 0L) return
        withContext(IO) {
            runCatching {
                source.respondTime = ms
                appDb.bookSourceDao.update(source)
            }
        }
    }

    /** 把异常翻成人话，分类与书源校验保持一致。 */
    private fun Throwable.probeReason(): String {
        if (this is NoStackTraceException) {
            localizedMessage?.takeIf { it.isNotBlank() }?.let { return it.replace(", ", "、") }
        }
        if (this is TimeoutCancellationException) return "搜索超时"
        if (this is ScriptException || this is WrappedException) return "js 失效"
        var current: Throwable? = this
        val visited = HashSet<Throwable>()
        while (current != null && visited.add(current)) {
            when (current) {
                is UnknownHostException -> return "域名无法解析"
                is SocketTimeoutException -> return "源站响应超时"
                is SSLException -> return "证书校验失败"
                is ConnectException -> return "无法连接源站"
                is SocketException -> return "网络连接中断"
            }
            current = current.cause ?: (current as? WrappedException)?.wrappedException
        }
        return localizedMessage?.takeIf { it.isNotBlank() }?.take(60) ?: javaClass.simpleName
    }
}

/**
 * 实测结果的内存缓存，按书源地址存。
 *
 * 只在这个进程里有效，5 分钟过期：目的是关掉对比弹窗再打开不用重测一遍，
 * 而不是当作持久结论——站点状态随时会变。
 */
object SourceProbeCache {

    private const val TTL_MS = 5 * 60_000L

    private val entries = ConcurrentHashMap<String, Entry>()

    private data class Entry(val result: ProbeResult, val at: Long)

    fun put(result: ProbeResult) {
        entries[result.url] = Entry(result, SystemClock.elapsedRealtime())
    }

    fun get(url: String): ProbeResult? {
        val entry = entries[url] ?: return null
        if (SystemClock.elapsedRealtime() - entry.at > TTL_MS) {
            entries.remove(url)
            return null
        }
        return entry.result
    }

    fun clear(urls: Collection<String>) {
        urls.forEach { entries.remove(it) }
    }
}
