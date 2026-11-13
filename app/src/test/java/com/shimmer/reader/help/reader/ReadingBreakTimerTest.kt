package com.shimmer.reader.help.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingBreakTimerTest {

    private val minute = 60_000L

    @Test
    fun onlyCountsTimeSpentInForeground() {
        val timer = ReadingBreakTimer(75 * minute)
        timer.onResumed(0L)
        timer.onPaused(30 * minute)

        // 中间去后台那半小时不算
        timer.onResumed(60 * minute)
        assertEquals(60 * minute, timer.elapsed(90 * minute))
        assertEquals(15 * minute, timer.remaining(90 * minute))
    }

    @Test
    fun remainingStopsAtZero() {
        val timer = ReadingBreakTimer(30 * minute)
        timer.onResumed(0L)

        assertEquals(0L, timer.remaining(45 * minute))
    }

    @Test
    fun resetStartsCountingFromNow() {
        val timer = ReadingBreakTimer(30 * minute)
        timer.onResumed(0L)
        timer.reset(31 * minute)

        assertEquals(0L, timer.elapsed(31 * minute))
        assertEquals(30 * minute, timer.remaining(31 * minute))
        assertEquals(10 * minute, timer.elapsed(41 * minute))
    }

    @Test
    fun pausedTimerDoesNotGrow() {
        val timer = ReadingBreakTimer(30 * minute)
        timer.onResumed(0L)
        timer.onPaused(10 * minute)

        assertEquals(10 * minute, timer.elapsed(99 * minute))
    }
}
