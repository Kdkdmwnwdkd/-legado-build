package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubRuntimeRenderStateTest {
    @Test
    fun `image completion during animation requires a fresh measurement before capture`() {
        val state = readyState()
        val beforeAnimation = state.sequence
        assertTrue(state.changed(7L, 2L, true))
        assertFalse(state.canCapture)
        assertTrue(state.changed(7L, 2L, false))
        assertTrue(state.needsMetrics)
        assertFalse(state.measured(7L, 1L, false, beforeAnimation))
        assertFalse(state.canCapture)
        assertTrue(state.measured(7L, 2L, false, state.sequence))
        assertTrue(state.canCapture)
    }

    @Test
    fun `late image update invalidates an in flight measurement even when page size is unchanged`() {
        val state = readyState()
        state.requireMetrics()
        val inFlight = state.sequence
        state.changed(7L, 2L, true)
        state.changed(7L, 2L, false)
        assertFalse(state.measured(7L, 2L, false, inFlight))
        assertTrue(state.needsMetrics)
        assertTrue(state.measured(7L, 2L, false, state.sequence))
    }

    @Test
    fun `old chapter callbacks cannot unlock a reused WebView`() {
        val state = readyState()
        val oldSequence = state.sequence
        state.reset(8L)
        assertFalse(state.changed(7L, 30L, false))
        assertFalse(state.measured(7L, 30L, false, oldSequence))
        assertFalse(state.canCapture)
        assertTrue(state.changed(8L, 0L, false))
        assertTrue(state.measured(8L, 0L, false, state.sequence))
        assertEquals(0L, state.visualRevision)
    }

    @Test
    fun `older settled notification does not erase a newer pending layout`() {
        val state = readyState()
        state.changed(7L, 3L, true)
        assertFalse(state.changed(7L, 2L, false))
        assertFalse(state.measured(7L, 2L, false, state.sequence))
        assertTrue(state.layoutPending)
        assertFalse(state.canCapture)
    }

    @Test
    fun `selection deferred layout cannot become a reusable frame`() {
        val state = readyState()
        state.changed(7L, 2L, true)
        repeat(3) { assertFalse(state.measured(7L, 2L, true, state.sequence)) }
        assertFalse(state.canCapture)
        state.changed(7L, 2L, false)
        assertTrue(state.measured(7L, 2L, false, state.sequence))
        assertTrue(state.canCapture)
    }

    @Test
    fun `pixel change rejects an old capture despite unchanged page and layout revision`() {
        val released = mutableListOf<String>()
        val cache = EpubCommittedPageSnapshotCache<String>(released::add)
        val old = EpubCommittedPageSnapshotKey(7L, 7L, 1, 0, 3, 4L, 400, 700, visualRevision = 1L)
        val latest = old.copy(visualRevision = 2L)
        val oldCapture = cache.begin(old)
        assertFalse(cache.complete(oldCapture, latest, "old-image"))
        assertEquals(listOf("old-image"), released)
        assertNull(cache.peek(latest))
        val newCapture = cache.begin(latest)
        assertTrue(cache.complete(newCapture, latest, "loaded-image"))
        assertNull(cache.peek(old))
        assertEquals("loaded-image", cache.peek(latest))
    }

    @Test
    fun `many duplicate requests coalesce without invalidating their own measurement`() {
        val state = readyState()
        state.requireMetrics()
        val sequence = state.sequence
        repeat(50) { state.requireMetrics() }
        assertEquals(sequence, state.sequence)
        assertTrue(state.measured(7L, 1L, false, sequence))
        assertTrue(state.canCapture)
    }

    private fun readyState() = EpubRuntimeRenderState().apply {
        reset(7L)
        changed(7L, 1L, false)
        check(measured(7L, 1L, false, sequence))
    }
}
