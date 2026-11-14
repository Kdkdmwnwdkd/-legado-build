package com.shimmer.reader.help.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组内择优的判定。
 *
 * 这里每个用例都在验同一句话：只有拿得出「能力不弱、并且确实更靠谱」的替代源时才停用，
 * 其余情况一律保留。停错了用户会直接读不到书，比多留一个源的代价大得多。
 */
class SourceDomainPickTest {

    private fun cap(
        url: String,
        name: String,
        search: Boolean = true,
        explore: Boolean = true,
        info: Boolean = true,
        toc: Boolean = true,
        content: Boolean = true,
        enabled: Boolean = true,
        respondTime: Long = SourceCapability.DEFAULT_RESPOND_TIME,
    ) = SourceCapability(
        url = url,
        name = name,
        enabled = enabled,
        hasSearch = search,
        hasExplore = explore,
        hasInfo = info,
        hasToc = toc,
        hasContent = content,
        respondTime = respondTime,
    )

    private fun passed(url: String, ms: Long) = ProbeResult(
        url = url,
        domainOk = true,
        domainMs = 200,
        searchOk = true,
        searchMs = ms,
        bookCount = 3,
    )

    private fun failed(url: String) = ProbeResult(
        url = url,
        domainOk = true,
        domainMs = 300,
        searchOk = false,
        searchMs = 0,
        reason = "搜索没有结果",
    )

    private fun group(vararg sources: SourceCapability) =
        SourceDomainGroup(host = "abc.com", sources = sources.toList())

    @Test
    fun `能力被严格覆盖的源会被停用`() {
        val strong = cap("https://abc.com", "全能", search = true, explore = true)
        val weak = cap("https://abc.com/2", "只有正文", search = false, explore = false)
        val plan = SourceDomainPick.plan(group(weak, strong))
        assertEquals(listOf(weak.url), plan.disable.map { it.url })
        assertEquals("全能", plan.replacedBy[weak.url])
        assertTrue(plan.reasons[weak.url].orEmpty().contains("能力更全"))
    }

    @Test
    fun `同能力实测明显更快的留下，慢的停用`() {
        val fast = cap("https://abc.com/1", "快")
        val slow = cap("https://abc.com/2", "慢")
        val plan = SourceDomainPick.plan(
            group(slow, fast),
            mapOf(
                fast.url to passed(fast.url, 300),
                slow.url to passed(slow.url, 4_000),
            )
        )
        assertEquals(listOf(slow.url), plan.disable.map { it.url })
        assertTrue(plan.reasons[slow.url].orEmpty().contains("实测更快"))
    }

    @Test
    fun `同能力实测差不多时两个都留着`() {
        val a = cap("https://abc.com/1", "A")
        val b = cap("https://abc.com/2", "B")
        val plan = SourceDomainPick.plan(
            group(a, b),
            mapOf(
                a.url to passed(a.url, 800),
                b.url to passed(b.url, 900),
            )
        )
        assertFalse(plan.hasAction)
        assertEquals(2, plan.keep.size)
    }

    @Test
    fun `实测失败的源被通过的那个顶掉`() {
        val broken = cap("https://abc.com/1", "失效")
        val alive = cap("https://abc.com/2", "可用")
        val plan = SourceDomainPick.plan(
            group(broken, alive),
            mapOf(
                broken.url to failed(broken.url),
                alive.url to passed(alive.url, 600),
            )
        )
        assertEquals(listOf(broken.url), plan.disable.map { it.url })
        assertTrue(plan.reasons[broken.url].orEmpty().contains("没通过"))
    }

    @Test
    fun `唯一能搜索的源不会被停用`() {
        val onlySearch = cap("https://abc.com/1", "唯一能搜", search = true, explore = false)
        val others = cap("https://abc.com/2", "只有发现", search = false, explore = true)
        val plan = SourceDomainPick.plan(group(onlySearch, others))
        // 两边各有独有能力，谁也不覆盖谁
        assertFalse(plan.hasAction)
    }

    @Test
    fun `已经停用的源不参与计划`() {
        val enabled = cap("https://abc.com/1", "在用")
        val disabled = cap("https://abc.com/2", "已停", enabled = false)
        val plan = SourceDomainPick.plan(group(enabled, disabled))
        assertEquals(listOf(enabled.url), plan.keep.map { it.url })
        assertFalse(plan.hasAction)
    }

    @Test
    fun `都没测过时同能力的源不动`() {
        val a = cap("https://abc.com/1", "A", respondTime = 2_000)
        val b = cap("https://abc.com/2", "B", respondTime = 2_100)
        val plan = SourceDomainPick.plan(group(a, b))
        assertFalse(plan.hasAction)
    }

    @Test
    fun `都没测过但响应时间差很多时停用慢的`() {
        val quick = cap("https://abc.com/1", "快", respondTime = 800)
        val sluggish = cap("https://abc.com/2", "慢", respondTime = 9_000)
        val plan = SourceDomainPick.plan(group(quick, sluggish))
        assertEquals(listOf(sluggish.url), plan.disable.map { it.url })
        assertTrue(plan.reasons[sluggish.url].orEmpty().contains("响应更快"))
    }

    @Test
    fun `单个源没有可停用的对象`() {
        val plan = SourceDomainPick.plan(group(cap("https://abc.com", "只有一个")))
        assertFalse(plan.hasAction)
        assertEquals(1, plan.keep.size)
    }

    @Test
    fun `能力链逐级顶替，停完之后能力一项不少`() {
        val full = cap("https://abc.com/1", "全")
        val noContent = cap("https://abc.com/2", "缺正文", content = false)
        val onlySearch = cap("https://abc.com/3", "只搜索", explore = false, info = false, toc = false, content = false)
        val plan = SourceDomainPick.plan(group(onlySearch, noContent, full))
        assertEquals(2, plan.disable.size)
        assertEquals(listOf(full.url), plan.keep.map { it.url })
        assertEquals("全", plan.replacedBy[noContent.url])
        assertEquals("全", plan.replacedBy[onlySearch.url])
    }

    @Test
    fun `目标快三倍以上时即使能力更全也不停`() {
        val wide = cap("https://abc.com/1", "能力全")
        val narrowFast = cap("https://abc.com/2", "少一项但快", content = false)
        val plan = SourceDomainPick.plan(
            group(wide, narrowFast),
            mapOf(
                wide.url to passed(wide.url, 9_000),
                narrowFast.url to passed(narrowFast.url, 400),
            )
        )
        assertFalse(plan.hasAction)
    }
}
