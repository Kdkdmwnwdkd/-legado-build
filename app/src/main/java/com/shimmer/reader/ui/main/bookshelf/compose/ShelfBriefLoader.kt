package com.shimmer.reader.ui.main.bookshelf.compose

import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.ReadRecordDaily
import com.shimmer.reader.ui.about.ReadRecordWidgetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * 汇总书架顶部卡片需要的数据。
 *
 * 只读本地库，不做任何网络请求，也不写库；调用方放到 IO 线程执行即可。
 */
object ShelfBriefLoader {

    private const val WEEK_DAYS = 7

    suspend fun load(): ShelfBrief = withContext(Dispatchers.IO) {
        val daily = runCatching { appDb.readRecordDailyDao.allDesc }.getOrDefault(emptyList())
        val goalMinutes = runCatching {
            ReadRecordWidgetStore.loadGoalConfig().dailyGoalMinutes
        }.getOrDefault(0).coerceAtLeast(0)
        val recent = runCatching { appDb.readRecentBookDao.recentBooks(1).firstOrNull() }
            .getOrNull()
        build(daily, goalMinutes, recent?.name, recentProgressOf(recent), LocalDate.now(), recent?.bookUrl)
    }

    /**
     * 拆出来便于单独验证：给定每日记录与目标，算出卡片要显示的那几个数。
     */
    fun build(
        daily: List<ReadRecordDaily>,
        goalMinutes: Int,
        recentTitle: String?,
        recentProgress: String?,
        today: LocalDate,
        recentBookUrl: String? = null
    ): ShelfBrief {
        val minutesByDate = daily.associate { record ->
            record.date to readMillisToMinutes(record.readTime)
        }
        val week = (WEEK_DAYS - 1 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            minutesByDate[date.toString()] ?: 0
        }
        return ShelfBrief(
            todayMinutes = minutesByDate[today.toString()] ?: 0,
            goalMinutes = goalMinutes,
            streakDays = streakOf(minutesByDate, today),
            weekMinutes = week,
            weekLabels = (WEEK_DAYS - 1 downTo 0).map { offset ->
                today.minusDays(offset.toLong()).dayOfWeek.shortLabel()
            },
            recentTitle = recentTitle,
            recentProgress = recentProgress,
            recentBookUrl = recentBookUrl
        )
    }

    /**
     * 连续阅读天数。今天还没读不算断，从昨天往前数；
     * 这样早上打开应用不会看到「连续 0 天」而以为记录丢了。
     */
    private fun streakOf(minutesByDate: Map<String, Int>, today: LocalDate): Int {
        var cursor = if ((minutesByDate[today.toString()] ?: 0) > 0) today else today.minusDays(1)
        var streak = 0
        // 上限一年，避免数据异常时无限循环。
        while (streak < 366) {
            val value = minutesByDate[cursor.toString()] ?: 0
            if (value <= 0) break
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    private fun DayOfWeek.shortLabel(): String =
        runCatching { getDisplayName(TextStyle.NARROW, Locale.getDefault()) }
            .getOrDefault("")
            .ifBlank { name.take(1) }

    private fun recentProgressOf(book: com.shimmer.reader.data.entities.Book?): String? {
        book ?: return null
        val title = book.durChapterTitle?.takeIf { it.isNotBlank() }
        val index = book.durChapterIndex
        val total = book.totalChapterNum
        return when {
            title != null && total > 0 -> "$title · ${index + 1}/$total"
            title != null -> title
            total > 0 -> "${index + 1}/$total"
            book.author.isNotBlank() -> book.author
            else -> null
        }
    }
}
