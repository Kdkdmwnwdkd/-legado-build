package com.shimmer.reader.help.book

import com.shimmer.reader.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrphanSourceSummaryTest {

    private fun book(name: String, origin: String) = Book().apply {
        this.name = name
        this.author = "作者"
        this.origin = origin
        this.originName = "示例源"
    }

    @Test
    fun `空列表算零本零源`() {
        val stats = orphanSourceStats(emptyList())
        assertEquals(0, stats.bookCount)
        assertEquals(0, stats.sourceCount)
        assertTrue(stats.isEmpty)
    }

    @Test
    fun `同一个源上多本书只算一个源`() {
        val stats = orphanSourceStats(
            listOf(
                book("甲", "https://a.com"),
                book("乙", "https://a.com"),
                book("丙", "https://a.com")
            )
        )
        assertEquals(3, stats.bookCount)
        assertEquals(1, stats.sourceCount)
    }

    @Test
    fun `每本书挂不同源时源数量等于书数量`() {
        val stats = orphanSourceStats(
            listOf(
                book("甲", "https://a.com"),
                book("乙", "https://b.com")
            )
        )
        assertEquals(2, stats.bookCount)
        assertEquals(2, stats.sourceCount)
    }

    @Test
    fun `origin 为空的书不参与源计数`() {
        val stats = orphanSourceStats(
            listOf(
                book("甲", ""),
                book("乙", "https://b.com")
            )
        )
        assertEquals(2, stats.bookCount)
        assertEquals(1, stats.sourceCount)
    }
}
