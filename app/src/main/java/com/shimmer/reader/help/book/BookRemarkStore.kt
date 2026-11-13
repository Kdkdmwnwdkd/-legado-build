package com.shimmer.reader.help.book

import com.shimmer.reader.constant.PreferKey
import com.shimmer.reader.utils.GSON
import com.shimmer.reader.utils.fromJsonArray
import com.shimmer.reader.utils.getPrefString
import com.shimmer.reader.utils.putPrefString
import com.shimmer.reader.utils.removePref
import splitties.init.appCtx

/**
 * 一本书的一条备注。
 *
 * 没有进 book 表，是因为备注只影响详情页展示，加列要动 schema 与迁移；
 * 这里按 bookUrl 存在偏好里，和 BookCloudEntryModeStore 一样属于本地附加数据。
 */
data class BookRemarkEntry(
    val bookUrl: String = "",
    val remark: String = ""
)

/**
 * 书籍备注的本地存储。
 *
 * 备份走 `bookRemarks.json`（见 `help/storage/Backup.kt`），恢复时按 bookUrl 合并，
 * 备份里的非空备注覆盖本机同名书的备注，本机独有的保留。
 */
object BookRemarkStore {

    /** 详情页一行能显示的长度有限，超过就没有意义了，直接截断 */
    const val MAX_LENGTH = 500

    private const val MAX_LINES = 20

    /**
     * 规整用户输入：统一换行、去掉行尾空白、连续空行压成一行、限制总长度。
     * 纯函数，方便单测。
     */
    fun normalize(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val unified = raw.replace("\r\n", "\n").replace('\r', '\n')
        val kept = mutableListOf<String>()
        var pendingBlank = false
        for (line in unified.split('\n')) {
            if (line.isBlank()) {
                // 开头的空行丢掉，中间连续空行只留一行
                if (kept.isNotEmpty()) pendingBlank = true
                continue
            }
            if (kept.isNotEmpty() && pendingBlank) kept.add("")
            kept.add(line.trimEnd())
            pendingBlank = false
            if (kept.size >= MAX_LINES) break
        }
        var text = kept.joinToString("\n").trim()
        if (text.length > MAX_LENGTH) {
            var cut = text.take(MAX_LENGTH)
            // 别把 emoji 这类代理对切成半个
            if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut = cut.dropLast(1)
            text = cut.trimEnd()
        }
        return text
    }

    fun get(bookUrl: String?): String {
        if (bookUrl.isNullOrBlank()) return ""
        return all().firstOrNull { it.bookUrl == bookUrl }?.remark.orEmpty()
    }

    fun set(bookUrl: String?, raw: String?) {
        if (bookUrl.isNullOrBlank()) return
        val text = normalize(raw)
        val current = all().associateByTo(LinkedHashMap()) { it.bookUrl }
        if (text.isBlank()) {
            current.remove(bookUrl)
        } else {
            current[bookUrl] = BookRemarkEntry(bookUrl, text)
        }
        persist(current.values.toList())
    }

    fun all(): List<BookRemarkEntry> {
        val raw = appCtx.getPrefString(PreferKey.bookRemarks).orEmpty()
        if (raw.isBlank()) return emptyList()
        return GSON.fromJsonArray<BookRemarkEntry>(raw)
            .getOrDefault(emptyList())
            .filter { it.bookUrl.isNotBlank() && it.remark.isNotBlank() }
    }

    fun restoreFromBackup(entries: List<BookRemarkEntry>) {
        if (entries.isEmpty()) return
        val merged = mergeEntries(
            local = all(),
            incoming = entries.map { BookRemarkEntry(it.bookUrl, normalize(it.remark)) }
        )
        persist(merged)
    }

    /**
     * 备份里的备注覆盖同书的本机备注，本机独有的条目保留。
     * 纯函数，不碰偏好，方便单测。
     */
    fun mergeEntries(
        local: List<BookRemarkEntry>,
        incoming: List<BookRemarkEntry>
    ): List<BookRemarkEntry> {
        val result = LinkedHashMap<String, BookRemarkEntry>()
        local.filter { it.bookUrl.isNotBlank() && it.remark.isNotBlank() }
            .forEach { result[it.bookUrl] = it }
        incoming.filter { it.bookUrl.isNotBlank() && it.remark.isNotBlank() }
            .forEach { result[it.bookUrl] = it }
        return result.values.toList()
    }

    private fun persist(list: List<BookRemarkEntry>) {
        if (list.isEmpty()) {
            appCtx.removePref(PreferKey.bookRemarks)
            return
        }
        appCtx.putPrefString(PreferKey.bookRemarks, GSON.toJson(list))
    }
}
