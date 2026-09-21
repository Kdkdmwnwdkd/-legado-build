package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDirectActivationGateTest {

    @Test
    fun staleCallbackCannotCommitNewActivation() {
        val gate = EpubDirectActivationGate()

        assertTrue(gate.begin(1L))
        gate.clear()
        assertTrue(gate.begin(2L))
        assertFalse(gate.complete(1L))
        assertTrue(gate.complete(2L))
    }

    @Test
    fun duplicateReadyCallbackCannotStartSecondCommit() {
        val gate = EpubDirectActivationGate()

        assertTrue(gate.begin(7L))
        assertFalse(gate.begin(7L))
        assertTrue(gate.cancel(7L))
        assertFalse(gate.complete(7L))
    }
}
