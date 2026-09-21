package io.legado.app.model

import io.legado.app.data.entities.BookChapter
import java.net.URI
import java.util.Locale

/**
 * The small, Android-free representation used when deciding what changed in
 * a refreshed table of contents.  Chapter indexes are deliberately not part
 * of the identity: an insertion or a reorder must not look like a new
 * chapter.
 */
data class AutoTaskChapterSnapshot(
    val url: String = "",
    val title: String = "",
    val baseUrl: String = "",
    val isVolume: Boolean = false,
    val startFragmentId: String? = null,
    val endFragmentId: String? = null,
    val index: Int = 0
) {
    val key: String
        get() = AutoTaskChapterIdentity.key(this)

    val titleKey: String
        get() = AutoTaskChapterIdentity.titleKey(title)
}

data class AutoTaskChapterRef(
    val key: String,
    val title: String,
    val index: Int
)

data class AutoTaskChapterMove(
    val key: String,
    val title: String,
    val fromIndex: Int,
    val toIndex: Int
)

/**
 * A structural diff between two chapter lists.  [added] is the only list
 * used for update notifications/cache requests; moves and deletions are
 * exposed separately so callers do not infer them from a count delta.
 */
data class AutoTaskTocDiff(
    val oldSize: Int,
    val newSize: Int,
    val added: List<AutoTaskChapterRef>,
    val removed: List<AutoTaskChapterRef>,
    val moved: List<AutoTaskChapterMove>,
    val duplicateKeys: Set<String>
) {
    val newCount: Int
        get() = added.size

    val inserted: List<AutoTaskChapterRef>
        get() = added.filter { it.index < oldSize }

    val appended: List<AutoTaskChapterRef>
        get() = added.filter { it.index >= oldSize }

    val hasChanges: Boolean
        get() = added.isNotEmpty() || removed.isNotEmpty() || moved.isNotEmpty()

    val hasDuplicateChapters: Boolean
        get() = duplicateKeys.isNotEmpty()

    companion object {
        fun between(
            before: List<AutoTaskChapterSnapshot>,
            after: List<AutoTaskChapterSnapshot>
        ): AutoTaskTocDiff {
            val old = before.mapIndexed { index, chapter ->
                chapter.copy(index = index)
            }
            val new = after.mapIndexed { index, chapter ->
                chapter.copy(index = index)
            }
            val oldCounts = old.groupingBy { it.key }.eachCount()
            val newCounts = new.groupingBy { it.key }.eachCount()
            val duplicateKeys = (oldCounts.keys + newCounts.keys)
                .filterTo(linkedSetOf()) { (oldCounts[it] ?: 0) > 1 || (newCounts[it] ?: 0) > 1 }

            val oldMatched = BooleanArray(old.size)
            val newMatched = BooleanArray(new.size)
            val matches = ArrayList<Pair<Int, Int>>(minOf(old.size, new.size))

            // Pair equal identities in source order.  Keeping a queue per key
            // makes duplicate entries deterministic without pretending that
            // their occurrence number is a durable identity.
            val oldByKey = old.withIndex().groupBy { it.value.key }
            val newByKey = new.withIndex().groupBy { it.value.key }
            (oldByKey.keys intersect newByKey.keys).forEach { key ->
                val oldEntries = oldByKey.getValue(key)
                val newEntries = newByKey.getValue(key)
                val count = minOf(oldEntries.size, newEntries.size)
                repeat(count) { offset ->
                    val oldIndex = oldEntries[offset].index
                    val newIndex = newEntries[offset].index
                    oldMatched[oldIndex] = true
                    newMatched[newIndex] = true
                    matches += oldIndex to newIndex
                }
            }

            // A source occasionally changes a relative URL while keeping a
            // unique title.  Allow that one-to-one fallback, but never use it
            // for repeated titles where it could merge distinct chapters.
            val oldTitleGroups = old.withIndex()
                .filter { !oldMatched[it.index] && it.value.titleKey.isNotBlank() }
                .groupBy { it.value.titleKey }
            val newTitleGroups = new.withIndex()
                .filter { !newMatched[it.index] && it.value.titleKey.isNotBlank() }
                .groupBy { it.value.titleKey }
            oldTitleGroups.forEach { (titleKey, oldEntries) ->
                val newEntries = newTitleGroups[titleKey] ?: return@forEach
                if (oldEntries.size != 1 || newEntries.size != 1) return@forEach
                val oldIndex = oldEntries.single().index
                val newIndex = newEntries.single().index
                oldMatched[oldIndex] = true
                newMatched[newIndex] = true
                matches += oldIndex to newIndex
            }

            val added = new.indices
                .filter { !newMatched[it] }
                .map { index -> new[index].toRef() }
            val removed = old.indices
                .filter { !oldMatched[it] }
                .map { index -> old[index].toRef() }
            val moved = matches
                .filter { (oldIndex, newIndex) -> oldIndex != newIndex }
                .sortedBy { (_, newIndex) -> newIndex }
                .map { (oldIndex, newIndex) ->
                    AutoTaskChapterMove(
                        key = new[newIndex].key,
                        title = new[newIndex].title,
                        fromIndex = oldIndex,
                        toIndex = newIndex
                    )
                }

            return AutoTaskTocDiff(
                oldSize = old.size,
                newSize = new.size,
                added = added,
                removed = removed,
                moved = moved,
                duplicateKeys = duplicateKeys
            )
        }
    }
}

