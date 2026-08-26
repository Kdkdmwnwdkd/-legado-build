package io.legado.app.help

import android.util.Base64
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.SSLHelper
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

object JsWebSocketManager {

    private const val MAX_CONNECTIONS = 16
    private const val MAX_EVENTS_PER_CONNECTION = 128
    private const val MAX_MESSAGE_BYTES = 1_048_576
    private const val DEFAULT_OPEN_TIMEOUT_MS = 15_000L
    private const val DEFAULT_READ_TIMEOUT_MS = 30_000L
    private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L

    private val client: OkHttpClient by lazy {
        val specs = arrayListOf(
            ConnectionSpec.MODERN_TLS,
            ConnectionSpec.COMPATIBLE_TLS,
            ConnectionSpec.CLEARTEXT
        )
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .sslSocketFactory(SSLHelper.unsafeSSLSocketFactory, SSLHelper.unsafeTrustManager)
            .hostnameVerifier(SSLHelper.unsafeHostnameVerifier)
            .connectionSpecs(specs)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .apply {
                if (AppConfig.addressCache.isNotEmpty()) {
                    dns { hostname ->
                        AppConfig.addressCache[hostname] ?: Dns.SYSTEM.lookup(hostname)
                    }
                }
            }
            .build()
    }
    private val connections = ConcurrentHashMap<String, Connection>()

