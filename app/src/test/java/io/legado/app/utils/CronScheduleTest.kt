package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class CronScheduleTest {

    private val utc = ZoneOffset.UTC

    @Test
    fun computesStrictlyAfterTheInputMinute() {
        val schedule = CronSchedule.parse("*/15 * * * *")!!
        val from = Instant.parse("2026-08-29T10:15:00Z").toEpochMilli()
        val next = schedule.nextTimeAfter(from, utc)!!
        assertEquals("2026-08-29T10:30:00Z", Instant.ofEpochMilli(next).toString())
    }

    @Test
    fun supportsListsRangesStepsAndSundaySeven() {
        val schedule = CronSchedule.parse("5 8-10/2 * * 7")!!
        val from = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli()
        val next = schedule.nextTimeAfter(from, utc)!!
        val local = Instant.ofEpochMilli(next).atZone(utc)
        assertEquals(5, local.minute)
        assertEquals(8, local.hour)
        assertEquals(0, local.dayOfWeek.value % 7)
    }

    @Test
    fun usesCronOrSemanticsWhenBothDayFieldsAreRestricted() {
        val schedule = CronSchedule.parse("0 0 1 * 0")!!
        val from = Instant.parse("2026-08-01T00:01:00Z").toEpochMilli()
        val next = schedule.nextTimeAfter(from, utc)!!
        val local = Instant.ofEpochMilli(next).atZone(utc)
        assertTrue(local.dayOfMonth == 1 || local.dayOfWeek == java.time.DayOfWeek.SUNDAY)
    }

    @Test
    fun rejectsMalformedExpressionsAndImpossibleFields() {
        listOf(
            "",
            "* * * *",
            "*/0 * * * *",
            "1-0 * * * *",
            "1//2 * * * *",
            "60 * * * *",
            "* * * 13 *",
            "* * * * 8-9"
        ).forEach { assertNull(it, CronSchedule.parse(it)) }
    }

    @Test
    fun findsTheNextOccurrenceAcrossMonthBoundary() {
        val schedule = CronSchedule.parse("0 0 31 * *")!!
        val from = Instant.parse("2026-02-01T00:00:00Z").toEpochMilli()
        val next = schedule.nextTimeAfter(from, utc)!!
        assertEquals("2026-03-31T00:00:00Z", Instant.ofEpochMilli(next).toString())
    }

    @Test
    fun handlesZoneOffsetsAndDstWithoutReturningAnEarlierInstant() {
        val schedule = CronSchedule.parse("30 2 * * *")!!
        val zone = ZoneId.of("America/New_York")
        val from = Instant.parse("2026-03-08T06:00:00Z").toEpochMilli()
        val next = schedule.nextTimeAfter(from, zone)!!
        assertTrue(next > from)
        assertEquals(2, Instant.ofEpochMilli(next).atZone(zone).hour)
    }
}
