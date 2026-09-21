package io.legado.app.model

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.legado.app.R
import io.legado.app.api.controller.BookController
import io.legado.app.constant.AppConst
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.isLocal
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.isTrue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.Wrapper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Built-in actions returned by an AutoTask script. */
object AutoTaskProtocol {

    data class HandleResult(
        val handled: Boolean,
        val summary: String? = null,
        val details: List<String> = emptyList()
    )

    suspend fun handle(
        result: Any?,
        context: Context,
        taskName: String? = null,
        logger: ((String) -> Unit)? = null
    ): HandleResult {
        val actions = AutoTaskActionParser.parse(result) ?: return HandleResult(false)
        val summaries = ArrayList<String>(actions.size)
        for (action in actions) {
            currentCoroutineContext().ensureActive()
            val summary = handleAction(action, context, taskName)
            if (summary.isNotBlank()) {
                summaries += summary
                logger?.invoke(summary)
            }
        }
        return HandleResult(
            handled = true,
            summary = summaries.joinToString(" | ").ifBlank { null },
            details = summaries
        )
    }

    private suspend fun handleAction(
        action: Map<String, Any?>,
        context: Context,
        taskName: String?
    ): String {
        val type = action.string("type")?.lowercase(Locale.ROOT).orEmpty()
        return when (type) {
            "refreshtoc" -> handleRefreshToc(action, context)
            "notify" -> handleNotify(action, context, taskName)
            else -> throw IllegalArgumentException("unknown auto task action: $type")
        }
    }

    private suspend fun handleRefreshToc(
        action: Map<String, Any?>,
        context: Context
    ): String {
        val bookUrl = action.string("bookUrl").orEmpty()
        if (bookUrl.isBlank()) return "refreshToc 缺少 bookUrl"
        val book = appDb.bookDao.getBook(bookUrl)
            ?: return "refreshToc 未找到书籍"

        return AutoTaskBookOperationGate.withBook(bookUrl) {
            currentCoroutineContext().ensureActive()
            val before = appDb.bookChapterDao.getChapterList(bookUrl)
            val refresh = BookController.refreshToc(mapOf("url" to listOf(bookUrl)))
            if (!refresh.isSuccess) {
                return@withBook "《${book.name}》刷新失败: ${refresh.errorMsg}"
            }
            currentCoroutineContext().ensureActive()
            val after = appDb.bookChapterDao.getChapterList(bookUrl)
            val diff = AutoTaskTocDiff.between(
                before.toAutoTaskChapterSnapshots(),
                after.toAutoTaskChapterSnapshots()
            )
            val notify = action.map("notify")
            val notifyEnabled = notify?.boolean("enable", true) ?: false
            val notifyMin = (notify?.int("minCount") ?: 1).coerceAtLeast(1)
            val cache = action.map("cache")
            val cacheEnabled = cache?.boolean("enable", false) ?: false
            val shouldNotify = notifyEnabled && diff.newCount >= notifyMin
            if (shouldNotify) {
                notifyBookUpdate(
                    context = context,
                    book = book,
                    newCount = diff.newCount,
                    latestTitle = latestChapterTitle(after),
                    titleTemplate = notify?.string("title"),
                    contentTemplate = notify?.string("content")
                )
            }
            val queued = if (cacheEnabled && diff.newCount > 0 && !book.isLocal) {
                queueNewChapterRanges(context, book, diff.added.map { it.index })
            } else {
                0
            }
            buildRefreshSummary(book, diff, shouldNotify, queued)
        }
    }

    private fun queueNewChapterRanges(
        context: Context,
        book: Book,
        indexes: List<Int>
    ): Int {
        if (indexes.isEmpty()) return 0
        val sorted = indexes.distinct().sorted()
        var start = sorted.first()
        var previous = start
        var queued = 0
        fun enqueue(rangeStart: Int, rangeEnd: Int) {
            if (rangeStart > rangeEnd) return
            CacheBook.start(context, book, rangeStart, rangeEnd)
            queued += rangeEnd - rangeStart + 1
        }
        sorted.drop(1).forEach { index ->
            if (index == previous + 1) {
                previous = index
            } else {
                enqueue(start, previous)
                start = index
                previous = index
            }
        }
        enqueue(start, previous)
        return queued
    }

