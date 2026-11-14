package com.shimmer.reader.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookNoteExportTest {

    private fun note(
        book: String = "诡秘之主",
        author: String = "爱潜水的乌贼",
        chapterIndex: Int = 1,
        chapter: String = "第一章 绯红",
        text: String = "周明瑞睁开眼睛。",
        content: String = "",
        time: Long = 1_700_000_000_000L,
    ) = NoteEntry(
        bookName = book,
        bookAuthor = author,
        time = time,
        chapterIndex = chapterIndex,
        chapterName = chapter,
        text = text,
        content = content,
    )

    private val fixedDate: (Long) -> String = { "2026-11-14" }
    private val fixedDateTime: (Long) -> String = { "2026-11-14 18:35" }

    @Test
    fun `按书分组保持原有顺序`() {
        val books = BookNoteExport.group(
            listOf(
                note(book = "A", text = "a1"),
                note(book = "A", text = "a2"),
                note(book = "B", text = "b1"),
            )
        )
        assertEquals(listOf("A", "B"), books.map { it.name })
        assertEquals(2, books[0].count)
        assertEquals(1, books[1].count)
    }

    @Test
    fun `同名不同作者算两本书`() {
        val books = BookNoteExport.group(
            listOf(note(author = "甲"), note(author = "乙"))
        )
        assertEquals(2, books.size)
    }

    @Test
    fun `同一章的多条摘录合成一节`() {
        val books = BookNoteExport.group(
            listOf(
                note(text = "第一条"),
                note(text = "第二条"),
                note(chapterIndex = 2, chapter = "第二章", text = "第三条"),
            )
        )
        val chapters = books.single().chapters
        assertEquals(2, chapters.size)
        assertEquals(2, chapters[0].notes.size)
        assertEquals(1, chapters[1].notes.size)
    }

    @Test
    fun `没有章节名时归到未分章`() {
        val books = BookNoteExport.group(listOf(note(chapter = "   ")))
        assertEquals(BookNoteExport.FALLBACK_CHAPTER, books.single().chapters.single().name)
    }

    @Test
    fun `非法字符替换成下划线`() {
        assertEquals("a_b_c_d_e_f_g_h_i_j", BookNoteExport.sanitizeFileName("a/b:c*d?e\"f<g>h|i#j"))
        assertEquals("书名_下", BookNoteExport.sanitizeFileName("书名^下"))
    }

    @Test
    fun `文件名全被清掉时用未命名`() {
        assertEquals(BookNoteExport.FALLBACK_NAME, BookNoteExport.sanitizeFileName("   "))
        assertEquals(BookNoteExport.FALLBACK_NAME, BookNoteExport.sanitizeFileName("///"))
        assertEquals(BookNoteExport.FALLBACK_NAME, BookNoteExport.sanitizeFileName("..."))
    }

    @Test
    fun `超长书名截断`() {
        val long = "长".repeat(200)
        assertTrue(BookNoteExport.sanitizeFileName(long).length <= 80)
    }

    @Test
    fun `文件名带作者`() {
        val book = BookNoteExport.group(listOf(note())).single()
        assertEquals("诡秘之主（爱潜水的乌贼）.md", BookNoteExport.fileNameOf(book))
    }

    @Test
    fun `作者为空时文件名只有书名`() {
        val book = BookNoteExport.group(listOf(note(author = ""))).single()
        assertEquals("诡秘之主.md", BookNoteExport.fileNameOf(book))
    }

    @Test
    fun `单本书带 frontmatter 与章节`() {
        val book = BookNoteExport.group(
            listOf(note(text = "第一行\n第二行", content = "这里写得真好"))
        ).single()
        val md = BookNoteExport.renderBook(book, 0L, fixedDate, fixedDateTime)

        assertTrue(md.startsWith("---\n"))
        assertTrue(md.contains("title: \"诡秘之主\""))
        assertTrue(md.contains("author: \"爱潜水的乌贼\""))
        assertTrue(md.contains("  - 书摘"))
        assertTrue(md.contains("notes: 1"))
        assertTrue(md.contains("# 诡秘之主"))
        assertTrue(md.contains("## 第一章 绯红"))
        // 摘录按行加引用前缀，换行不会把引用块截断
        assertTrue(md.contains("> 第一行\n> 第二行"))
        assertTrue(md.contains("**想法**：这里写得真好"))
        assertTrue(md.contains("*2026-11-14 18:35*"))
    }

    @Test
    fun `作者为空时不写 author 字段`() {
        val book = BookNoteExport.group(listOf(note(author = ""))).single()
        val md = BookNoteExport.renderBook(book, 0L, fixedDate, fixedDateTime)
        assertFalse(md.contains("author:"))
        assertFalse(md.contains("作者："))
    }

    @Test
    fun `书名里的引号在 frontmatter 中转义`() {
        val book = BookNoteExport.group(listOf(note(book = "他说\"你好\""))).single()
        val md = BookNoteExport.renderBook(book, 0L, fixedDate, fixedDateTime)
        assertTrue(md.contains("title: \"他说\\\"你好\\\"\""))
    }

    @Test
    fun `合并导出同一章只写一次标题`() {
        val md = BookNoteExport.renderMerged(
            listOf(note(text = "第一条"), note(text = "第二条")),
            0L, fixedDate, fixedDateTime
        )
        assertEquals(1, Regex("### 第一章 绯红").findAll(md).count())
        assertEquals(1, Regex("## 诡秘之主（爱潜水的乌贼）").findAll(md).count())
    }

    @Test
    fun `没有书签时不产出内容`() {
        assertEquals("", BookNoteExport.renderMerged(emptyList(), 0L, fixedDate, fixedDateTime))
    }

    @Test
    fun `空摘录与空想法都不留空行`() {
        val book = BookNoteExport.group(listOf(note(text = "", content = ""))).single()
        val md = BookNoteExport.renderBook(book, 0L, fixedDate, fixedDateTime)
        assertFalse(md.contains("> \n"))
        assertFalse(md.contains("**想法**"))
    }
}
