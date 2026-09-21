package io.legado.app.help.http.dns

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.IDN
import java.net.InetAddress
import java.util.Locale

enum class DnsScope(val key: String, val title: String) {
    READING("reading", "书源、搜索与正文"),
    IMAGE("image", "封面、漫画与正文图片"),
    AI("ai", "AI、MCP 与 AI 工具"),
    MEDIA("media", "HTTP 朗读与音视频"),
    WEBSOCKET("websocket", "脚本 WebSocket"),
    SYNC("sync", "WebDAV 与云同步"),
    IMPORT("import", "在线导入"),
    RELAY("relay", "远程分享与中继"),
    OTHER("other", "其它应用请求")
}

data class DnsDomainRule(val pattern: String, val doh: Boolean) {
    fun matches(host: String): Boolean = if (pattern.startsWith("*.")) {
        host.endsWith(pattern.substring(1)) && host != pattern.substring(2)
    } else host == pattern

    fun normalized(): DnsDomainRule {
        val wildcard = pattern.trim().startsWith("*.")
        val value = DnsNames.host(pattern.trim().removePrefix("*."))
        require(value != null && DnsNames.literal(value) == null) { "请输入域名，例如 example.com 或 *.example.com" }
        return copy(pattern = if (wildcard) "*.$value" else value)
    }
}

data class DohProvider(val id: String, val name: String, val url: String, val bootstrap: List<String>) {
    companion object {
        val presets = listOf(
            DohProvider("alidns", "阿里 DNS", "https://dns.alidns.com/dns-query", listOf("223.5.5.5", "223.6.6.6")),
            DohProvider("dnspod", "腾讯 DNSPod", "https://doh.pub/dns-query", listOf("1.12.12.12", "120.53.53.53")),
            DohProvider("cloudflare", "Cloudflare", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1")),
            DohProvider("google", "Google", "https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4"))
        )
    }
}