    private fun handleNotify(
        action: Map<String, Any?>,
        context: Context,
        taskName: String?
    ): String {
        val time = formatTime(System.currentTimeMillis(), "MM-dd HH:mm")
        val title = formatCommonTemplate(
            action.string("title") ?: context.getString(R.string.auto_task_notify_title),
            taskName,
            time
        )
        val content = formatCommonTemplate(
            action.string("content")
                ?: context.getString(R.string.auto_task_notify_content, taskName.orEmpty()),
            taskName,
            time
        )
        val priority = when (action.string("level")?.lowercase(Locale.ROOT)) {
            "high", "error", "fail", "failed" -> NotificationCompat.PRIORITY_HIGH
            "low" -> NotificationCompat.PRIORITY_LOW
            else -> NotificationCompat.PRIORITY_DEFAULT
        }
        val id = action.int("id")?.let {
            NotificationId.AutoTaskNotifyBase + (it and 0x7fff)
        } ?: run {
            NotificationId.AutoTaskNotifyBase +
                ("${taskName.orEmpty()}|$title|$content".hashCode() and 0x7fff)
        }
        NotificationManagerCompat.from(context).notify(
            id,
            NotificationCompat.Builder(context, AppConst.channelIdWeb)
                .setSmallIcon(R.drawable.ic_web_service_noti)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(priority)
                .build()
        )
        return "通知: $title"
    }

    private fun notifyBookUpdate(
        context: Context,
        book: Book,
        newCount: Int,
        latestTitle: String?,
        titleTemplate: String?,
        contentTemplate: String?
    ) {
        val time = formatTime(System.currentTimeMillis(), "MM-dd HH:mm")
        val defaultTitle = context.getString(R.string.auto_task_book_update_title, book.name)
        val defaultContent = if (latestTitle.isNullOrBlank()) {
            context.getString(R.string.auto_task_book_update_content_count, newCount)
        } else {
            context.getString(R.string.auto_task_book_update_content, newCount, latestTitle)
        }
        val title = formatBookTemplate(
            titleTemplate ?: defaultTitle,
            book,
            newCount,
            latestTitle,
            time
        )
        val content = formatBookTemplate(
            contentTemplate ?: defaultContent,
            book,
            newCount,
            latestTitle,
            time
        )
        val id = NotificationId.AutoTaskBookUpdateBase +
            (book.bookUrl.hashCode() and Int.MAX_VALUE) % 10_000
        NotificationManagerCompat.from(context).notify(
            id,
            NotificationCompat.Builder(context, AppConst.channelIdWeb)
                .setSmallIcon(R.drawable.ic_web_service_noti)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
        )
    }

    private fun buildRefreshSummary(
        book: Book,
        diff: AutoTaskTocDiff,
        notified: Boolean,
        queued: Int
    ): String {
        val name = book.name.ifBlank { book.bookUrl }
        val parts = ArrayList<String>(4)
        parts += "《$name》"
        parts += if (diff.newCount > 0) "+${diff.newCount}" else "无更新"
        if (diff.inserted.isNotEmpty()) parts += "插入${diff.inserted.size}"
        if (diff.removed.isNotEmpty()) parts += "删除${diff.removed.size}"
        if (diff.moved.isNotEmpty() && diff.added.isEmpty()) parts += "重排"
        if (diff.hasDuplicateChapters) parts += "重复身份${diff.duplicateKeys.size}"
        if (notified) parts += "已通知"
        if (queued > 0) parts += "已排队缓存$queued"
        return parts.joinToString(" ")
    }

    private fun latestChapterTitle(list: List<BookChapter>): String? =
        list.lastOrNull { !it.isVolume }?.title ?: list.lastOrNull()?.title

    private fun formatBookTemplate(
        template: String,
        book: Book,
        newCount: Int,
        latestTitle: String?,
        time: String
    ): String = template
        .replace("{book}", book.name)
        .replace("{author}", book.author)
        .replace("{newCount}", newCount.toString())
        .replace("{chapter}", latestTitle.orEmpty())
        .replace("{time}", time)

