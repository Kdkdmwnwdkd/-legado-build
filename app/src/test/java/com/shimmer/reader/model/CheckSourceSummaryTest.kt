package com.shimmer.reader.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckSourceSummaryTest {

    private fun summary(
        vararg results: Triple<List<String>, Boolean, Boolean>
    ): CheckSourceSummary {
        return CheckSourceSummary().apply {
            results.forEach { (groups, wasEnabled, enabledNow) ->
                record(groups, wasEnabled, enabledNow)
            }
        }
    }

    @Test
    fun cleanSourcesCountAsUsable() {
        val s = summary(
            Triple(emptyList(), true, true),
            Triple(emptyList(), true, true),
            Triple(emptyList(), false, false)
        )

        assertEquals(3, s.checked)
        assertEquals(0, s.failedSources)
        assertEquals(3, s.usable)
        // 本来就是关着的源，不算本轮被停用
        assertEquals(0, s.disabledSources)
    }

    @Test
    fun onlyEnabledToDisabledCountsAsDisabled() {
        val s = summary(
            Triple(listOf("搜索失效"), true, false),
            Triple(listOf("搜索失效"), false, false)
        )

        assertEquals(2, s.checked)
        assertEquals(2, s.failedSources)
        assertEquals(1, s.disabledSources)
    }

    @Test
    fun sameTagOnManySourcesAccumulates() {
        val s = summary(
            Triple(listOf("搜索失效"), true, false),
            Triple(listOf("搜索失效"), true, false),
            Triple(listOf("搜索失效", "网站失效"), true, false)
        )

        assertEquals(3, s.checked)
        assertEquals(3, s.failedSources)
        assertEquals(0, s.usable)
        assertEquals(3, s.countOf("搜索失效"))
        assertEquals(1, s.countOf("网站失效"))
    }

    @Test
    fun repeatedTagOnOneSourceCountsOnce() {
        val s = summary(Triple(listOf("搜索失效", "搜索失效"), true, false))

        assertEquals(1, s.failedSources)
        assertEquals(1, s.countOf("搜索失效"))
    }

    @Test
    fun topFailuresOrdersByCountThenName() {
        val s = CheckSourceSummary()
        s.record(listOf("b失效"), true, false)
        s.record(listOf("b失效"), true, false)
        s.record(listOf("a失效"), true, false)
        s.record(listOf("a失效"), true, false)
        s.record(listOf("c失效"), true, false)

        assertEquals(
            listOf("a失效" to 2, "b失效" to 2, "c失效" to 1),
            s.topFailures().map { it.tag to it.count }
        )
    }

    @Test
    fun overflowIsMergedIntoRest() {
        val s = CheckSourceSummary()
        s.record(listOf("甲失效"), true, false)
        s.record(listOf("甲失效"), true, false)
        s.record(listOf("甲失效"), true, false)
        s.record(listOf("乙失效"), true, false)
        s.record(listOf("丙失效"), true, false)
        s.record(listOf("丁失效", "戊失效"), true, false)

        val top = s.topFailures(limit = 2)
        assertEquals(3, top.size)
        assertEquals("甲失效" to 3, top[0].tag to top[0].count)
        // 其余同数量，按码位排序取「丁」
        assertEquals("丁失效" to 1, top[1].tag to top[1].count)
        // 丙 1、乙 1、戊 1 合并为 3
        assertEquals(null, top[2].tag)
        assertEquals(3, top[2].count)
    }

    @Test
    fun failureTextUsesCallerFormats() {
        val s = summary(
            Triple(listOf("搜索失效"), true, false),
            Triple(listOf("网站失效"), true, false)
        )

        val text = s.failureText(
            itemFormat = { tag, count -> "$tag×$count" },
            separator = " | ",
            restFormat = { "其他$it" }
        )

        assertEquals("搜索失效×1 | 网站失效×1", text)
    }

    @Test
    fun failureTextIsEmptyWhenNothingFailed() {
        val s = summary(Triple(emptyList(), true, true))

        assertTrue(
            s.failureText(
                itemFormat = { tag, count -> "$tag×$count" },
                separator = "、",
                restFormat = { "其他$it" }
            ).isEmpty()
        )
    }

    @Test
    fun resetClearsPreviousRun() {
        val s = summary(Triple(listOf("搜索失效"), true, false))
        s.reset()

        assertEquals(0, s.checked)
        assertEquals(0, s.failedSources)
        assertEquals(0, s.disabledSources)
        assertEquals(0, s.usable)
        assertTrue(s.topFailures().isEmpty())
    }

    @Test
    fun disabledOriginsKeepOnlySourcesTurnedOffThisRound() {
        val s = CheckSourceSummary().apply {
            record(listOf("搜索失效"), true, false, origin = "https://a.com")
            // 本来就是关着的，不该算进来
            record(listOf("搜索失效"), false, false, origin = "https://b.com")
            record(emptyList(), true, true, origin = "https://c.com")
        }

        assertEquals(1, s.disabledSources)
        assertEquals(listOf("https://a.com"), s.disabledOrigins)
    }

    @Test
    fun resetClearsDisabledOrigins() {
        val s = CheckSourceSummary().apply {
            record(listOf("目录失效"), true, false, origin = "https://a.com")
            reset()
        }

        assertEquals(0, s.disabledSources)
        assertTrue(s.disabledOrigins.isEmpty())
    }

    @Test
    fun recordWithoutOriginStillCounts() {
        val s = CheckSourceSummary().apply {
            record(listOf("搜索失效"), true, false)
        }

        assertEquals(1, s.disabledSources)
        assertTrue(s.disabledOrigins.isEmpty())
    }
}