data class NetworkDnsConfig(
    val enabled: Boolean = false,
    val endpoint: String = DohProvider.presets.first().url,
    val bootstrap: List<String> = DohProvider.presets.first().bootstrap,
    val scopes: Set<DnsScope> = DnsScope.entries.toSet(),
    val rules: List<DnsDomainRule> = emptyList(),
    val fallbackToSystem: Boolean = false,
    val includeIpv6: Boolean = true,
    val localNamesUseSystem: Boolean = true
) {
    fun usesDoh(scope: DnsScope, hostname: String): Boolean {
        if (!enabled) return false
        val host = DnsNames.host(hostname) ?: return false
        if (DnsNames.literal(host) != null) return false
        // Exact rules win; otherwise the longest matching suffix wins. A local
        // domain may explicitly use a private DoH service through an exact rule.
        rules.filter { it.matches(host) }
            .maxWithOrNull(compareBy<DnsDomainRule> { !it.pattern.startsWith("*.") }.thenBy { it.pattern.length })
            ?.let { return it.doh }
        if (localNamesUseSystem && DnsNames.isLocalName(host)) return false
        return scope in scopes
    }

    fun mayUseDoh(scope: DnsScope): Boolean = enabled && (scope in scopes || rules.any { it.doh })

    fun validated(): NetworkDnsConfig {
        val url = endpoint.trim().toHttpUrlOrNull()
        require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) {
            "DoH 地址必须是 HTTPS 地址，不能包含用户名、密码或片段"
        }
        require("dns" !in url.queryParameterNames) { "DoH 地址不能预填 dns 查询参数" }
        require(bootstrap.size <= 8) { "启动 IP 最多 8 个" }
        val ips = bootstrap.map { value ->
            val address = DnsNames.literal(value.trim())
            require(address != null) { "启动地址只接受 IPv4 或 IPv6，不接受域名" }
            address.hostAddress ?: error("无效启动 IP")
        }.distinct()
        require(rules.size <= 128) { "域名规则最多 128 条" }
        val normalizedRules = rules.map { it.normalized() }
        require(normalizedRules.distinctBy { it.pattern }.size == normalizedRules.size) { "域名规则重复" }
        return copy(endpoint = url.toString(), bootstrap = ips, rules = normalizedRules)
    }

    fun encode(): String = JsonObject().apply {
        addProperty("version", 1)
        addProperty("enabled", enabled)
        addProperty("endpoint", endpoint)
        add("bootstrap", JsonArray().also { array -> bootstrap.forEach(array::add) })
        add("scopes", JsonArray().also { array -> scopes.forEach { array.add(it.key) } })
        add("rules", JsonArray().also { array -> rules.forEach { rule ->
            array.add(JsonObject().apply { addProperty("pattern", rule.pattern); addProperty("doh", rule.doh) })
        } })
        addProperty("fallbackToSystem", fallbackToSystem)
        addProperty("includeIpv6", includeIpv6)
        addProperty("localNamesUseSystem", localNamesUseSystem)
    }.toString()

    companion object {
        fun decode(raw: String?): NetworkDnsConfig {
            if (raw.isNullOrBlank()) return NetworkDnsConfig()
            require(raw.length <= 64 * 1024) { "DNS 配置过大" }
            val json = JsonParser.parseString(raw).asJsonObject
            require(json.get("version")?.asInt == 1) { "不支持的 DNS 配置版本" }
            val defaults = NetworkDnsConfig()
            return NetworkDnsConfig(
                enabled = json.get("enabled")?.asBoolean ?: false,
                endpoint = json.get("endpoint")?.asString ?: defaults.endpoint,
                bootstrap = json.getAsJsonArray("bootstrap")?.map { it.asString } ?: defaults.bootstrap,
                scopes = json.getAsJsonArray("scopes")?.mapNotNull { item -> DnsScope.entries.find { it.key == item.asString } }?.toSet() ?: defaults.scopes,
                rules = json.getAsJsonArray("rules")?.map { item ->
                    val rule = item.asJsonObject
                    DnsDomainRule(rule.get("pattern").asString, rule.get("doh").asBoolean)
                } ?: emptyList(),
                fallbackToSystem = json.get("fallbackToSystem")?.asBoolean ?: false,
                includeIpv6 = json.get("includeIpv6")?.asBoolean ?: true,
                localNamesUseSystem = json.get("localNamesUseSystem")?.asBoolean ?: true
            ).validated()
        }
    }
}

object DnsNames {
    fun host(value: String): String? = runCatching {
        val host = value.trim().trimEnd('.').removeSurrounding("[", "]")
        if (literal(host) != null) return@runCatching host.lowercase(Locale.ROOT)
        require(host.isNotEmpty() && host.length <= 253 && host.none { it in "/:@?#*\\%" || it.isWhitespace() })
        // Accept legacy web hosts containing underscores, as OkHttp and the
        // previous system resolver do. Still reject URL delimiters and controls.
        IDN.toASCII(host).lowercase(Locale.ROOT).also { ascii ->
            require(ascii.length <= 253 && ascii.split('.').all { label ->
                label.isNotEmpty() && label.length <= 63 && label.all { it in 'a'..'z' || it in '0'..'9' || it in "_-" }
            })
        }
    }.getOrNull()

    fun literal(value: String): InetAddress? {
        val text = value.removeSurrounding("[", "]")
        if (text.contains(':')) {
            if (text.any { it !in "0123456789abcdefABCDEF:." }) return null
            return runCatching { InetAddress.getByName(text) }.getOrNull()
        }
        val parts = text.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { ch -> ch !in '0'..'9' } }) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        if (numbers.any { it !in 0..255 }) return null
        return InetAddress.getByAddress(numbers.map { it.toByte() }.toByteArray())
    }

    fun isLocalName(host: String): Boolean = !host.contains('.') || host == "localhost" ||
        listOf(".localhost", ".local", ".lan", ".internal", ".home.arpa", ".test", ".invalid").any(host::endsWith)
}
