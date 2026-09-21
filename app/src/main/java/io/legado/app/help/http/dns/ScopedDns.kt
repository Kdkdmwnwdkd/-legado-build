package io.legado.app.help.http.dns

import com.google.gson.JsonParser
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/** Parsed values retain legacy hostname aliases; resolving them uses the selected
 * resolver, never a hidden InetAddress/system lookup while parsing preferences. */
class DnsHosts private constructor(val entries: Map<String, List<String>>) {
    val isEmpty: Boolean get() = entries.isEmpty()

    companion object {
        fun parse(raw: String?, strict: Boolean = false): DnsHosts {
            if (raw.isNullOrBlank()) return DnsHosts(emptyMap())
            return runCatching {
                require(raw.length <= 256 * 1024) { "Hosts 配置过大" }
                val json = JsonParser.parseString(raw).asJsonObject
                val result = linkedMapOf<String, List<String>>()
                for ((key, value) in json.entrySet()) {
                    val host = DnsNames.host(key)
                    val values = when {
                        value.isJsonArray -> value.asJsonArray.mapNotNull {
                            val valid = it.isJsonPrimitive && it.asJsonPrimitive.isString
                            if (strict) require(valid) { "Hosts 地址必须是字符串" }
                            if (valid) it.asString else null
                        }
                        value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString.split(',')
                        else -> emptyList()
                    }
                    val targets = values.mapNotNull { DnsNames.host(it) }.distinct()
                    if (strict) require(host != null && targets.isNotEmpty() && targets.size == values.map { it.trim() }.distinct().size) {
                        "Hosts 必须是域名到 IP 或域名列表的映射"
                    }
                    if (strict) require(host !in result) { "Hosts 域名重复" }
                    if (host != null && targets.isNotEmpty()) result[host] = targets
                }
                DnsHosts(result)
            }.getOrElse { if (strict) throw IllegalArgumentException("Hosts 格式无效：${it.message}", it) else DnsHosts(emptyMap()) }
        }
    }
}

class ScopedDns(
    private val config: NetworkDnsConfig,
    val scope: DnsScope,
    private val hosts: DnsHosts,
    private val system: Dns,
    private val doh: () -> Dns
) : Dns {
    val requiresOkHttp: Boolean get() = !hosts.isEmpty || config.mayUseDoh(scope)

    override fun lookup(hostname: String): List<InetAddress> {
        val host = DnsNames.host(hostname) ?: throw UnknownHostException("Invalid hostname")
        DnsNames.literal(host)?.let { return listOf(it) }
        val targets = hosts.entries[host]
        if (targets != null) {
            val addresses = mutableListOf<InetAddress>()
            var failure: UnknownHostException? = null
            targets.forEach { target ->
                try { addresses.addAll(DnsNames.literal(target)?.let(::listOf) ?: resolve(target)) }
                catch (error: UnknownHostException) { failure = error }
            }
            if (addresses.isNotEmpty()) return addresses.distinct()
            throw failure ?: UnknownHostException(host)
        }
        return resolve(host)
    }

    private fun resolve(host: String): List<InetAddress> {
        if (!config.usesDoh(scope, host)) return nonEmpty(system.lookup(host), host)
        return try { nonEmpty(doh().lookup(host), host) }
        catch (error: UnknownHostException) {
            if (!config.fallbackToSystem || Thread.currentThread().isInterrupted) throw error
            try { nonEmpty(system.lookup(host), host) }
            catch (fallback: UnknownHostException) { fallback.addSuppressed(error); throw fallback }
        }
    }

    private fun nonEmpty(addresses: List<InetAddress>, host: String): List<InetAddress> {
        if (addresses.isEmpty()) throw UnknownHostException(host)
        return addresses
    }
}

/** An override belongs to one request's original host, not every redirect or
 * proxy hostname. Its delegate is captured before installing the wrapper. */
class HostOverrideDns(private val host: String, private val values: List<String>, private val delegate: Dns) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (DnsNames.host(hostname) != host || values.isEmpty()) return delegate.lookup(hostname)
        val result = mutableListOf<InetAddress>()
        var failure: UnknownHostException? = null
        values.forEach { value ->
            try { result.addAll(DnsNames.literal(value)?.let(::listOf) ?: delegate.lookup(value)) }
            catch (error: UnknownHostException) { failure = error }
        }
        if (result.isEmpty()) throw failure ?: UnknownHostException(hostname)
        return result.distinct()
    }
}
