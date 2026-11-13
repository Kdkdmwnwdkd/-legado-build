package com.shimmer.reader.help.reader

/**
 * 阅读记录的文本导出。
 *
 * 只做字符串拼装，不读数据库也不碰 Android API：数据由调用方查好传进来，
 * 这样格式化规则能直接单测，将来要加「分享前预览」之类的入口也不用重写。
 */
object ReadRecordExporter {

    /** 一天一条，date 用 yyyy-MM-dd，与 readRecordDaily 表一致 */
    data class Day(val date: String, val readTimeMs: Long)

    data class Book(val title: String, val readTimeMs: Long)

    data class Summary(
        val todayTimeMs: Long,
        val totalTimeMs: Long,
        val activeDays: Int,
        val bookCount: Int
    )

    /**
     * 导出文本里统一写成「3 小时 12 分」。
     * 不足一分钟时说「不到 1 分」而不是「0 分」——读了几十秒也是有记录的，写 0 会让人以为没统计上。
     */
    fun formatDuration(millis: Long): String {
        val minutes = millis.coerceAtLeast(0L) / 60_000L
        if (minutes <= 0L) return "不到 1 分"
        val hours = minutes / 60L
        val left = minutes % 60L
        return when {
            hours <= 0L -> "$left 分"
            left == 0L -> "$hours 小时"
            else -> "$hours 小时 $left 分"
        }
    }

    /** CSV 里按整分钟给，够看趋势，也不会把文件撑大 */
    fun minutesOf(millis: Long): Long = millis.coerceAtLeast(0L) / 60_000L

    /**
     * Markdown 报告：概览 + 每日明细 + 书籍排行。
     *
     * @param days 按日期倒序，调用方排好
     * @param books 按累计时长倒序
     */
    fun buildMarkdown(
        summary: Summary,
        days: List<Day>,
        books: List<Book>,
        exportedAt: String
    ): String = buildString {
        appendLine("# 阅读记录")
        appendLine()
        appendLine("导出时间：$exportedAt")
        if (days.isNotEmpty()) {
            appendLine("统计范围：${days.last().date} ~ ${days.first().date}")
        }
        appendLine()

        appendLine("## 概览")
        appendLine()
        appendLine("| 项目 | 数值 |")
        appendLine("| --- | --- |")
        appendLine("| 累计阅读 | ${formatDuration(summary.totalTimeMs)} |")
        appendLine("| 今天 | ${formatDuration(summary.todayTimeMs)} |")
        appendLine("| 有记录的天数 | ${summary.activeDays} 天 |")
        appendLine("| 书架书籍 | ${summary.bookCount} 本 |")
        appendLine()

        appendLine("## 每日明细")
        appendLine()
        if (days.isEmpty()) {
            appendLine("暂无每日阅读记录。")
        } else {
            appendLine("| 日期 | 阅读时长 | 分钟 |")
            appendLine("| --- | --- | --- |")
            days.forEach { day ->
                appendLine("| ${day.date} | ${formatDuration(day.readTimeMs)} | ${minutesOf(day.readTimeMs)} |")
            }
        }
        appendLine()

        appendLine("## 书籍排行")
        appendLine()
        if (books.isEmpty()) {
            appendLine("暂无按书统计。")
        } else {
            appendLine("| 排名 | 书名 | 累计时长 |")
            appendLine("| --- | --- | --- |")
            books.forEachIndexed { index, book ->
                appendLine("| ${index + 1} | ${escapeCell(book.title)} | ${formatDuration(book.readTimeMs)} |")
            }
        }
    }

    /**
     * CSV 只导每日明细，两列：日期、阅读分钟数。
     * 书籍排行不放进来——同一张表里混两种口径，导进表格反而要手工拆。
     */
    fun buildCsv(days: List<Day>): String = buildString {
        appendLine("date,minutes")
        days.forEach { day ->
            appendLine("${day.date},${minutesOf(day.readTimeMs)}")
        }
    }

    /** 书名里可能有竖线或换行，落进表格单元格会把表撑坏 */
    private fun escapeCell(text: String): String {
        return text
            .replace("\r\n", " ")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace("|", "\\|")
            .trim()
            .ifEmpty { "（未命名）" }
    }
}
