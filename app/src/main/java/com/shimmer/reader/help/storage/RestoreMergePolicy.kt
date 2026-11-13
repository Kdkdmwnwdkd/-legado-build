package com.shimmer.reader.help.storage

import com.shimmer.reader.data.entities.ReadRecentBook
import com.shimmer.reader.data.entities.ReadRecordDaily

/**
 * 恢复备份时，本机可能已经有一份更新的数据（例如连续恢复新旧两个备份）。
 * 这里统一判断「备份里的这条要不要覆盖本机」，避免把更大的阅读时长改小、
 * 或把更晚的最近阅读时间改早。
 */
object RestoreMergePolicy {

    /**
     * 每日阅读时长按日期唯一。同一天两台设备都有记录时保留较大的那份，
     * 既不会把本机累计的时间改小，也不会把同一天的数据叠加成两倍。
     */
    fun dailyReadTimeShouldReplace(local: ReadRecordDaily?, incoming: ReadRecordDaily): Boolean {
        if (local == null) return true
        if (local.date != incoming.date) return true
        return incoming.readTime > local.readTime
    }

    /**
     * 最近阅读按书唯一，保留更晚的那次阅读时间。
     */
    fun recentBookShouldReplace(local: ReadRecentBook?, incoming: ReadRecentBook): Boolean {
        if (local == null) return true
        if (local.bookUrl != incoming.bookUrl) return true
        return incoming.lastRead > local.lastRead
    }
}
