package io.legado.app.ui.book.read.page

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedTitleRequestGateTest {

    @Test
    fun contentChangeRejectsEveryOlderRequest() {
        val gate = AdvancedTitleRequestGate()
        gate.nextContent()
        val primary = gate.begin(AdvancedTitleSlot.PRIMARY, "a", "source-a")
        val pair = gate.begin(AdvancedTitleSlot.PAIR, "a-pair", "source-pair")
        assertTrue(gate.markReady(primary))
        assertTrue(gate.markReady(pair))

        gate.nextContent()

        assertFalse(gate.accepts(primary))
        assertFalse(gate.accepts(pair))
        assertFalse(gate.hasCurrent(AdvancedTitleSlot.PRIMARY, "a", "source-a"))
        assertFalse(gate.hasCurrent(AdvancedTitleSlot.PAIR, "a-pair", "source-pair"))
        assertNull(gate.readyToken(AdvancedTitleSlot.PRIMARY))
        assertNull(gate.readyToken(AdvancedTitleSlot.PAIR))
    }

    @Test
    fun latestRequestWinsWhenKeysRepeat() {
        val gate = AdvancedTitleRequestGate()
        gate.nextContent()
        val firstA = gate.begin(AdvancedTitleSlot.PRIMARY, "a", "source")
        gate.begin(AdvancedTitleSlot.PRIMARY, "b", "source")
        val secondA = gate.begin(AdvancedTitleSlot.PRIMARY, "a", "source")

        assertFalse(gate.accepts(firstA))
        assertTrue(gate.accepts(secondA))
        assertTrue(gate.hasCurrent(AdvancedTitleSlot.PRIMARY, "a", "source"))
        assertFalse(gate.hasCurrent(AdvancedTitleSlot.PRIMARY, "b", "source"))
    }

    @Test
    fun sameCompositionKeyFromDifferentSourceStartsANewRequest() {
        val gate = AdvancedTitleRequestGate()
        gate.nextContent()
        val firstSource = gate.begin(AdvancedTitleSlot.PRIMARY, "shared", "source-a")
        assertTrue(gate.markReady(firstSource))
        assertSame(firstSource, gate.readyToken(AdvancedTitleSlot.PRIMARY))

        val secondSource = gate.begin(AdvancedTitleSlot.PRIMARY, "shared", "source-b")

        assertFalse(gate.accepts(firstSource))
        assertTrue(gate.accepts(secondSource))
        assertNull(gate.readyToken(AdvancedTitleSlot.PRIMARY))
        assertFalse(gate.markReady(firstSource))
        assertFalse(gate.hasCurrent(AdvancedTitleSlot.PRIMARY, "shared", "source-a"))
        assertTrue(gate.hasCurrent(AdvancedTitleSlot.PRIMARY, "shared", "source-b"))
    }

    @Test
    fun clearingOneSlotDoesNotInvalidateTheOther() {
        val gate = AdvancedTitleRequestGate()
        gate.nextContent()
        val primary = gate.begin(AdvancedTitleSlot.PRIMARY, "a", "source-a")
        val pair = gate.begin(AdvancedTitleSlot.PAIR, "b", "source-b")
        assertTrue(gate.markReady(primary))
        assertTrue(gate.markReady(pair))

        gate.clear(AdvancedTitleSlot.PRIMARY)

        assertFalse(gate.accepts(primary))
        assertTrue(gate.accepts(pair))
        assertFalse(gate.hasCurrent(AdvancedTitleSlot.PRIMARY, "a", "source-a"))
        assertTrue(gate.hasCurrent(AdvancedTitleSlot.PAIR, "b", "source-b"))
        assertNull(gate.readyToken(AdvancedTitleSlot.PRIMARY))
        assertSame(pair, gate.readyToken(AdvancedTitleSlot.PAIR))
    }

    @Test
    fun failureClearsReadyButKeepsCurrentRequest() {
        val gate = AdvancedTitleRequestGate()
        gate.nextContent()
        val token = gate.begin(AdvancedTitleSlot.PRIMARY, "a", "source-a")
        assertTrue(gate.markReady(token))

        assertTrue(gate.fail(token))

        assertNull(gate.readyToken(AdvancedTitleSlot.PRIMARY))
        assertTrue(gate.accepts(token))
        assertTrue(gate.hasCurrent(AdvancedTitleSlot.PRIMARY, "a", "source-a"))
    }

    @Test
    fun staleFailureCannotClearTheCurrentReadyRequest() {
        val gate = AdvancedTitleRequestGate()
        gate.nextContent()
        val stale = gate.begin(AdvancedTitleSlot.PRIMARY, "a", "source-a")
        val current = gate.begin(AdvancedTitleSlot.PRIMARY, "a", "source-b")
        assertTrue(gate.markReady(current))

        assertFalse(gate.fail(stale))

        assertSame(current, gate.readyToken(AdvancedTitleSlot.PRIMARY))
    }

    @Test
    fun requestIsNotPresentedUntilItsReadyFrameIsPublished() {
        val gate = AdvancedTitleRequestGate()
        gate.nextContent()
        val token = gate.begin(AdvancedTitleSlot.PRIMARY, "a", "source-a")

        assertNull(gate.presentedToken(AdvancedTitleSlot.PRIMARY))
        assertFalse(gate.markPresented(token))
        assertTrue(gate.markReady(token))
        assertNull(gate.presentedToken(AdvancedTitleSlot.PRIMARY))

        assertTrue(gate.markPresented(token))
        assertSame(token, gate.presentedToken(AdvancedTitleSlot.PRIMARY))
    }

    @Test
    fun staleFrameCannotPresentAReplacementRequest() {
        val gate = AdvancedTitleRequestGate()
        gate.nextContent()
        val stale = gate.begin(AdvancedTitleSlot.PRIMARY, "a", "source-a")
        assertTrue(gate.markReady(stale))
        val current = gate.begin(AdvancedTitleSlot.PRIMARY, "b", "source-b")
        assertTrue(gate.markReady(current))

        assertFalse(gate.markPresented(stale))
        assertNull(gate.presentedToken(AdvancedTitleSlot.PRIMARY))
        assertTrue(gate.markPresented(current))
        assertSame(current, gate.presentedToken(AdvancedTitleSlot.PRIMARY))
    }
}
