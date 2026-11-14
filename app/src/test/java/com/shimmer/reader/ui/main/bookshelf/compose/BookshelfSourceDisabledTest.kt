package com.shimmer.reader.ui.main.bookshelf.compose

import com.shimmer.reader.constant.BookType
import com.shimmer.reader.data.dao.BookShelfDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BookshelfSourceDisabledTest {

    private fun display(
        bookUrl: String = "https://a.com/book/1",
        origin: String = "https://a.com"
    ) = BookShelfDisplay(
        bookUrl = bookUrl,
        origin = origin,
        originName = "示例源",
        name = "书名",
        author = "作者",
        intro = null,
        customIntro = null,
        customTag = null,
        coverUrl = null,
        customCoverUrl = null,
        type = BookType.text,
        group = 0L,
        latestChapterTitle = null,
        latestChapterTime = 0L,
        lastCheckCount = 0,
        totalChapterNum = 0,
        durChapterTitle = null,
        durChapterIndex = 0,
        durChapterTime = 0L,
        canUpdate = false,
        order = 0,
        readConfig = null
    )

    private fun item(
        bookUrl: String = "https://a.com/book/1",
        origin: String = "https://a.com",
        sourceDisabled: Boolean = false
    ) = BookshelfBookItemUi(
        display = display(bookUrl, origin),
        isUpdating = false,
        unreadCount = 0,
        hasNewChapter = false,
        tags = emptyList(),
        lastUpdateText = null,
        sourceDisabled = sourceDisabled
    )

    @Test
    fun `源在停用集合里就打标`() {
        val items = listOf(item(origin = "https://a.com"))
        val marked = markSourceDisabled(items, setOf("https://a.com"))
        assertTrue((marked.single() as BookshelfBookItemUi).sourceDisabled)
    }

    @Test
    fun `源不在集合里保持原样`() {
        val items = listOf(item(origin = "https://a.com"))
        val marked = markSourceDisabled(items, setOf("https://b.com"))
        assertFalse((marked.single() as BookshelfBookItemUi).sourceDisabled)
    }

    @Test
    fun `集合为空时原样返回同一个列表`() {
        val items = listOf(item())
        assertSame(items, markSourceDisabled(items, emptySet()))
    }

    @Test
    fun `重复标记不会反复复制条目`() {
        val items = listOf(item(sourceDisabled = true))
        val marked = markSourceDisabled(items, setOf("https://a.com"))
        assertSame(items, marked)
    }

    @Test
    fun `只影响命中的那一本`() {
        val items = listOf(
            item(bookUrl = "https://a.com/book/1", origin = "https://a.com"),
            item(bookUrl = "https://b.com/book/2", origin = "https://b.com")
        )
        val marked = markSourceDisabled(items, setOf("https://b.com"))
            .map { it as BookshelfBookItemUi }
        assertEquals(listOf(false, true), marked.map { it.sourceDisabled })
    }
}
