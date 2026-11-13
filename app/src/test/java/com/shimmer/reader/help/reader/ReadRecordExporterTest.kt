package com.shimmer.reader.help.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadRecordExporterTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    private val summary = ReadRecordExporter.Summary(
        todayTimeMs = 46 * minute,
        totalTimeMs = 132 * hour + 8 * minute,
        activeDays = 88,
        bookCount = 41
    )

    @Test
    fun formatsDurationByUnit() {
        assertEquals("不到 1 分", ReadRecordExporter.formatDuration(0L))
        assertEquals("不到 1 分", ReadRecordExporter.formatDuration(59_000L))
        assertEquals("1 分", ReadRecordExporter.formatDuration(minute))
        assertEquals("59 分", ReadRecordExporter.formatDuration(59 * minute))
        assertEquals("1 小时", ReadRecordExporter.formatDuration(hour))
        assertEquals("2 小时 5 分", ReadRecordExporter.formatDuration(2 * hour + 5 * minute))
    }

    @Test
    fun negativeDurationIsTreatedAsZero() {
        assertEquals("不到 1 分", ReadRecordExporter.formatDuration(-1000L))
    }

    @Test
    fun markdownContainsOverviewAndRange() {
        val text = ReadRecordExporter.buildMarkdown(
            summary = summary,
            days = listOf(
                ReadRecordExporter.Day("2026-11-13", 46 * minute),
                ReadRecordExporter.Day("2026-11-12", 2 * hour)
            ),
            books = listOf(ReadRecordExporter.Book("剑来", 30 * hour)),
            exportedAt = "2026-11-13 23:55"
        )
        assertTrue(text.contains("| 累计阅读 | 132 小时 8 分 |"))
        assertTrue(text.contains("| 今天 | 46 分 |"))
        assertTrue(text.contains("统计范围：2026-11-12 ~ 2026-11-13"))
        assertTrue(text.contains("| 2026-11-13 | 46 分 | 46 |"))
        assertTrue(text.contains("| 1 | 剑来 | 30 小时 |"))
    }

    @Test
    fun markdownEscapesPipeAndNewlineInTitle() {
        val text = ReadRecordExporter.buildMarkdown(
            summary = summary,
            days = emptyList(),
            books = listOf(ReadRecordExporter.Book("标题|带竖线\n还有换行", 5 * minute)),
            exportedAt = "2026-11-13 23:55"
        )
        assertTrue(text.contains("标题\\|带竖线 还有换行"))
        // 表头固定三列，被撑坏的话这一行会有更多竖线；转义的 \| 不算分隔符
        val row = text.lineSequence().first { it.contains("标题") }
        val separators = Regex("""(?<!\\)\|""").findAll(row).count()
        assertEquals(4, separators)
    }

    @Test
    fun markdownHandlesEmptyData() {
        val text = ReadRecordExporter.buildMarkdown(
            summary = ReadRecordExporter.Summary(0L, 0L, 0, 0),
            days = emptyList(),
            books = emptyList(),
            exportedAt = "2026-11-13 23:55"
        )
        assertTrue(text.contains("暂无每日阅读记录。"))
        assertTrue(text.contains("暂无按书统计。"))
        assertFalse(text.contains("统计范围"))
    }

    @Test
    fun csvHasHeaderAndRoundsDownToMinutes() {
        val csv = ReadRecordExporter.buildCsv(
            listOf(
                ReadRecordExporter.Day("2026-11-13", 46 * minute + 30_000L),
                ReadRecordExporter.Day("2026-11-12", 59_000L)
            )
        )
        assertEquals(
            listOf("date,minutes", "2026-11-13,46", "2026-11-12,0"),
            csv.trim().lines()
        )
    }

    @Test
    fun csvOfEmptyDataOnlyHasHeader() {
        assertEquals(listOf("date,minutes"), ReadRecordExporter.buildCsv(emptyList()).trim().lines())
    }
}
