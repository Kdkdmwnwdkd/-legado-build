package com.shimmer.reader.service

import com.shimmer.reader.service.ReadAloudSilenceGuard.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAloudSilenceGuardTest {

    private fun guard() = ReadAloudSilenceGuard(
        notStartedTimeoutMillis = 12_000L,
        minTimeoutMillis = 30_000L,
        perCharMillis = 450L,
        maxTimeoutMillis = 600_000L,
        maxRetries = 2
    )

    @Test
    fun waitsLongerBeforeGivingUpOnStart() {
        val guard = guard()
        guard.onUtterance(cueIndex = 3, text = "短句", now = 0L)

        assertEquals(Decision.NONE, guard.check(now = 11_999L, playing = true, hasPendingUtterance = true))
        assertEquals(Decision.RETRY, guard.check(now = 12_000L, playing = true, hasPendingUtterance = true))
    }

    @Test
    fun startedCueGetsTimeToRead() {
        val guard = guard()
        guard.onUtterance(cueIndex = 3, text = "短句", now = 0L)
        guard.onStarted(now = 3_000L)

        assertEquals(Decision.NONE, guard.check(now = 32_999L, playing = true, hasPendingUtterance = true))
        assertEquals(Decision.RETRY, guard.check(now = 33_000L, playing = true, hasPendingUtterance = true))
    }

    @Test
    fun longTextGetsLongerTimeoutAndIsCapped() {
        val guard = guard()
        guard.onUtterance(cueIndex = 0, text = "字".repeat(100), now = 0L)
        guard.onStarted(now = 0L)
        assertEquals(45_000L, guard.timeoutMillis())
        assertEquals(Decision.NONE, guard.check(now = 44_000L, playing = true, hasPendingUtterance = true))
        assertEquals(Decision.RETRY, guard.check(now = 45_000L, playing = true, hasPendingUtterance = true))

        val longGuard = guard()
        longGuard.onUtterance(cueIndex = 0, text = "字".repeat(2_000), now = 0L)
        longGuard.onStarted(now = 0L)
        assertEquals(600_000L, longGuard.timeoutMillis())
    }

    @Test
    fun activityPostponesRetry() {
        val guard = guard()
        guard.onUtterance(cueIndex = 3, text = "短句", now = 0L)
        guard.onStarted(now = 0L)
        guard.onActivity(now = 25_000L)

        assertEquals(Decision.NONE, guard.check(now = 50_000L, playing = true, hasPendingUtterance = true))
        assertEquals(Decision.RETRY, guard.check(now = 55_000L, playing = true, hasPendingUtterance = true))
    }

    @Test
    fun givesUpAfterRetriesExhausted() {
        val guard = guard()
        guard.onUtterance(cueIndex = 3, text = "短句", now = 0L)

        assertEquals(Decision.RETRY, guard.check(now = 12_000L, playing = true, hasPendingUtterance = true))
        assertEquals(Decision.RETRY, guard.check(now = 24_000L, playing = true, hasPendingUtterance = true))
        assertEquals(Decision.GIVE_UP, guard.check(now = 36_000L, playing = true, hasPendingUtterance = true))
    }

    @Test
    fun pausedPlaybackNeverRetries() {
        val guard = guard()
        guard.onUtterance(cueIndex = 3, text = "短句", now = 0L)

        assertEquals(Decision.NONE, guard.check(now = 60_000L, playing = false, hasPendingUtterance = true))
        assertEquals(Decision.NONE, guard.check(now = 60_000L, playing = true, hasPendingUtterance = false))
    }

    @Test
    fun movingToAnotherCueResetsRetries() {
        val guard = guard()
        guard.onUtterance(cueIndex = 3, text = "短句", now = 0L)
        assertEquals(Decision.RETRY, guard.check(now = 12_000L, playing = true, hasPendingUtterance = true))
        assertEquals(Decision.RETRY, guard.check(now = 24_000L, playing = true, hasPendingUtterance = true))

        guard.onUtterance(cueIndex = 4, text = "下一段", now = 25_000L)

        assertEquals(0, guard.retriedCount)
        assertEquals(Decision.NONE, guard.check(now = 30_000L, playing = true, hasPendingUtterance = true))
        assertEquals(Decision.RETRY, guard.check(now = 37_000L, playing = true, hasPendingUtterance = true))
    }

    @Test
    fun completedCueResetsRetries() {
        val guard = guard()
        guard.onUtterance(cueIndex = 3, text = "短句", now = 0L)
        assertEquals(Decision.RETRY, guard.check(now = 12_000L, playing = true, hasPendingUtterance = true))

        guard.onCompleted(now = 13_000L)

        assertEquals(0, guard.retriedCount)
    }
}