    fun open(scopeKey: String, id: String, url: String, headerJson: String?, timeoutMs: Long): String {
        cleanupIdle()
        val key = key(scopeKey, id)
        connections[key]?.let { old ->
            if (old.url == url && old.isOpen) {
                old.touch()
                return ok(JSONObject().put("reused", true))
            }
            close(scopeKey, id)
        }
        if (connections.size >= MAX_CONNECTIONS) {
            closeOldest()
        }
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .headers(parseHeaders(headerJson))
                .build()
            val connection = Connection(url)
            connection.webSocket = client.newWebSocket(request, connection)
            connections[key] = connection
            if (!connection.openLatch.await(timeoutMs.coercePositive(DEFAULT_OPEN_TIMEOUT_MS), TimeUnit.MILLISECONDS)) {
                connections.remove(key)
                connection.close()
                return error("WebSocket open timeout")
            }
            connection.error?.let { throw it }
            ok(JSONObject().put("reused", false))
        }.getOrElse {
            connections.remove(key)?.close()
            error(it.message ?: it.javaClass.simpleName)
        }
    }

    fun sendText(scopeKey: String, id: String, text: String): String {
        val connection = connections[key(scopeKey, id)] ?: return error("WebSocket not opened")
        connection.touch()
        return if (connection.webSocket?.send(text) == true) {
            ok()
        } else {
            error("WebSocket send failed")
        }
    }

    fun sendBase64(scopeKey: String, id: String, base64: String): String {
        val connection = connections[key(scopeKey, id)] ?: return error("WebSocket not opened")
        return runCatching {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            connection.touch()
            if (connection.webSocket?.send(ByteString.of(*bytes)) == true) {
                ok()
            } else {
                error("WebSocket send failed")
            }
        }.getOrElse {
            error(it.message ?: it.javaClass.simpleName)
        }
    }

    fun lock(scopeKey: String, id: String, timeoutMs: Long): String {
        val connection = connections[key(scopeKey, id)] ?: return error("WebSocket not opened")
        connection.touch()
        val locked = connection.lock.tryLock(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        return if (locked) ok() else error("WebSocket lock timeout")
    }

    fun unlock(scopeKey: String, id: String): String {
        val connection = connections[key(scopeKey, id)] ?: return ok()
        return runCatching {
            if (connection.lock.isHeldByCurrentThread) {
                connection.lock.unlock()
            }
            ok()
        }.getOrElse {
            error(it.message ?: it.javaClass.simpleName)
        }
    }

    fun read(scopeKey: String, id: String, timeoutMs: Long): String {
        val connection = connections[key(scopeKey, id)] ?: return error("WebSocket not opened")
        connection.touch()
        val event = connection.events.poll(
            timeoutMs.coerceAtLeast(0L),
            TimeUnit.MILLISECONDS
        )
        return when (event) {
            null -> error("WebSocket read timeout")
            is Event.Text -> ok(
                JSONObject()
                    .put("type", "text")
                    .put("text", event.text)
            )
            is Event.Binary -> ok(
                JSONObject()
                    .put("type", "binary")
                    .put("base64", Base64.encodeToString(event.bytes, Base64.NO_WRAP))
            )
            is Event.Closed -> ok(
                JSONObject()
                    .put("type", "closed")
                    .put("code", event.code)
                    .put("reason", event.reason)
            )
            is Event.Failure -> error(event.message)
        }
    }

    fun close(scopeKey: String, id: String): String {
        connections.remove(key(scopeKey, id))?.close()
        return ok()
    }

    private fun parseHeaders(headerJson: String?): Headers {
        val builder = Headers.Builder()
        if (headerJson.isNullOrBlank()) return builder.build()
        val obj = JSONObject(headerJson)
        obj.keys().forEach { name ->
            val value = obj.optString(name)
            if (name.isNotBlank() && value.isNotBlank()) {
                builder.set(name, value)
            }
        }
        return builder.build()
    }

    private fun cleanupIdle() {
        val now = System.currentTimeMillis()
        connections.entries.removeIf { entry ->
            val idle = now - entry.value.lastTouchAt > IDLE_TIMEOUT_MS
            if (idle) entry.value.close()
            idle
        }
    }

    private fun closeOldest() {
        val oldest = connections.entries.minByOrNull { it.value.lastTouchAt } ?: return
        connections.remove(oldest.key)?.close()
    }

    private fun key(scopeKey: String, id: String): String = "$scopeKey:$id"

    private fun Long.coercePositive(defaultValue: Long): Long {
        return if (this > 0L) this else defaultValue
    }

    private fun ok(extra: JSONObject = JSONObject()): String {
        extra.put("ok", true)
        return extra.toString()
    }

    private fun error(message: String): String {
        return JSONObject()
            .put("ok", false)
            .put("error", message)
            .toString()
    }

    private class Connection(
        val url: String
    ) : WebSocketListener() {

        val openLatch = CountDownLatch(1)
        val events = LinkedBlockingQueue<Event>(MAX_EVENTS_PER_CONNECTION)
        val lock = ReentrantLock()
        @Volatile
        var webSocket: WebSocket? = null
        @Volatile
        var error: Throwable? = null
        @Volatile
        var isOpen: Boolean = false
        @Volatile
        var lastTouchAt: Long = System.currentTimeMillis()

        fun touch() {
            lastTouchAt = System.currentTimeMillis()
        }

        fun close() {
            runCatching { webSocket?.close(1000, "closed") }
            webSocket = null
            isOpen = false
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            this.webSocket = webSocket
            isOpen = true
            touch()
            openLatch.countDown()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            touch()
            if (text.toByteArray().size > MAX_MESSAGE_BYTES) {
                offerEvent(Event.Failure("WebSocket message is too large"))
                webSocket.close(1009, "message too large")
                return
            }
            offerEvent(Event.Text(text))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            touch()
            if (bytes.size > MAX_MESSAGE_BYTES) {
                offerEvent(Event.Failure("WebSocket message is too large"))
                webSocket.close(1009, "message too large")
                return
            }
            offerEvent(Event.Binary(bytes.toByteArray()))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isOpen = false
            offerEvent(Event.Closed(code, reason))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            error = t
            isOpen = false
            openLatch.countDown()
            offerEvent(Event.Failure(t.message ?: t.javaClass.simpleName))
        }

        private fun offerEvent(event: Event) {
            while (!events.offer(event)) {
                events.poll() ?: break
            }
        }
    }

    private sealed class Event {
        data class Text(val text: String) : Event()
        data class Binary(val bytes: ByteArray) : Event()
        data class Closed(val code: Int, val reason: String) : Event()
        data class Failure(val message: String) : Event()
    }
}
