package io.legado.app.help.http.dns

import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class NetworkDnsPolicyTest {
    private val publicIp = requireNotNull(DnsNames.literal("93.184.216.34"))
    private val localIp = requireNotNull(DnsNames.literal("192.168.1.1"))

    @Test fun disabledByDefaultAndMasterSwitchWinsOverDomainRules() {
        val config = NetworkDnsConfig(rules = listOf(DnsDomainRule("example.com", true)))
        DnsScope.entries.forEach { scope ->
            assertFalse(config.usesDoh(scope, "example.com"))
            assertFalse(config.mayUseDoh(scope))
        }
    }

    @Test fun nineFunctionScopesAreIndependent() {
        assertEquals(9, DnsScope.entries.size)
        DnsScope.entries.forEach { selected ->
            val config = NetworkDnsConfig(enabled = true, scopes = setOf(selected))
            DnsScope.entries.forEach { scope -> assertEquals(scope == selected, config.usesDoh(scope, "example.com")) }
        }
    }

    @Test fun preciseRulesAndLongestSuffixOverrideFunctionSwitches() {
        val config = NetworkDnsConfig(enabled = true, scopes = emptySet(), rules = listOf(
            DnsDomainRule("*.example.com", true), DnsDomainRule("*.private.example.com", false),
            DnsDomainRule("book.private.example.com", true), DnsDomainRule("ad.example.com", false)
        )).validated()
        assertTrue(config.usesDoh(DnsScope.IMAGE, "cover.example.com"))
        assertTrue(config.usesDoh(DnsScope.IMAGE, "BOOK.PRIVATE.EXAMPLE.COM."))
        for (host in listOf("ad.example.com", "other.private.example.com", "example.com", "evil-example.com", "example.com.evil.net")) {
            assertFalse(host, config.usesDoh(DnsScope.IMAGE, host))
        }
        assertTrue(config.mayUseDoh(DnsScope.IMAGE)) // Redirects must also honor domain exceptions.
    }

    @Test fun localNamesStayLocalUnlessExplicitlyRouted() {
        val config = NetworkDnsConfig(enabled = true)
        for (host in listOf("localhost", "reader", "reader.local", "nas.lan", "svc.internal", "r.home.arpa", "127.0.0.1", "[::1]")) {
            assertFalse(host, config.usesDoh(DnsScope.SYNC, host))
        }
        assertTrue(config.copy(rules = listOf(DnsDomainRule("nas.lan", true))).usesDoh(DnsScope.SYNC, "nas.lan"))
    }

    @Test fun configRoundTripsWithoutChangingSelections() {
        val config = NetworkDnsConfig(enabled = true, scopes = setOf(DnsScope.IMAGE, DnsScope.SYNC),
            bootstrap = listOf("1.1.1.1", "2606:4700:4700::1111"), includeIpv6 = false,
            fallbackToSystem = true, localNamesUseSystem = false,
            rules = listOf(DnsDomainRule("*.EXAMPLE.COM.", false))).validated()
        assertEquals(config, NetworkDnsConfig.decode(config.encode()))
        assertEquals(NetworkDnsConfig(), NetworkDnsConfig.decode(null))
        assertEquals("xn--bcher-kva.example", DnsNames.host("bücher.example"))
        assertEquals("legacy_host.example", DnsNames.host("LEGACY_HOST.example"))
    }

    @Test fun invalidSettingsAreRejectedBeforeSaving() {
        for (endpoint in listOf("http://dns.example/query", "https://u:p@dns.example/query", "https://dns.example/#x", "https://dns.example/?dns", "https://dns.example/?dns=abc")) {
            assertThrows(IllegalArgumentException::class.java) { NetworkDnsConfig(endpoint = endpoint).validated() }
        }
        assertThrows(IllegalArgumentException::class.java) { NetworkDnsConfig(bootstrap = listOf("dns.example")).validated() }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkDnsConfig(rules = listOf(DnsDomainRule("example.com", true), DnsDomainRule("EXAMPLE.COM.", false))).validated()
        }
        assertThrows(IllegalArgumentException::class.java) { NetworkDnsConfig.decode("{\"version\":2}") }
        for (value in listOf("*example.com", "https://example.com", "a..example.com", "192.0.2.1")) {
            assertThrows(IllegalArgumentException::class.java) { DnsDomainRule(value, true).normalized() }
        }
    }

    @Test fun literalAddressesNeverUseDns() {
        val unexpected = Dns { error("Must not look up a literal address") }
        val resolver = ScopedDns(NetworkDnsConfig(enabled = true), DnsScope.READING, DnsHosts.parse(null), unexpected) { unexpected }
        assertEquals(localIp, resolver.lookup("192.168.1.1").single())
        assertTrue(resolver.lookup("::1").single().isLoopbackAddress)
        assertNull(DnsNames.literal("999.1.2.3"))
        assertNull(DnsNames.literal("domain.example"))
    }

    @Test fun hostsWinAndAliasesUseTheSelectedResolverWithoutRecursiveLookups() {
        val asked = mutableListOf<String>()
        val doh = Dns { asked.add(it); listOf(publicIp) }
        val hosts = DnsHosts.parse("""{"fixed.example":["192.168.1.1"],"alias.example":"target.example","target.example":"alias.example"}""", strict = true)
        val resolver = ScopedDns(NetworkDnsConfig(enabled = true), DnsScope.READING, hosts, Dns { error("Unexpected system DNS") }) { doh }
        assertEquals(localIp, resolver.lookup("FIXED.EXAMPLE.").single())
        assertTrue(asked.isEmpty())
        assertEquals(publicIp, resolver.lookup("alias.example").single())
        assertEquals(listOf("target.example"), asked)
        assertTrue(resolver.requiresOkHttp)
        assertTrue(ScopedDns(NetworkDnsConfig(), DnsScope.READING, hosts, doh) { doh }.requiresOkHttp)
    }

    @Test fun malformedHostsDoNotCrashStartupAndCannotBeSaved() {
        for (raw in listOf("broken", "[]", """{"a.example":["1.2.3.4",42]}""", """{"a.example":["bad/host"]}""")) {
            DnsHosts.parse(raw)
            assertThrows(IllegalArgumentException::class.java) { DnsHosts.parse(raw, strict = true) }
        }
        assertTrue(DnsHosts.parse("broken").isEmpty)
        assertTrue(DnsHosts.parse("", strict = true).isEmpty)
    }

    @Test fun failedDohDoesNotSilentlyUseSystemDns() {
        var systemQueries = 0
        val system = Dns { systemQueries++; listOf(publicIp) }
        val config = NetworkDnsConfig(enabled = true)
        val failing = Dns { throw UnknownHostException("test DoH failure") }
        val strict = ScopedDns(config, DnsScope.READING, DnsHosts.parse(null), system) { failing }
        assertThrows(UnknownHostException::class.java) { strict.lookup("example.com") }
        assertEquals(0, systemQueries)
        val fallback = ScopedDns(config.copy(fallbackToSystem = true), DnsScope.READING, DnsHosts.parse(null), system) { failing }
        assertEquals(publicIp, fallback.lookup("example.com").single())
        assertEquals(1, systemQueries)
    }

    @Test fun hostOverrideIsLimitedToOriginalHostAndCapturesDelegate() {
        val queries = mutableListOf<String>()
        val delegate = Dns { queries.add(it); listOf(publicIp) }
        val resolver = HostOverrideDns("book.example", listOf("192.168.1.1"), delegate)
        assertEquals(localIp, resolver.lookup("BOOK.EXAMPLE.").single())
        assertEquals(publicIp, resolver.lookup("redirect.example").single())
        assertEquals(publicIp, resolver.lookup("proxy.example").single())
        assertEquals(listOf("redirect.example", "proxy.example"), queries)
    }

    @Test fun dohBootstrapDoesNotBreakProxyHostResolution() {
        val queries = mutableListOf<String>()
        val resolver = EndpointBootstrapDns("doh.example", listOf("192.168.1.1"), Dns { queries.add(it); listOf(publicIp) })
        assertEquals(localIp, resolver.lookup("doh.example").single())
        assertEquals(publicIp, resolver.lookup("proxy.example").single())
        assertEquals(listOf("proxy.example"), queries)
    }

    @Test fun blockingScriptScopeIsNestedAndRestoredAfterFailure() {
        assertNull(DnsRequestContext.scope)
        DnsRequestContext.withScope(DnsScope.MEDIA) {
            assertEquals(DnsScope.MEDIA, DnsRequestContext.scope)
            assertThrows(IllegalStateException::class.java) {
                DnsRequestContext.withScope(DnsScope.IMAGE) {
                    assertEquals(DnsScope.IMAGE, DnsRequestContext.scope)
                    error("failed script")
                }
            }
            assertEquals(DnsScope.MEDIA, DnsRequestContext.scope)
            val otherThread = java.util.concurrent.Executors.newSingleThreadExecutor()
            try { assertNull(otherThread.submit<DnsScope?> { DnsRequestContext.scope }.get()) }
            finally { otherThread.shutdownNow() }
        }
        assertNull(DnsRequestContext.scope)
    }

    @Test fun benchmarkIncludesCustomizedBootstrapForPresetUrl() {
        assertEquals(5, DnsBenchmark.providers(NetworkDnsConfig()).size)
        val config = NetworkDnsConfig(bootstrap = listOf("192.0.2.53"))
        val providers = DnsBenchmark.providers(config)
        assertEquals(6, providers.size)
        assertEquals(config.endpoint, providers.last()?.url)
        assertEquals(config.bootstrap, providers.last()?.bootstrap)
        assertEquals("custom", providers.last()?.id)
    }
}
