package com.shimmer.reader.help.search

import com.shimmer.reader.data.entities.SearchBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPreviewTest {

    private fun book(
        url: String,
        origin: String,
        name: String = "诡秘之主",
        author: String = "爱潜水的乌贼",
        time: Long = 1L,
    ) = SearchBook(
        bookUrl = url,
        origin = origin,
        originName = origin,
        name = name,
        author = author,
        time = time,
    )

    @Test
    fun `同一本书在多个源只留一条`() {
        val merged = LocalPreview.merge(
            listOf(
                book("u1", "s1"),
                book("u2", "s2"),
                book("u3", "s3"),
            )
        )
        assertEquals(1, merged.size)
        assertEquals(3, merged.first().origins.size)
    }

    @Test
    fun `合并后保留时间最新的那条`() {
        val merged = LocalPreview.merge(
            listOf(
                book("u1", "s1", time = 100L).apply { latestChapterTitle = "旧章节" },
                book("u2", "s2", time = 300L).apply { latestChapterTitle = "新章节" },
                book("u3", "s3", time = 200L).apply { latestChapterTitle = "中间章节" },
            )
        )
        assertEquals(1, merged.size)
        assertEquals("u2", merged.first().bookUrl)
        assertEquals("新章节", merged.first().latestChapterTitle)
        assertEquals(3, merged.first().origins.size)
    }

    @Test
    fun `作者为空时不合并不同地址的记录`() {
        val merged = LocalPreview.merge(
            listOf(
                book("u1", "s1", author = ""),
                book("u2", "s2", author = ""),
            )
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `作者为空但地址相同的记录仍算同一本`() {
        val merged = LocalPreview.merge(
            listOf(
                book("u1", "s1", author = ""),
                book("u1", "s1", author = "", time = 9L),
            )
        )
        assertEquals(1, merged.size)
        assertEquals(9L, merged.first().time)
    }

    @Test
    fun `源多的排在前面`() {
        val merged = LocalPreview.merge(
            listOf(
                book("u1", "s1", name = "单源书", author = "甲", time = 900L),
                book("u2", "s2", name = "多源书", author = "乙", time = 100L),
                book("u3", "s3", name = "多源书", author = "乙", time = 100L),
            )
        )
        assertEquals("多源书", merged.first().name)
        assertEquals(2, merged.first().origins.size)
    }

    @Test
    fun `超过上限时截断`() {
        val books = (1..10).map { book("u$it", "s$it", name = "书$it", author = "作者$it") }
        assertEquals(3, LocalPreview.merge(books, limit = 3).size)
    }

    @Test
    fun `空列表与非法上限返回空`() {
        assertTrue(LocalPreview.merge(emptyList()).isEmpty())
        assertTrue(LocalPreview.merge(listOf(book("u1", "s1")), limit = 0).isEmpty())
    }
}
