package io.legado.app.ui.book.read.epub

internal data class EpubCommittedPageSnapshotKey(
    val generation: Long,
    val token: Long,
    val viewIdentity: Int,
    val chapterIndex: Int,
    val pageIndex: Int,
    val layoutRevision: Long,
    val viewportWidth: Int,
    val viewportHeight: Int,
    /** Geometry identity includes reader chrome dimensions, never dynamic text. */
    val readerChromeGeometryKey: String = "",
    /** Pixel changes can occur without a size change or a new page count. */
    val visualRevision: Long = 0L
)

internal class EpubCommittedPageSnapshotCache<T>(
    private val release: (T) -> Unit
) {

    data class Request internal constructor(
        val sequence: Long,
        val key: EpubCommittedPageSnapshotKey,
        val sceneRevision: Long
    )

    private data class Entry<T>(
        val key: EpubCommittedPageSnapshotKey,
        val value: T
    )

    private var sequence = 0L
    private var pendingRequest: Request? = null
    private var entry: Entry<T>? = null

    fun begin(key: EpubCommittedPageSnapshotKey, sceneRevision: Long = 0L): Request {
        if (entry?.key != key) releaseEntry()
        return Request(++sequence, key, sceneRevision).also { pendingRequest = it }
    }

    fun complete(
        request: Request,
        currentKey: EpubCommittedPageSnapshotKey?,
        currentSceneRevision: Long,
        value: T
    ): Boolean {
        if (pendingRequest != request || currentKey != request.key ||
            currentSceneRevision != request.sceneRevision
        ) {
            if (pendingRequest == request) pendingRequest = null
            release(value)
            return false
        }
        pendingRequest = null
        releaseEntry()
        entry = Entry(request.key, value)
        return true
    }

    fun complete(
        request: Request,
        currentKey: EpubCommittedPageSnapshotKey?,
        value: T
    ): Boolean = complete(request, currentKey, request.sceneRevision, value)

    fun fail(request: Request): Boolean {
        if (pendingRequest != request) return false
        pendingRequest = null
        return true
    }

    fun contains(key: EpubCommittedPageSnapshotKey): Boolean {
        return entry?.key == key
    }

    fun isPending(key: EpubCommittedPageSnapshotKey): Boolean {
        return pendingRequest?.key == key
    }

    fun peek(key: EpubCommittedPageSnapshotKey): T? {
        return entry?.takeIf { it.key == key }?.value
    }

    fun take(key: EpubCommittedPageSnapshotKey): T? {
        val matched = entry?.takeIf { it.key == key } ?: return null
        entry = null
        return matched.value
    }

    fun invalidate() {
        sequence++
        pendingRequest = null
        releaseEntry()
    }

    private fun releaseEntry() {
        val stale = entry ?: return
        entry = null
        release(stale.value)
    }
}
