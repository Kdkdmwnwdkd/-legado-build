package io.legado.app.ui.main.bookshelf.compose

import com.google.gson.reflect.TypeToken
import io.legado.app.data.dao.BookShelfDisplay
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.getFile
import splitties.init.appCtx
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.LinkedHashMap

object BookshelfSnapshotStore {

    private const val VERSION = 1
    private const val MAX_MEMORY_SNAPSHOTS = 12
    private const val MAX_DISK_SNAPSHOTS = 24

    private val snapshotType = object : TypeToken<Snapshot>() {}.type
    private val rootDir: File
        get() = appCtx.filesDir.getFile("bookshelfSnapshots").apply { mkdirs() }

    private val memorySnapshots = object : LinkedHashMap<String, Snapshot>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot>?): Boolean {
            return size > MAX_MEMORY_SNAPSHOTS
        }
    }

    fun buildKey(
        style: String,
        groupId: Long,
        sort: Int,
        tagFilter: String,
        groups: List<BookGroup>
    ): String {
        val groupSignature = groups
            .sortedWith(compareBy<BookGroup> { it.order }.thenBy { it.groupId })
            .joinToString(separator = "|") {
                "${it.groupId},${it.groupName},${it.cover.orEmpty()},${it.order}," +
                    "${it.enableRefresh},${it.show},${it.bookSort},${it.onlyUpdateRead}"
            }
        val groupTags = AppConfig.bookshelfGroupTags[groupId]
            .orEmpty()
            .joinToString(separator = "|")
        val hiddenTags = AppConfig.bookshelfHiddenTags[groupId]
            .orEmpty()
            .sorted()
            .joinToString(separator = "|")
        val rawKey = listOf(
            "version=$VERSION",
            "style=$style",
            "groupId=$groupId",
            "sort=$sort",
            "tagFilter=${tagFilter.trim()}",
            "layout=${AppConfig.bookshelfLayout}",
            "margin=${AppConfig.bookshelfMargin}",
            "listStyle=${AppConfig.bookshelfListItemStyle}",
            "showUnread=${AppConfig.showUnread}",
            "showLastUpdateTime=${AppConfig.showLastUpdateTime}",
            "groupTags=$groupTags",
            "hiddenTags=$hiddenTags",
            "groups=$groupSignature"
        ).joinToString(separator = "\n")
        return "$style-${sha256(rawKey).take(32)}"
    }

    fun getMemory(key: String): List<BookshelfItemUi>? {
        return synchronized(memorySnapshots) {
            memorySnapshots[key]?.takeIf { it.isValid(key) }?.toItems()
        }
    }

    fun read(key: String): List<BookshelfItemUi>? {
        getMemory(key)?.let { return it }
        return runCatching {
            val file = snapshotFile(key)
            if (!file.exists()) return null
            InputStreamReader(file.inputStream(), Charsets.UTF_8).use { reader ->
                (GSON.fromJson(reader, snapshotType) as? Snapshot)
                    ?.takeIf { it.isValid(key) }
                    ?.also { snapshot ->
                        synchronized(memorySnapshots) {
                            memorySnapshots[key] = snapshot
                        }
                    }
                    ?.toItems()
            }
        }.getOrNull()
    }

    fun save(key: String, items: List<BookshelfItemUi>) {
        if (items.isEmpty()) {
            remove(key)
            return
        }
        val snapshot = Snapshot(
            version = VERSION,
            key = key,
            savedAt = System.currentTimeMillis(),
            items = items.mapNotNull { item ->
                when (item) {
                    is BookshelfBookItemUi -> SnapshotItem(
                        type = SnapshotItem.TYPE_BOOK,
                        display = item.display.copy(readConfig = null),
                        unreadCount = item.unreadCount,
                        hasNewChapter = item.hasNewChapter,
                        tags = item.tags,
                        lastUpdateText = item.lastUpdateText
                    )
                    is BookshelfFolderItemUi -> SnapshotItem(
                        type = SnapshotItem.TYPE_FOLDER,
                        group = item.group
                    )
                }
            }
        )
        synchronized(memorySnapshots) {
            memorySnapshots[key] = snapshot
        }
        runCatching {
            val target = snapshotFile(key)
            val temp = snapshotFile("$key.tmp")
            OutputStreamWriter(temp.outputStream(), Charsets.UTF_8).use { writer ->
                GSON.toJson(snapshot, snapshotType, writer)
            }
            if (target.exists()) {
                target.delete()
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            trimDiskSnapshots()
        }
    }

    private fun remove(key: String) {
        synchronized(memorySnapshots) {
            memorySnapshots.remove(key)
        }
        runCatching {
            snapshotFile(key).delete()
            snapshotFile("$key.tmp").delete()
        }
    }

    private fun snapshotFile(key: String): File {
        return rootDir.getFile("${key.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.json")
    }

    private fun trimDiskSnapshots() {
        rootDir.listFiles()
            ?.filter { it.isFile && it.extension == "json" && !it.name.endsWith(".tmp.json") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_DISK_SNAPSHOTS)
            ?.forEach { it.delete() }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun Snapshot.isValid(expectedKey: String): Boolean {
        return version == VERSION && key == expectedKey
    }

    private fun Snapshot.toItems(): List<BookshelfItemUi> {
        return items.mapNotNull { item ->
            when (item.type) {
                SnapshotItem.TYPE_BOOK -> item.display?.let { display ->
                    BookshelfBookItemUi(
                        display = display,
                        isUpdating = false,
                        unreadCount = item.unreadCount,
                        hasNewChapter = item.hasNewChapter,
                        tags = item.tags.orEmpty(),
                        lastUpdateText = item.lastUpdateText
                    )
                }
                SnapshotItem.TYPE_FOLDER -> item.group?.let(::BookshelfFolderItemUi)
                else -> null
            }
        }
    }

    private data class Snapshot(
        val version: Int = VERSION,
        val key: String = "",
        val savedAt: Long = 0L,
        val items: List<SnapshotItem> = emptyList()
    )

    private data class SnapshotItem(
        val type: String = "",
        val group: BookGroup? = null,
        val display: BookShelfDisplay? = null,
        val unreadCount: Int = 0,
        val hasNewChapter: Boolean = false,
        val tags: List<String>? = null,
        val lastUpdateText: String? = null
    ) {
        companion object {
            const val TYPE_BOOK = "book"
            const val TYPE_FOLDER = "folder"
        }
    }
}
