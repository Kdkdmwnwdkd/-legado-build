package com.shimmer.reader.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 限速记录的 key。
 *
 * 开关关闭时必须和原来完全一致（一个书源一份记录），
 * 这样默认行为不会有任何变化；打开后才按主域 + 速率合并。
 */
class ConcurrentRateLimiterKeyTest {

    private val hostOf: (String) -> String? = { url ->
        url.substringAfter("://").substringBefore("/")
            .removePrefix("www.")
            .removePrefix("m.")
            .takeIf { it.isNotBlank() && it.contains(".") }
    }

    private fun key(url: String, rate: String?, share: Boolean) =
        ConcurrentRateLimiter.recordKey(url, rate, share, hostOf)

    @Test
    fun `关闭时一个书源一份`() {
        val a = key("https://www.abc.com/a", "1/3000", false)
        val b = key("https://m.abc.com/b", "1/3000", false)
        assertEquals("https://www.abc.com/a", a)
        assertEquals("https://m.abc.com/b", b)
        assertNotEquals(a, b)
    }

    @Test
    fun `打开后同域名同速率共用一份`() {
        assertEquals(
            key("https://www.abc.com/a", "1/3000", true),
            key("https://m.abc.com/b", "1/3000", true)
        )
    }

    @Test
    fun `打开后同域名不同速率仍然分开`() {
        assertNotEquals(
            key("https://abc.com/a", "1/3000", true),
            key("https://abc.com/b", "2/1000", true)
        )
    }

    @Test
    fun `打开后不同域名分开`() {
        assertNotEquals(
            key("https://abc.com/a", "1/3000", true),
            key("https://xyz.com/b", "1/3000", true)
        )
    }

    @Test
    fun `没有书源地址时不出 key`() {
        assertNull(ConcurrentRateLimiter.recordKey(null, "1/3000", true, hostOf))
        assertNull(ConcurrentRateLimiter.recordKey("  ", "1/3000", true, hostOf))
    }

    @Test
    fun `域名解析不出时回落到书源地址`() {
        assertEquals(
            "https://abc.com/a",
            ConcurrentRateLimiter.recordKey("https://abc.com/a", "1/3000", true) { null }
        )
    }

    @Test
    fun `速率为空时按空串算，不影响分组`() {
        assertEquals(
            key("https://abc.com/a", null, true),
            key("https://abc.com/b", null, true)
        )
    }
}
