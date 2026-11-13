package com.shimmer.reader.help.http.dns

import com.shimmer.reader.constant.PreferKey
import com.shimmer.reader.lib.cronet.CronetInterceptor
import com.shimmer.reader.lib.cronet.CronetCoroutineInterceptor
import com.shimmer.reader.utils.defaultSharedPreferences
import okhttp3.Dns
import okhttp3.OkHttpClient
import splitties.init.appCtx
import java.io.File
import java.util.EnumMap

/** One process snapshot shared by every scope. Preferences are applied after a
 * restart so clients retained by Glide/WebSocket cannot silently keep old routes. */
object AppDns {
    val config: NetworkDnsConfig by lazy { NetworkDnsStore.load(appCtx) }
    private val hosts by lazy {
        DnsHosts.parse(appCtx.defaultSharedPreferences.getString(PreferKey.customHosts, null))
    }
    private val transport by lazy {
        DohTransport(config, File(appCtx.cacheDir, "network-doh-v1"))
    }
    private val resolvers by lazy {
        EnumMap<DnsScope, ScopedDns>(DnsScope::class.java).apply {
            DnsScope.entries.forEach { scope -> put(scope, ScopedDns(config, scope, hosts, Dns.SYSTEM) { transport.dns }) }
        }
    }

    fun resolver(scope: DnsScope): ScopedDns = requireNotNull(resolvers[scope])

    fun requiresOkHttp(scope: DnsScope): Boolean = resolver(scope).requiresOkHttp
}

fun OkHttpClient.Builder.withDnsScope(scope: DnsScope): OkHttpClient.Builder = apply {
    dns(AppDns.resolver(scope))
    if (AppDns.requiresOkHttp(scope)) withoutCronet()
}

fun OkHttpClient.Builder.withoutCronet(): OkHttpClient.Builder = apply {
    interceptors().removeAll { it is CronetInterceptor || it is CronetCoroutineInterceptor }
}
