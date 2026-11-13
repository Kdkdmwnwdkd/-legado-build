package com.shimmer.reader.help.book

import org.junit.Assert.assertEquals
import org.junit.Test

class BookRemarkStoreTest {

    @Test
    fun plainTextIsKeptAsIs() {
        assertEquals("等完结再看", BookRemarkStore.normalize("等完结再看"))
    }

    @Test
    fun surroundingWhitespaceAndBlankLinesAreTrimmed() {
        assertEquals(
            "第一行\n第二行",
            BookRemarkStore.normalize("\n\n  第一行  \n第二行\n\n\n")
        )
    }

    @Test
    fun repeatedBlankLinesCollapseToOne() {
        assertEquals(
            "上\n\n下",
            BookRemarkStore.normalize("上\n\n\n\n下")
        )
    }

    @Test
    fun crlfIsUnified() {
        assertEquals("甲\n乙", BookRemarkStore.normalize("甲\r\n乙"))
        assertEquals("甲\n乙", BookRemarkStore.normalize("甲\r乙"))
    }

    @Test
    fun tooLongTextIsTruncated() {
        val long = "字".repeat(BookRemarkStore.MAX_LENGTH + 100)
        assertEquals(BookRemarkStore.MAX_LENGTH, BookRemarkStore.normalize(long).length)
    }

    @Test
    fun tooManyLinesAreDropped() {
        val many = (1..40).joinToString("\n") { "行$it" }
        val normalized = BookRemarkStore.normalize(many)
        assertEquals(20, normalized.lines().size)
        assertEquals("行1", normalized.lines().first())
        assertEquals("行20", normalized.lines().last())
    }

    @Test
    fun blankInputBecomesEmpty() {
        assertEquals("", BookRemarkStore.normalize(null))
        assertEquals("", BookRemarkStore.normalize(""))
        assertEquals("", BookRemarkStore.normalize("   \n\n  "))
    }

    @Test
    fun mergeKeepsLocalOnlyEntriesAndIncomingWins() {
        val local = listOf(
            BookRemarkEntry("a", "本机的 A"),
            BookRemarkEntry("b", "本机的 B")
        )
        val incoming = listOf(
            BookRemarkEntry("b", "备份的 B"),
            BookRemarkEntry("c", "备份的 C")
        )
        val merged = BookRemarkStore.mergeEntries(local, incoming)
        assertEquals(listOf("a", "b", "c"), merged.map { it.bookUrl })
        assertEquals("本机的 A", merged[0].remark)
        assertEquals("备份的 B", merged[1].remark)
        assertEquals("备份的 C", merged[2].remark)
    }

    @Test
    fun mergeIgnoresBlankEntries() {
        val merged = BookRemarkStore.mergeEntries(
            local = listOf(BookRemarkEntry("a", "有备注")),
            incoming = listOf(BookRemarkEntry("a", "  "), BookRemarkEntry("", "没有书"))
        )
        assertEquals(1, merged.size)
        assertEquals("有备注", merged[0].remark)
    }
}
