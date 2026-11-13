package com.shimmer.reader.help.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * 一份备份里装了什么。
 *
 * 恢复是破坏性操作：选错文件、选成旧备份，之前的数据就被覆盖了。
 * 所以选完文件先读一遍内容给用户看，确认了再动手。
 */
data class BackupSummary(
    val books: Int = 0,
    val bookmarks: Int = 0,
    val sources: Int = 0,
    val groups: Int = 0,
    val readRecords: Int = 0,
    /** zip 里条目的最后修改时间，取最大值 */
    val createdAt: Long = 0L,
    val sizeBytes: Long = 0L
) {
    val isEmpty: Boolean
        get() = books == 0 && bookmarks == 0 && sources == 0 && groups == 0 && readRecords == 0
}

object BackupSummaryReader {

    /** 只关心这几项，其余条目直接跳过，不解析 */
    private const val KEY_BOOKS = "bookshelf.json"
    private const val KEY_BOOKMARKS = "bookmark.json"
    private const val KEY_SOURCES = "bookSource.json"
    private const val KEY_GROUPS = "bookGroup.json"
    private const val KEY_READ_RECORDS = "readRecord.json"

    suspend fun read(context: Context, uri: Uri): BackupSummary? = withContext(IO) {
        val size = runCatching {
            DocumentFile.fromSingleUri(context, uri)?.length() ?: 0L
        }.getOrDefault(0L)
        val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: return@withContext null
        stream.use { readFrom(it, size) }
    }

    /**
     * 从 zip 流里统计。读不出 zip 头（不是 zip、文件损坏）时返回 null。
     *
     * 这里不用整包读进内存：书源文件动辄几 MB，逐条 skip 就够数了。
     */
    fun readFrom(input: InputStream, sizeBytes: Long): BackupSummary? {
        var books = 0
        var bookmarks = 0
        var sources = 0
        var groups = 0
        var readRecords = 0
        var createdAt = 0L
        var sawEntry = false
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    sawEntry = true
                    if (entry.time > createdAt) createdAt = entry.time
                    when (entry.name.substringAfterLast('/')) {
                        KEY_BOOKS -> books = countArray(zip)
                        KEY_BOOKMARKS -> bookmarks = countArray(zip)
                        KEY_SOURCES -> sources = countArray(zip)
                        KEY_GROUPS -> groups = countArray(zip)
                        KEY_READ_RECORDS -> readRecords = countArray(zip)
                    }
                }
            }
        } catch (e: Exception) {
            // 不是 zip 或中途损坏：已读到的部分没有意义，整体当失败处理
            return null
        }
        if (!sawEntry) return null
        return BackupSummary(
            books = books,
            bookmarks = bookmarks,
            sources = sources,
            groups = groups,
            readRecords = readRecords,
            createdAt = createdAt,
            sizeBytes = sizeBytes
        )
    }

    /**
     * 数一个 JSON 数组有多少元素。字段内容一律跳过，只看结构。
     * 注意不能关闭 reader——关掉会把底层的 zip 流一起关掉，后面的条目就没法读了。
     */
    private fun countArray(input: InputStream): Int {
        return try {
            val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8))
            if (reader.peek() != JsonToken.BEGIN_ARRAY) return 0
            var count = 0
            reader.beginArray()
            while (reader.hasNext()) {
                reader.skipValue()
                count++
            }
            reader.endArray()
            count
        } catch (e: Exception) {
            0
        }
    }
}
