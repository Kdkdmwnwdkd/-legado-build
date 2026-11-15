package com.shimmer.reader.help.book

import org.apache.commons.text.similarity.JaccardSimilarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterAlignTest {

    private val similarity = JaccardSimilarity()

    /** 去掉「第 N 章」和标点，只留标题主体——和 BookHelp.getPureChapterName 同一思路的简化版。 */
    private fun pureName(title: String?): String {
        return (title ?: "")
            .replace(Regex("""第[\d一二三四五六七八九十百]+[章节篇回集话]"""), "")
            .replace(Regex("""[\s\p{P}]"""), "")
    }

    /** 只认阿拉伯数字，够覆盖判定的分支。 */
    private fun chapterNum(title: String?): Int {
        val match = Regex("""第(\d+)[章节篇回集话]""").find(title ?: "") ?: return -1
        return match.groupValues[1].toIntOrNull() ?: -1
    }

    private fun align(
        oldIndex: Int,
        oldTitle: String?,
        oldTotal: Int,
        titles: List<String>
    ): ChapterAlign.Result {
        return ChapterAlign.align(
            oldIndex = oldIndex,
            oldTitle = oldTitle,
            oldTotal = oldTotal,
            newTitles = titles,
            pureName = ::pureName,
            chapterNum = ::chapterNum,
            similarity = { a, b -> similarity.apply(a, b) }
        )
    }

    private fun chapters(vararg titles: String): List<String> = titles.toList()

    @Test
    fun `停在开头时不需要对齐`() {
        val result = align(0, "第1章 初入江湖", 100, chapters("第1章 初入江湖", "第2章 风起"))
        assertEquals(ChapterAlign.Reason.SAME_INDEX, result.reason)
        assertEquals(0, result.index)
        assertFalse(result.matched)
    }

    @Test
    fun `新源在开头插了序章仍能按标题找回原章节`() {
        val toc = chapters(
            "序章 一场雨",
            "第1章 初入江湖",
            "第2章 风起",
            "第3章 山雨欲来"
        )
        val result = align(1, "第2章 风起", 100, toc)
        assertEquals(ChapterAlign.Reason.TITLE, result.reason)
        assertEquals(2, result.index)
        assertTrue(result.matched)
    }

    @Test
    fun `新源多出卷名导致整体后移，标题仍能对上`() {
        val toc = chapters(
            "第1卷 少年行",
            "第1章 初入江湖",
            "第2章 风起",
            "第2卷 江湖路",
            "第3章 山雨欲来",
            "第4章 夜行"
        )
        val result = align(2, "第3章 山雨欲来", 100, toc)
        assertEquals(ChapterAlign.Reason.TITLE, result.reason)
        assertEquals(4, result.index)
    }

    @Test
    fun `标题全改了但章节号还在，按序号对齐`() {
        val toc = chapters(
            "第1章 另一种写法",
            "第2章 又一个标题",
            "第3章 完全不同的标题",
            "第4章 另一个标题"
        )
        val result = align(2, "第3章 山雨欲来", 100, toc)
        assertEquals(ChapterAlign.Reason.NUMBER, result.reason)
        assertEquals(2, result.index)
        assertTrue(result.matched)
    }

    @Test
    fun `换了本书，标题和编号都对不上时按原序号落位`() {
        val toc = chapters("春", "夏", "秋", "冬")
        val result = align(2, "山雨欲来", 100, toc)
        assertEquals(ChapterAlign.Reason.FALLBACK, result.reason)
        assertEquals(2, result.index)
        assertFalse(result.matched)
    }

    @Test
    fun `新目录比原序号短时落位不会越界`() {
        val toc = chapters("第1章 初入江湖", "第2章 风起", "第3章 山雨欲来")
        val result = align(100, "第100章 归途", 200, toc)
        assertTrue(result.index in toc.indices)
        assertEquals(2, result.index)
    }

    @Test
    fun `新目录为空时原样返回`() {
        val result = align(30, "第30章 归途", 200, emptyList())
        assertEquals(30, result.index)
        assertEquals(ChapterAlign.Reason.FALLBACK, result.reason)
    }

    @Test
    fun `离得太远的同名章节不会被硬拽过去`() {
        val toc = (1..1000).map { "第${it}章 内容$it" }.toMutableList()
        toc[800] = "大结局"
        // 原书停在「大结局」，但它在旧书靠前的位置，标题没有编号可依
        val result = align(20, "大结局", 100, toc)
        assertEquals(ChapterAlign.Reason.FALLBACK, result.reason)
        assertEquals(20, result.index)
        assertFalse(result.matched)
    }

    @Test
    fun `旧书章数多、新源章数少时按比例换算不会跳过整段窗口`() {
        // 旧源 1000 章（中间夹着大量重复章和广告章），新源只有 500 章；
        // 用户停在旧书第 900 章的《归途》，在新源里它是第 450 章。
        // 换算方向写反的话，估算位置会跑到 1800，窗口变成 890..499 的空区间，
        // 标题比对整段跳过，最后按原序号落到 499（最后一章）——进度直接从 90% 跳到末尾。
        val toc = (1..500).map { "第${it}章 内容$it" }.toMutableList()
        toc[449] = "第450章 归途"

        val result = align(900, "第900章 归途", 1000, toc)

        assertEquals(ChapterAlign.Reason.TITLE, result.reason)
        assertEquals(449, result.index)
        assertTrue(result.matched)
    }

    @Test
    fun `换算位置溢出时窗口仍然有内容可找`() {
        // 旧书 2000 章停在 1900 章，新源只有 120 章，按比例该落在末尾附近
        val toc = (1..120).map { "第${it}章 内容$it" }
        val result = align(1900, "第120章 内容120", 2000, toc)

        assertEquals(ChapterAlign.Reason.TITLE, result.reason)
        assertEquals(119, result.index)
        assertTrue(result.index in toc.indices)
    }

    @Test
    fun `回退时也会带回窗口里最像的那一章`() {
        val toc = chapters("第1章 初入江湖", "第2章 风起云涌", "第3章 山雨欲来风满楼")
        // 标题只差几个字，够不上 0.96；编号对上走 NUMBER，但 bestIndex 依然要记录
        val result = align(2, "第3章 山雨欲来", 100, toc)
        assertEquals(2, result.bestIndex)
        assertTrue(result.bestSimilarity > 0.0)
    }
}