private fun AutoTaskChapterSnapshot.toRef(): AutoTaskChapterRef =
    AutoTaskChapterRef(key = key, title = title, index = index)

/** Stable identity rules shared by refresh, notification and cache paths. */
object AutoTaskChapterIdentity {

    fun key(chapter: AutoTaskChapterSnapshot): String {
        val fragmentKey = listOf(chapter.startFragmentId, chapter.endFragmentId)
            .map { it?.trim().orEmpty() }
            .filter { it.isNotBlank() }
            .joinToString("|")
        if (chapter.isVolume) {
            if (fragmentKey.isNotBlank()) return "fragment:$fragmentKey"
            titleKey(chapter.title).takeIf { it.isNotBlank() }?.let { return "volume:$it" }
        }
        canonicalUrl(chapter.url, chapter.baseUrl)
            .takeIf { it.isNotBlank() }
            ?.let { return "url:$it" }
        if (fragmentKey.isNotBlank()) return "fragment:$fragmentKey"
        titleKey(chapter.title).takeIf { it.isNotBlank() }?.let { return "title:$it" }
        // A completely empty chapter is malformed, but retaining its position
        // keeps the diff deterministic and prevents all empty entries merging.
        return "position:${chapter.index}"
    }

    fun titleKey(title: String): String = title
        .trim()
        .replace(WHITESPACE, " ")
        .lowercase(Locale.ROOT)

    fun canonicalUrl(url: String, baseUrl: String = ""): String {
        val raw = url.trim()
        if (raw.isBlank()) return ""
        return runCatching {
            val uri = if (baseUrl.trim().isNotBlank()) {
                URI(baseUrl.trim()).resolve(raw)
            } else {
                URI(raw)
            }.normalize()
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT)
            if (scheme != null && host != null) {
                URI(
                    scheme,
                    uri.userInfo,
                    host,
                    normalizedPort(uri, scheme),
                    uri.path,
                    uri.query,
                    uri.fragment
                ).toString()
            } else {
                uri.toString()
            }
        }.getOrElse {
            // Keep malformed/data URLs usable as identities while removing
            // inconsequential surrounding whitespace.
            raw.replace(WHITESPACE, "")
        }
    }

    private fun normalizedPort(uri: URI, scheme: String): Int {
        if (uri.port < 0) return -1
        if (scheme == "http" && uri.port == 80) return -1
        if (scheme == "https" && uri.port == 443) return -1
        return uri.port
    }

    private val WHITESPACE = Regex("\\s+")
}

fun BookChapter.toAutoTaskChapterSnapshot(): AutoTaskChapterSnapshot =
    AutoTaskChapterSnapshot(
        url = url,
        title = title,
        baseUrl = baseUrl,
        isVolume = isVolume,
        startFragmentId = startFragmentId,
        endFragmentId = endFragmentId,
        index = index
    )

fun List<BookChapter>.toAutoTaskChapterSnapshots(): List<AutoTaskChapterSnapshot> =
    map(BookChapter::toAutoTaskChapterSnapshot)
