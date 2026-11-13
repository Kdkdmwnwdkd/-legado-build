package com.shimmer.reader.help.storage

import com.shimmer.reader.data.entities.ReadRecentBook
import com.shimmer.reader.data.entities.ReadRecordDaily
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreMergePolicyTest {

    @Test
    fun missingLocalAlwaysAcceptsIncoming() {
        assertTrue(
            RestoreMergePolicy.dailyReadTimeShouldReplace(
                null,
                ReadRecordDaily(date = "2026-11-13", readTime = 1L)
            )
        )
        assertTrue(
            RestoreMergePolicy.recentBookShouldReplace(
                null,
                ReadRecentBook(bookUrl = "u", lastRead = 1L)
            )
        )
    }

    @Test
    fun dailyReadTimeKeepsLargerValue() {
        val local = ReadRecordDaily(date = "2026-11-13", readTime = 600_000L)

        assertTrue(
            RestoreMergePolicy.dailyReadTimeShouldReplace(local, local.copy(readTime = 900_000L))
        )
        assertFalse(
            RestoreMergePolicy.dailyReadTimeShouldReplace(local, local.copy(readTime = 300_000L))
        )
        assertFalse(
            RestoreMergePolicy.dailyReadTimeShouldReplace(local, local.copy(readTime = 600_000L))
        )
    }

    @Test
    fun recentBookKeepsLaterRead() {
        val local = ReadRecentBook(bookUrl = "u", lastRead = 2_000L)

        assertTrue(
            RestoreMergePolicy.recentBookShouldReplace(local, ReadRecentBook("u", 3_000L))
        )
        assertFalse(
            RestoreMergePolicy.recentBookShouldReplace(local, ReadRecentBook("u", 1_000L))
        )
        assertFalse(
            RestoreMergePolicy.recentBookShouldReplace(local, ReadRecentBook("u", 2_000L))
        )
    }

    @Test
    fun differentDateOrBookIsIndependent() {
        val localDaily = ReadRecordDaily(date = "2026-11-12", readTime = 900_000L)
        assertTrue(
            RestoreMergePolicy.dailyReadTimeShouldReplace(
                localDaily,
                ReadRecordDaily(date = "2026-11-13", readTime = 1L)
            )
        )

        val localRecent = ReadRecentBook(bookUrl = "a", lastRead = 9_000L)
        assertTrue(
            RestoreMergePolicy.recentBookShouldReplace(localRecent, ReadRecentBook("b", 1L))
        )
    }
}
