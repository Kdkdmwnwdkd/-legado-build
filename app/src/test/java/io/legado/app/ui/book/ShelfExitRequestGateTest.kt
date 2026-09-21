package io.legado.app.ui.book

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfExitRequestGateTest {

    @Test
    fun onlyOneExitDecisionCanBePending() {
        val gate = ShelfExitRequestGate()

        assertTrue(gate.tryBegin())
        assertFalse(gate.tryBegin())
    }

    @Test
    fun cancellingDialogAllowsAnotherExitRequest() {
        val gate = ShelfExitRequestGate()
        assertTrue(gate.tryBegin())

        gate.cancel()

        assertTrue(gate.tryBegin())
    }
}