    private fun formatCommonTemplate(template: String, taskName: String?, time: String): String =
        template.replace("{task}", taskName.orEmpty()).replace("{time}", time)

    private fun formatTime(time: Long, pattern: String): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(time))

    private fun Map<String, Any?>.string(key: String): String? =
        valueIgnoreCase(key)?.toString()?.trim()?.takeIf { it.isNotBlank() }

    private fun Map<String, Any?>.int(key: String): Int? = when (val value = valueIgnoreCase(key)) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    private fun Map<String, Any?>.boolean(key: String, default: Boolean): Boolean =
        when (val value = valueIgnoreCase(key)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.isTrue(default)
            else -> default
        }

    private fun Map<String, Any?>.map(key: String): Map<String, Any?>? =
        valueIgnoreCase(key).asStringMap()

    private fun Map<String, Any?>.valueIgnoreCase(key: String): Any? =
        this[key] ?: entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

    private fun Any?.asStringMap(): Map<String, Any?>? = when (this) {
        is Map<*, *> -> LinkedHashMap<String, Any?>().also { out ->
            forEach { (key, value) -> if (key != null) out[key.toString()] = value }
        }
        else -> null
    }
}

/** Isolated parser so malformed script return values never reach action code. */
object AutoTaskActionParser {
    fun parse(result: Any?): List<Map<String, Any?>>? {
        if (result == null) return null
        val json = when (result) {
            is String -> result
            is Scriptable -> runCatching {
                GSON.toJson(result.toJsonCompatible())
            }.getOrNull()
            else -> runCatching { GSON.toJson(result) }.getOrNull()
        } ?: return null
        return parseJson(json)
    }

    /** Convert Rhino values before handing them to Gson; Rhino objects are not
     * Gson beans and serializing them directly can recurse through prototypes. */
    private fun Any?.toJsonCompatible(): Any? = when (this) {
        null, is String, is Number, is Boolean -> this
        Undefined.instance -> null
        is Wrapper -> unwrap().toJsonCompatible()
        is NativeArray -> {
            ids.sortedBy { it.toString().toIntOrNull() ?: Int.MAX_VALUE }
                .map { key -> scriptableProperty(this, key).toJsonCompatible() }
        }
        is Scriptable -> {
            LinkedHashMap<String, Any?>().also { out ->
                ids.forEach { key ->
                    val name = key.toString()
                    out[name] = scriptableProperty(this, key).toJsonCompatible()
                }
            }
        }
        is Map<*, *> -> LinkedHashMap<String, Any?>().also { out ->
            forEach { (key, value) -> if (key != null) out[key.toString()] = value.toJsonCompatible() }
        }
        is Iterable<*> -> map { it.toJsonCompatible() }
        else -> this
    }

    private fun scriptableProperty(scriptable: Scriptable, key: Any): Any? = when (key) {
        is Number -> scriptable.get(key.toInt(), scriptable)
        else -> ScriptableObject.getProperty(scriptable, key.toString())
    }

    private fun parseJson(text: String): List<Map<String, Any?>>? {
        val trimmed = text.trim()
        return when {
            trimmed.isJsonArray() -> GSON.fromJsonArray<Map<String, Any?>>(trimmed)
                .getOrNull()?.mapNotNull { it.asStringMap() }
            trimmed.isJsonObject() -> {
                val root = GSON.fromJsonObject<Map<String, Any?>>(trimmed).getOrNull()
                    ?.asStringMap() ?: return null
                val actions = root.valueIgnoreCase("actions")
                when (actions) {
                    is List<*> -> actions.mapNotNull { it.asStringMap() }
                    else -> if (root.valueIgnoreCase("type") != null) listOf(root) else null
                }
            }
            else -> null
        }
    }

    private fun Any?.asStringMap(): Map<String, Any?>? = when (this) {
        is Map<*, *> -> LinkedHashMap<String, Any?>().also { out ->
            forEach { (key, value) -> if (key != null) out[key.toString()] = value }
        }
        else -> null
    }

    private fun Map<String, Any?>.valueIgnoreCase(key: String): Any? =
        this[key] ?: entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
}
