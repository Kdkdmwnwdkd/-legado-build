package com.shimmer.reader.help.source

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 对比表右侧那一格的文案。
 *
 * 规则很简单，但「没测过」和「测了失败」必须能一眼分清：
 * 前者显示书源自带的响应时间或一条横线，后者显示叉，原因写在源名下面。
 */
class SourceProbeTextTest {

    private fun cap(respondTime: Long = SourceCapability.DEFAULT_RESPOND_TIME) =
        SourceCapability(url = "https://abc.com", name = "A", respondTime = respondTime)

    private fun passed(ms: Long) = ProbeResult(
        url = "https://abc.com",
        name = "A",
        domainOk = true,
        domainMs = 300,
        searchOk = true,
        searchMs = ms,
        bookCount = 3,
    )

    private fun failed(domainOk: Boolean) = ProbeResult(
        url = "https://abc.com",
        name = "A",
        domainOk = domainOk,
        domainMs = 300,
        searchOk = false,
        searchMs = 900,
        reason = if (domainOk) "搜索没有结果" else "域名不可访问",
    )

    @Test
    fun `没测过且书源没响应时间时显示横线`() {
        assertEquals("—", probeText(cap(), null))
    }

    @Test
    fun `没测过时显示书源自带的响应时间`() {
        assertEquals("12s", probeText(cap(respondTime = 12_000), null))
    }

    @Test
    fun `实测一秒钟以内显示毫秒`() {
        assertEquals("420ms", probeText(cap(), passed(420)))
    }

    @Test
    fun `实测十秒以内保留一位小数`() {
        assertEquals("3.2s", probeText(cap(), passed(3_200)))
    }

    @Test
    fun `实测超过十秒只显示整秒`() {
        assertEquals("12s", probeText(cap(), passed(12_000)))
    }

    @Test
    fun `实测通过但没有耗时只显示勾`() {
        assertEquals("✓", probeText(cap(), passed(0)))
    }

    @Test
    fun `域名不通和搜索无结果都只显示叉`() {
        assertEquals("✗", probeText(cap(), failed(domainOk = false)))
        assertEquals("✗", probeText(cap(), failed(domainOk = true)))
    }
}
