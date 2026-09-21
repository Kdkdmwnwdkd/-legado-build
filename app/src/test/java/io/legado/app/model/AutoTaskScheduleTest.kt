package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class AutoTaskScheduleTest {

    private val utc = ZoneOffset.UTC

    @Test
    fun firstRunCanUseTheExplicitGraceWindow() {
        val now = Instant.parse("2026-08-29T10:04:30Z").toEpochMilli()
        val rule = AutoTaskRule(id = "b", name = "B", cron = "0 * * * *", script = "1")
        val next = AutoTaskSchedule.nextFor(rule, now, utc, firstRunGraceMs = 5 * 60_000L)!!
        assertEquals("2026-08-29T10:00:00Z", Instant.ofEpochMilli(next).toString())
        assertTrue(next <= now)
    }

    @Test
    fun futureLastRunDoesNotBlockAfterClockRollback() {
        val now = Instant.parse("2026-08-29T10:04:30Z").toEpochMilli()
        val rule = AutoTaskRule(
            id = "future",
            name = "Future",
            cron = "*/5 * * * *",
            script = "1",
            lastRunAt = now + 24 * 60 * 60_000L
        )
        val next = AutoTaskSchedule.nextFor(rule, now, utc)!!
        assertTrue(next <= now)
    }

    @Test
    fun enabledSelectionIsStableForEqualTimes() {
        val now = Instant.parse("2026-08-29T10:04:30Z").toEpochMilli()
        val rules = listOf(
            AutoTaskRule(id = "z", name = "Z", cron = "*/5 * * * *", script = "1"),
            AutoTaskRule(id = "a", name = "A", cron = "*/5 * * * *", script = "1"),
            AutoTaskRule(id = "off", name = "Off", enable = false, cron = "* * * * *", script = "1")
        )
        assertEquals("a", AutoTaskSchedule.nextEnabled(rules, now, utc)?.ruleId)
    }

    @Test
    fun invalidSchedulesAreIgnored() {
        val rule = AutoTaskRule(id = "bad", name = "Bad", cron = "bad", script = "1")
        assertNull(AutoTaskSchedule.nextFor(rule, 0L, utc))
    }
}
