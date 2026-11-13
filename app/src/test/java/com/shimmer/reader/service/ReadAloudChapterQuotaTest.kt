package com.shimmer.reader.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudChapterQuotaTest {

    @Test
    fun offQuotaNeverStops() {
        val step = ReadAloudChapterQuota.step(ReadAloudChapterQuota.OFF)

        assertEquals(ReadAloudChapterQuota.OFF, step.remaining)
        assertFalse(step.stopNow)
    }

    @Test
    fun lastChapterStopsAndClearsQuota() {
        val step = ReadAloudChapterQuota.step(1)

        assertEquals(ReadAloudChapterQuota.OFF, step.remaining)
        assertTrue(step.stopNow)
    }

    @Test
    fun remainingChaptersCountDown() {
        assertEquals(2, ReadAloudChapterQuota.step(3).remaining)
        assertFalse(ReadAloudChapterQuota.step(3).stopNow)

        assertEquals(1, ReadAloudChapterQuota.step(2).remaining)
        assertFalse(ReadAloudChapterQuota.step(2).stopNow)
    }

    @Test
    fun quotaIsRestoredWhenChapterSwitchFails() {
        assertEquals(3, ReadAloudChapterQuota.rollback(3))
    }

    @Test
    fun disabledQuotaStaysOffOnRollback() {
        assertEquals(ReadAloudChapterQuota.OFF, ReadAloudChapterQuota.rollback(ReadAloudChapterQuota.OFF))
    }

    @Test
    fun requestIsClampedToSaneRange() {
        assertEquals(0, ReadAloudChapterQuota.clamp(-5))
        assertEquals(ReadAloudChapterQuota.MAX, ReadAloudChapterQuota.clamp(9_999))
        assertEquals(5, ReadAloudChapterQuota.clamp(5))
    }
}
