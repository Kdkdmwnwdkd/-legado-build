package com.shimmer.reader.help.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceDomainIndexTest {

    private fun cap(
        url: String,
        name: String,
        search: Boolean = true,
        explore: Boolean = true,
        info: Boolean = true,
        toc: Boolean = true,
        content: Boolean = true,
        respondTime: Long = SourceCapability.DEFAULT_RESPOND_TIME,
    ) = SourceCapability(
        url = url,
        name = name,
        hasSearch = search,
        hasExplore = explore,
        hasInfo = info,
        hasToc = toc,
        hasContent = content,
        respondTime = respondTime,
    )

    /** www、m、裸域都归到同一个主域 */
    private val hostOf: (String) -> String? = { url ->
        url.substringAfter("://").substringBefore("/")
            .removePrefix("www.")
            .removePrefix("m.")
    }

    @Test
    fun `同一主域的多个子域并到一组`() {
        val groups = SourceDomainIndex.group(
            listOf(
                cap("https://www.abc.com", "A"),
                cap("https://m.abc.com", "B"),
                cap("https://abc.com", "C"),
            ),
            hostOf
        )
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].size)
        assertEquals("abc.com", groups[0].host)
    }

    @Test
    fun `单源域名默认不单独成组`() {
        val sources = listOf(cap("https://abc.com", "A"), cap("https://xyz.com", "B"))
        assertTrue(SourceDomainIndex.group(sources, hostOf).isEmpty())
        assertEquals(2, SourceDomainIndex.group(sources, hostOf, onlyDuplicated = false).size)
    }

    @Test
    fun `组内能力多的排前面`() {
        val groups = SourceDomainIndex.group(
            listOf(
                cap("https://abc.com/1", "只有发现", search = false, info = false, toc = false, content = false),
                cap("https://abc.com/2", "全能力"),
            ),
            hostOf
        )
        assertEquals("全能力", groups[0].sources.first().name)
        assertEquals(5, groups[0].best.first().capabilityCount)
        assertEquals(1, groups[0].best.size)
    }

    @Test
    fun `能力被完全覆盖的源标为重叠`() {
        val groups = SourceDomainIndex.group(
            listOf(
                cap("https://abc.com/1", "全能力"),
                cap("https://abc.com/2", "只有发现", search = false, info = false, toc = false, content = false),
            ),
            hostOf
        )
        val overlapped = groups[0].overlapped
        assertEquals(1, overlapped.size)
        assertEquals("只有发现", overlapped[0].name)
    }

    @Test
    fun `能力相同时只保留最快的那个`() {
        val groups = SourceDomainIndex.group(
            listOf(
                cap("https://abc.com/1", "慢源", respondTime = 8000),
                cap("https://abc.com/2", "快源", respondTime = 1200),
            ),
            hostOf
        )
        val group = groups[0]
        assertEquals("快源", group.sources.first().name)
        assertEquals(1, group.overlapped.size)
        assertEquals("慢源", group.overlapped[0].name)
    }

    @Test
    fun `都没测过响应时间时按名字排序且只标一个重叠`() {
        val groups = SourceDomainIndex.group(
            listOf(
                cap("https://abc.com/2", "B源"),
                cap("https://abc.com/1", "A源"),
            ),
            hostOf
        )
        val group = groups[0]
        assertEquals(listOf("A源", "B源"), group.sources.map { it.name })
        assertEquals(1, group.overlapped.size)
        assertEquals("B源", group.overlapped[0].name)
    }

    @Test
    fun `互不覆盖的两个源都不标重叠`() {
        val groups = SourceDomainIndex.group(
            listOf(
                cap("https://abc.com/1", "只有搜索", explore = false, info = false, toc = false, content = false),
                cap("https://abc.com/2", "只有发现", search = false, info = false, toc = false, content = false),
            ),
            hostOf
        )
        assertTrue(groups[0].overlapped.isEmpty())
        assertEquals("2 个源 · 搜索 1 · 发现 1", groups[0].summary())
    }

    @Test
    fun `摘要包含停用与重叠数量`() {
        val group = SourceDomainGroup(
            host = "abc.com",
            sources = listOf(
                cap("https://abc.com/1", "A").copy(enabled = false),
                cap("https://abc.com/2", "B", search = false),
            )
        )
        assertEquals("2 个源 · 搜索 1 · 发现 2 · 停用 1 · 重叠 1", group.summary())
    }

    @Test
    fun `覆盖关系是严格超集`() {
        val full = cap("https://abc.com/1", "全")
        val half = cap("https://abc.com/2", "半", content = false)
        assertTrue(full.covers(half))
        assertFalse(half.covers(full))
        assertFalse(full.covers(full))
    }

    @Test
    fun `域名无法解析的源被跳过`() {
        val groups = SourceDomainIndex.group(
            listOf(cap("https://abc.com/1", "A"), cap("https://abc.com/2", "B")),
            { null },
            onlyDuplicated = false
        )
        assertTrue(groups.isEmpty())
    }
}
