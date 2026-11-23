package com.shimmer.reader.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceMatchTest {

    @Test
    fun `搜索关键词会剥掉站点后缀`() {
        assertEquals("斗破苍穹", SourceMatch.searchKey("斗破苍穹(全本)"))
        assertEquals("斗破苍穹", SourceMatch.searchKey("斗破苍穹 最新章节"))
        assertEquals("斗破苍穹", SourceMatch.searchKey("斗破苍穹【精校版】"))
        assertEquals("斗破苍穹", SourceMatch.searchKey("斗破苍穹 TXT下载"))
    }

    @Test
    fun `书名整个都是装饰时退回原名`() {
        assertEquals("全本", SourceMatch.searchKey("全本"))
        assertEquals("", SourceMatch.searchKey(""))
        assertEquals("", SourceMatch.searchKey(null))
    }

    @Test
    fun `空格标点括号不影响书名比对`() {
        assertTrue(SourceMatch.nameMatches("斗破苍穹", "斗破 苍穹"))
        assertTrue(SourceMatch.nameMatches("斗破苍穹", "《斗破苍穹》"))
        assertTrue(SourceMatch.nameMatches("斗破苍穹", "斗破苍穹（精校版）"))
        assertTrue(SourceMatch.nameMatches("斗破苍穹(全本)", "斗破苍穹"))
    }

    @Test
    fun `分卷数字能对上`() {
        assertTrue(SourceMatch.nameMatches("斗破苍穹", "斗破苍穹2"))
        assertTrue(SourceMatch.nameMatches("斗破苍穹", "斗破苍穹 3"))
    }

    @Test
    fun `续作不算同一本书`() {
        assertFalse(SourceMatch.nameMatches("斗破苍穹", "斗破苍穹之药老传奇"))
        assertFalse(SourceMatch.nameMatches("斗破苍穹", "遮天"))
        assertFalse(SourceMatch.nameMatches("斗破苍穹", ""))
        assertFalse(SourceMatch.nameMatches("", "斗破苍穹"))
    }

    @Test
    fun `书源没写作者时放行`() {
        // 这是这次改动的重点：源站不填作者，不该因此把整条结果丢掉
        assertTrue(SourceMatch.authorMatches("天蚕土豆", ""))
        assertTrue(SourceMatch.authorMatches("", "天蚕土豆"))
        assertTrue(SourceMatch.authorMatches("", ""))
        assertTrue(SourceMatch.authorMatches("天蚕土豆", null))
    }

    @Test
    fun `作者写法差异能对上`() {
        assertTrue(SourceMatch.authorMatches("天蚕土豆", "天蚕土豆 著"))
        assertTrue(SourceMatch.authorMatches("作者：天蚕土豆", "天蚕土豆"))
        assertTrue(SourceMatch.authorMatches("天蚕土豆", "天蚕"))
    }

    @Test
    fun `不同作者仍然拦下`() {
        assertFalse(SourceMatch.authorMatches("天蚕土豆", "辰东"))
        assertFalse(SourceMatch.authorMatches("唐家三少", "天蚕土豆"))
    }

    @Test
    fun `书名里的搜索高亮标签会被忽略`() {
        assertEquals("斗破苍穹", SourceMatch.normalizeName("<em>斗破</em>苍穹"))
    }
}
