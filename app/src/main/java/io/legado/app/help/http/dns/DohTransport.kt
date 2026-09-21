package io.legado.app.help.http.dns

import okhttp3.Cache
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.dnsoverhttps.DnsOverHttps
import okio.Buffer
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Independent from business clients: normal TLS, no cookies, no Cronet and
 * no recursive DNS. Cache entries use the server's HTTP cache lifetime. */
class DohTransport(
    config: NetworkDnsConfig,
    cacheDirectory: File? = null,
    configureClient: OkHttpClient.Builder.() -> Unit = {}
) : Closeable {
    private val settings = config.validated()
    private val endpoint = settings.endpoint.toHttpUrl()
    private val cache = cacheDirectory?.let { Cache(it, 4L * 1024 * 1024) }
    private val dispatcher = Dispatcher().apply { maxRequests = 16; maxRequestsPerHost = 16 }
    private val client = OkHttpClient.Builder()
        .apply(configureClient)
        .dispatcher(dispatcher)
        .cache(cache)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        // Bound the decoded body, including gzip and chunked responses. This
        // also guards reads from the HTTP cache before the DNS codec sees them.
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            try {
                val source = response.body.source()
                val buffer = Buffer()
                while (buffer.size <= MAX_RESPONSE_BYTES) {
                    if (source.read(buffer, minOf(8192, MAX_RESPONSE_BYTES + 1 - buffer.size)) == -1L) break
                }
                if (buffer.size > MAX_RESPONSE_BYTES) throw IOException("DoH response exceeds 64 KiB")
                response.newBuilder().body(buffer.readByteArray().toResponseBody(response.body.contentType())).build()
            } finally { response.close() }
        }
        .build()
    private val implementation = DnsOverHttps.Builder()
        .client(client)
        .url(endpoint)
        .includeIPv6(settings.includeIpv6)
        .resolvePrivateAddresses(true) // Local-name routing is decided by ScopedDns.
        .systemDns(EndpointBootstrapDns(endpoint.host, settings.bootstrap, client.dns))
        .build()

    val dns: Dns = CoalescingDns(implementation)

    override fun close() {
        dispatcher.cancelAll()
        client.connectionPool.evictAll()
        dispatcher.executorService.shutdownNow()
        cache?.close()
    }

    companion object { private const val MAX_RESPONSE_BYTES = 64 * 1024L }
}

/** Bootstrap only the service host. A system HTTP proxy may need its own DNS
 * lookup; the official endpoint-only BootstrapDns would reject that hostname. */
internal class EndpointBootstrapDns(
    private val endpointHost: String,
    addresses: List<String>,
    private val system: Dns
) : Dns {
    private val bootstrap = addresses.map { requireNotNull(DnsNames.literal(it)) }

    override fun lookup(hostname: String): List<InetAddress> =
        if (hostname == endpointHost && bootstrap.isNotEmpty()) bootstrap else system.lookup(hostname)
}

/** Share in-flight queries only; DNS answer lifetime remains the HTTP cache's
 * responsibility. Both waiting callers and queue admission have finite limits. */
class CoalescingDns(private val delegate: Dns) : Dns {
    private class Pending {
        val latch = CountDownLatch(1)
        var result: List<InetAddress>? = null
        var failure: UnknownHostException? = null
    }
    private val inFlight = ConcurrentHashMap<String, Pending>()
    private val slots = Semaphore(8, true)

    override fun lookup(hostname: String): List<InetAddress> {
        val future = Pending()
        val existing = inFlight.putIfAbsent(hostname, future)
        if (existing != null) {
            try {
                if (!existing.latch.await(9, TimeUnit.SECONDS)) throw TimeoutException("DoH query timed out")
                existing.failure?.let { throw it }
                return existing.result ?: throw UnknownHostException(hostname)
            }
            catch (error: InterruptedException) { Thread.currentThread().interrupt(); throw failed(hostname, error) }
            catch (error: TimeoutException) { throw failed(hostname, error) }
        }
        var acquired = false
        try {
            acquired = slots.tryAcquire(2, TimeUnit.SECONDS)
            if (!acquired) throw UnknownHostException("DoH query queue is busy")
            val result = delegate.lookup(hostname)
            if (result.isEmpty()) throw UnknownHostException(hostname)
            future.result = result
            return result
        } catch (error: Exception) {
            if (generateSequence<Throwable>(error) { it.cause }.take(12).any { it is InterruptedException }) {
                Thread.currentThread().interrupt()
            }
            val failure = failed(hostname, error)
            future.failure = failure
            throw failure
        } finally {
            future.latch.countDown()
            if (acquired) slots.release()
            inFlight.remove(hostname, future)
        }
    }

    private fun failed(host: String, cause: Throwable): UnknownHostException =
        cause as? UnknownHostException ?: UnknownHostException(host).apply { initCause(cause) }
}
