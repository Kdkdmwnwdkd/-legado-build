package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubCommittedPageSnapshotCacheTest {

    private val firstPage = key(pageIndex = 0)
    private val secondPage = key(pageIndex = 1)

    @Test
    fun `snapshot is returned only for the exact committed page`() {
        val released = mutableListOf<String>()
        val cache = EpubCommittedPageSnapshotCache<String>(released::add)
        val request = cache.begin(firstPage)

        assertTrue(cache.complete(request, firstPage, "page-0"))
        assertTrue(cache.contains(firstPage))
        assertNull(cache.take(secondPage))
        assertEquals("page-0", cache.take(firstPage))
        assertFalse(cache.contains(firstPage))
        assertTrue(released.isEmpty())
    }

    @Test
    fun `peek keeps the committed snapshot available for consecutive turns`() {
        val released = mutableListOf<String>()
        val cache = EpubCommittedPageSnapshotCache<String>(released::add)
        val request = cache.begin(firstPage)

        assertTrue(cache.complete(request, firstPage, "page-0"))
        assertEquals("page-0", cache.peek(firstPage))
        assertEquals("page-0", cache.peek(firstPage))
        assertTrue(cache.contains(firstPage))
        assertTrue(released.isEmpty())
    }

    @Test
    fun `late capture callback cannot replace a newer request`() {
        val released = mutableListOf<String>()
        val cache = EpubCommittedPageSnapshotCache<String>(released::add)
        val stale = cache.begin(firstPage)
        val current = cache.begin(secondPage)

        assertFalse(cache.complete(stale, firstPage, "stale"))
        assertTrue(cache.isPending(secondPage))
        assertTrue(cache.complete(current, secondPage, "current"))
        assertEquals("current", cache.take(secondPage))
        assertEquals(listOf("stale"), released)
    }

    @Test
    fun `callback is rejected when the visible page changed during capture`() {
        val released = mutableListOf<String>()
        val cache = EpubCommittedPageSnapshotCache<String>(released::add)
        val request = cache.begin(firstPage)

        assertFalse(cache.complete(request, secondPage, "wrong-page"))
        assertFalse(cache.isPending(firstPage))
        assertEquals(listOf("wrong-page"), released)
    }

    @Test
    fun `callback is rejected when the visible scene changed during capture`() {
        val released = mutableListOf<String>()
        val cache = EpubCommittedPageSnapshotCache<String>(released::add)
        val request = cache.begin(firstPage, sceneRevision = 12L)

        assertFalse(
            cache.complete(
                request = request,
                currentKey = firstPage,
                currentSceneRevision = 13L,
                value = "overlay-contaminated"
            )
        )
        assertFalse(cache.isPending(firstPage))
        assertEquals(listOf("overlay-contaminated"), released)
    }

    @Test
    fun `snapshot from an older layout revision is never reused`() {
        val released = mutableListOf<String>()
        val cache = EpubCommittedPageSnapshotCache<String>(released::add)
        val oldRevision = key(pageIndex = 0, layoutRevision = 4L)
        val newRevision = key(pageIndex = 0, layoutRevision = 5L)
        val request = cache.begin(oldRevision)

        assertTrue(cache.complete(request, oldRevision, "old-layout"))
        assertNull(cache.peek(newRevision))
        assertNull(cache.take(newRevision))
        assertEquals("old-layout", cache.take(oldRevision))
    }

    @Test
    fun `snapshot from an older reader chrome geometry is never reused`() {
        val released = mutableListOf<String>()
        val cache = EpubCommittedPageSnapshotCache<String>(released::add)
        val oldGeometry = key(pageIndex = 0, readerChromeGeometryKey = "header-40")
        val newGeometry = key(pageIndex = 0, readerChromeGeometryKey = "header-52")
        val request = cache.begin(oldGeometry)

        assertTrue(cache.complete(request, oldGeometry, "old-chrome"))
        assertNull(cache.peek(newGeometry))
        assertEquals("old-chrome", cache.take(oldGeometry))
    }

    @Test
    fun `invalidation cancels pending work and releases the cached value`() {
        val released = mutableListOf<String>()
        val cache = EpubCommittedPageSnapshotCache<String>(released::add)
        val stored = cache.begin(firstPage)
        assertTrue(cache.complete(stored, firstPage, "stored"))
        val pending = cache.begin(firstPage)

        cache.invalidate()

        assertFalse(cache.complete(pending, firstPage, "late"))
        assertEquals(listOf("stored", "late"), released)
    }

    private fun key(
        pageIndex: Int,
        layoutRevision: Long = 11L,
        readerChromeGeometryKey: String = ""
    ): EpubCommittedPageSnapshotKey {
        return EpubCommittedPageSnapshotKey(
            generation = 7L,
            token = 7L,
            viewIdentity = 41,
            chapterIndex = 3,
            pageIndex = pageIndex,
            layoutRevision = layoutRevision,
            viewportWidth = 1080,
            viewportHeight = 1920,
            readerChromeGeometryKey = readerChromeGeometryKey
        )
    }
}
