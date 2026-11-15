package com.shimmer.reader.help.source

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class HostConcurrencyGateTest {

    @Test
    fun `同一个 host 的并发不超过上限`() = runBlocking {
        val gate = HostConcurrencyGate(limit = 2)
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val jobs = (1..6).map {
            launch {
                gate.withSlot("a.com") {
                    val now = running.incrementAndGet()
                    peak.set(maxOf(peak.get(), now))
                    delay(40)
                    running.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.join() }
        assertEquals(2, peak.get())
    }

    @Test
    fun `不同 host 各算各的`() = runBlocking {
        val gate = HostConcurrencyGate(limit = 1)
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val jobs = listOf("a.com", "b.com", "c.com").map { host ->
            launch {
                gate.withSlot(host) {
                    val now = running.incrementAndGet()
                    peak.set(maxOf(peak.get(), now))
                    delay(40)
                    running.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.join() }
        assertEquals(3, peak.get())
    }

    @Test
    fun `关掉之后不限制并发`() = runBlocking {
        val gate = HostConcurrencyGate(limit = 0)
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val jobs = (1..5).map {
            launch {
                gate.withSlot("a.com") {
                    val now = running.incrementAndGet()
                    peak.set(maxOf(peak.get(), now))
                    delay(40)
                    running.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.join() }
        assertEquals(5, peak.get())
    }

    @Test
    fun `不知道归属的请求不参与限流`() = runBlocking {
        val gate = HostConcurrencyGate(limit = 1)
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val jobs = (1..4).map {
            launch {
                gate.withSlot("") {
                    val now = running.incrementAndGet()
                    peak.set(maxOf(peak.get(), now))
                    delay(40)
                    running.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.join() }
        assertEquals(4, peak.get())
    }

    @Test
    fun `等太久就直接放行`() = runBlocking {
        val gate = HostConcurrencyGate(limit = 1, waitLimitMs = 100)
        val holder = launch {
            gate.withSlot("a.com") { delay(600) }
        }
        delay(40) // 让 holder 先拿走唯一的名额
        val start = System.currentTimeMillis()
        gate.withSlot("a.com") { }
        val elapsed = System.currentTimeMillis() - start
        // 应该等满 100ms 左右就放行，而不是陪 holder 耗完 600ms
        assertTrue("等了 $elapsed ms", elapsed < 400)
        holder.join()
    }

    @Test
    fun `抛异常也会把名额还回去`() = runBlocking {
        val gate = HostConcurrencyGate(limit = 1)
        try {
            gate.withSlot("a.com") { throw IllegalStateException("boom") }
        } catch (_: IllegalStateException) {
        }
        val again = withTimeout(2_000) { gate.withSlot("a.com") { "ok" } }
        assertEquals("ok", again)
    }

    @Test
    fun `协程被取消也会把名额还回去`() = runBlocking {
        val gate = HostConcurrencyGate(limit = 1)
        val job = launch { gate.withSlot("a.com") { delay(10_000) } }
        delay(60)
        job.cancel()
        job.join()
        val again = withTimeout(2_000) { gate.withSlot("a.com") { "ok" } }
        assertEquals("ok", again)
    }
}
