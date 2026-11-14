package com.shimmer.reader.help.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索发放顺序。
 *
 * 这里只验两件事：同一个站点内部按快慢发、并且隔开一点时间；
 * 不同站点之间不受影响，不能让某个域名源多就把别的域名全排到后面。
 */
class SearchScheduleTest {

    private data class Src(val url: String, val name: String, val ms: Long = 180_000L)

    private fun order(
        vararg sources: Src,
        step: Long = SearchSchedule.STEP_MS,
        max: Long = SearchSchedule.MAX_DELAY_MS,
    ): List<ScheduledSource<Src>> = SearchSchedule.order(
        sources = sources.toList(),
        urlOf = { it.url },
        respondTimeOf = { it.ms },
        hostOf = { url ->
            url.substringAfter("://").substringBefore("/")
                .removePrefix("www.")
                .removePrefix("m.")
        },
        stepMs = step,
        maxDelayMs = max,
    )

    @Test
    fun `同域名三个源依次错开`() {
        val a = Src("https://www.abc.com/a", "A")
        val b = Src("https://m.abc.com/b", "B")
        val c = Src("https://abc.com/c", "C")
        val result = order(a, b, c)
        assertEquals(listOf("A", "B", "C"), result.map { it.source.name })
        assertEquals(listOf(0L, 600L, 1_200L), result.map { it.delayMs })
    }

    @Test
    fun `不同域名各一个源都不等待`() {
        val a = Src("https://a.com/x", "A")
        val b = Src("https://b.com/y", "B")
        val result = order(a, b)
        assertEquals(listOf(0L, 0L), result.map { it.delayMs })
    }

    @Test
    fun `同域名内响应快的先发`() {
        val slow = Src("https://abc.com/slow", "慢", ms = 9_000)
        val fast = Src("https://abc.com/fast", "快", ms = 400)
        val never = Src("https://abc.com/new", "没测过", ms = 180_000)
        val result = order(slow, fast, never)
        assertEquals(listOf("快", "慢", "没测过"), result.map { it.source.name })
    }

    @Test
    fun `不同域名之间交替发放`() {
        val a1 = Src("https://a.com/1", "A1", ms = 300)
        val a2 = Src("https://a.com/2", "A2", ms = 500)
        val b1 = Src("https://b.com/1", "B1", ms = 400)
        val b2 = Src("https://b.com/2", "B2", ms = 900)
        val result = order(a1, a2, b1, b2)
        assertEquals(listOf("A1", "B1", "A2", "B2"), result.map { it.source.name })
        assertEquals(listOf(0L, 0L, 600L, 600L), result.map { it.delayMs })
    }

    @Test
    fun `排队延迟有上限`() {
        val sources = (1..10).map { Src("https://abc.com/$it", "S$it", ms = it * 100L) }
        val result = SearchSchedule.order(
            sources = sources,
            urlOf = { it.url },
            respondTimeOf = { it.ms },
            hostOf = { "abc.com" },
        )
        assertEquals(10, result.size)
        assertEquals(0L, result.first().delayMs)
        assertEquals(SearchSchedule.MAX_DELAY_MS, result.last().delayMs)
        assertTrue(result.all { it.delayMs <= SearchSchedule.MAX_DELAY_MS })
    }

    @Test
    fun `解析不出的域名各自成组`() {
        val a = Src("magnet:?xt=1", "A")
        val b = Src("magnet:?xt=2", "B")
        val result = SearchSchedule.order(
            sources = listOf(a, b),
            urlOf = { it.url },
            respondTimeOf = { it.ms },
            hostOf = { null },
        )
        assertEquals(listOf(0L, 0L), result.map { it.delayMs })
        assertEquals(2, result.map { it.host }.distinct().size)
    }

    @Test
    fun `空列表返回空`() {
        val result = SearchSchedule.order(
            sources = emptyList<Src>(),
            urlOf = { it.url },
            respondTimeOf = { it.ms },
            hostOf = { "abc.com" },
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `单个源照常发不用等`() {
        val result = order(Src("https://abc.com/a", "A"))
        assertEquals(1, result.size)
        assertEquals(0L, result[0].delayMs)
    }
}
