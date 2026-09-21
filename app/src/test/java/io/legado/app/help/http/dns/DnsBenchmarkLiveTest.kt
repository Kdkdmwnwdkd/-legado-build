package io.legado.app.help.http.dns

import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in network measurement using exactly the resolver code used by the UI.
 * A service being unreachable is a measured result, not a unit-test failure. */
class DnsBenchmarkLiveTest {
    @Test fun measureConfiguredProvidersOnBuildMachine() {
        val report = System.getProperty("legado.dns.benchmark.report")
        assumeTrue("Only run when an explicit report path is supplied", !report.isNullOrBlank())
        val config = NetworkDnsConfig(includeIpv6 = true)
        val results = DnsBenchmark.providers(config).map { DnsBenchmark.measure(config, it, "example.com") }
        assertEquals(5, results.size)
        assertEquals(15, results.sumOf { it.samples.size })
        val record = mapOf("measuredAt" to java.time.Instant.now().toString(),
            "network" to "Windows build machine; not Android device", "physicalDeviceVerified" to false,
            "host" to "example.com", "includeIpv6" to true, "dohCacheEnabled" to false,
            "fallbackEnabled" to false, "hostsApplied" to false,
            "results" to results.map { result -> mapOf("name" to result.name, "provider" to result.provider,
                "samples" to result.samples, "successCount" to result.successCount,
                "firstMilliseconds" to result.firstMilliseconds, "medianMilliseconds" to result.medianMilliseconds) })
        File(requireNotNull(report)).writeText(GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(record))
    }
}
