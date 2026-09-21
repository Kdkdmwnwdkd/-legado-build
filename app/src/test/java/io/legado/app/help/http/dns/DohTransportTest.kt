package io.legado.app.help.http.dns

import okhttp3.Dns
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import java.io.ByteArrayOutputStream

class DohTransportTest {
    @get:Rule val folder = TemporaryFolder()

    private fun secureServer(host: String = "doh.example"): Pair<MockWebServer, HandshakeCertificates> {
        val certificate = HeldCertificate.Builder().commonName(host).addSubjectAlternativeName(host).build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        return server to clientCertificates
    }

    private fun config(server: MockWebServer, ipv6: Boolean = false) = NetworkDnsConfig(
        enabled = true, endpoint = "https://doh.example:${server.port}/dns-query",
        bootstrap = listOf("127.0.0.1"), includeIpv6 = ipv6
    )

    private fun answer(request: RecordedRequest): MockResponse {
        val query = requireNotNull(request.requestUrl?.queryParameter("dns")?.decodeBase64())
        val type = ((query[query.size - 4].toInt() and 255) shl 8) or (query[query.size - 3].toInt() and 255)
        val address = requireNotNull(DnsNames.literal(if (type == 28) "2001:db8::1" else "93.184.216.34")).address
        val response = Buffer().write(query.substring(0, 2)).writeShort(0x8180)
            .writeShort(1).writeShort(1).writeShort(0).writeShort(0)
            .write(query.substring(12)).writeShort(0xc00c).writeShort(type)
            .writeShort(1).writeInt(60).writeShort(address.size).write(address)
        return MockResponse().setHeader("Content-Type", "application/dns-message")
            .setHeader("Cache-Control", "max-age=60").setBody(response)
    }

    @Test fun realWireQueriesResolveAAndAAAAAndRespectHttpCache() {
        val (server, trusted) = secureServer()
        server.use {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = answer(request)
            }
            DohTransport(config(server, ipv6 = true), folder.newFolder("cache")) {
                sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
                dns(Dns { error("Bootstrap must prevent a system query for the DoH endpoint") })
                proxy(Proxy.NO_PROXY)
            }.use { transport ->
                val first = transport.dns.lookup("book.example")
                assertEquals(setOf("93.184.216.34", "2001:db8:0:0:0:0:0:1"), first.map { it.hostAddress }.toSet())
                assertEquals(first.toSet(), transport.dns.lookup("book.example").toSet())
                assertEquals(2, server.requestCount)
                val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals("application/dns-message", request.getHeader("Accept"))
                assertNull(request.getHeader("Cookie"))
            }
        }
    }

    @Test fun disabledIpv6IssuesOnlyAQueryAndNoCacheWhenDirectoryIsAbsent() {
        val (server, trusted) = secureServer()
        server.use {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = answer(request)
            }
            DohTransport(config(server)) {
                sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
                proxy(Proxy.NO_PROXY)
            }.use { transport ->
                repeat(2) { assertEquals("93.184.216.34", transport.dns.lookup("book.example").single().hostAddress) }
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun defaultClientRejectsUntrustedCertificate() {
        val (server, _) = secureServer()
        server.use {
            DohTransport(config(server)) { proxy(Proxy.NO_PROXY) }.use { transport ->
                assertThrows(UnknownHostException::class.java) { transport.dns.lookup("book.example") }
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test fun trustedCertificateStillHasToMatchOriginalEndpointHostname() {
        val (server, trusted) = secureServer("different.example")
        server.use {
            DohTransport(config(server)) {
                sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
                proxy(Proxy.NO_PROXY)
            }.use { transport ->
                assertThrows(UnknownHostException::class.java) { transport.dns.lookup("book.example") }
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test fun redirectsAndServerErrorsFailWithoutFollowingLocation() {
        val (server, trusted) = secureServer()
        server.use {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://elsewhere.example/dns-query"))
            server.enqueue(MockResponse().setResponseCode(503))
            DohTransport(config(server)) {
                sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
                proxy(Proxy.NO_PROXY)
            }.use { transport ->
                repeat(2) { assertThrows(UnknownHostException::class.java) { transport.dns.lookup("book.example") } }
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun oversizedChunkedAndGzipResponsesAreBoundedBeforeDecoding() {
        val (server, trusted) = secureServer()
        server.use {
            val oversized = ByteArray(70 * 1024) { 42 }
            val compressed = ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write(oversized) } }.toByteArray()
            server.enqueue(MockResponse().setChunkedBody(Buffer().write(oversized), 1024))
            server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(Buffer().write(compressed)))
            DohTransport(config(server)) {
                sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
                proxy(Proxy.NO_PROXY)
            }.use { transport ->
                repeat(2) {
                    val failure = assertThrows(UnknownHostException::class.java) { transport.dns.lookup("book.example") }
                    assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it.message?.contains("64 KiB") == true })
                }
            }
        }
    }

    @Test fun coalescingSharesConcurrentWorkButDoesNotCacheResults() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val dns = CoalescingDns(Dns {
            requests.incrementAndGet()
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            listOf(requireNotNull(DnsNames.literal("192.0.2.1")))
        })
        val workers = Executors.newFixedThreadPool(6)
        try {
            val leader = workers.submit<List<java.net.InetAddress>> { dns.lookup("book.example") }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val followerStarted = CountDownLatch(5)
            val followers = (1..5).map { workers.submit<List<java.net.InetAddress>> {
                followerStarted.countDown()
                dns.lookup("book.example")
            } }
            assertTrue(followerStarted.await(2, TimeUnit.SECONDS))
            // Wait until all workers are blocked in the resolver, without timing
            // a network service or requiring a fast machine.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (workers is java.util.concurrent.ThreadPoolExecutor && workers.activeCount < 6 && System.nanoTime() < deadline) Thread.yield()
            Thread.sleep(50)
            release.countDown()
            val expected = leader.get(2, TimeUnit.SECONDS)
            followers.forEach { assertEquals(expected, it.get(2, TimeUnit.SECONDS)) }
            assertEquals(1, requests.get())
            dns.lookup("book.example")
            assertEquals(2, requests.get())
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun aFailedInflightQueryDoesNotPoisonTheNextAttempt() {
        val requests = AtomicInteger()
        val dns = CoalescingDns(Dns {
            if (requests.incrementAndGet() == 1) throw UnknownHostException("first attempt")
            listOf(requireNotNull(DnsNames.literal("192.0.2.1")))
        })
        assertThrows(UnknownHostException::class.java) { dns.lookup("book.example") }
        assertEquals("192.0.2.1", dns.lookup("book.example").single().hostAddress)
        assertEquals(2, requests.get())
    }
}
