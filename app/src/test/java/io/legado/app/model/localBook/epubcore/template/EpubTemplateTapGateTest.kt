package io.legado.app.model.localBook.epubcore.template

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubTemplateTapGateTest {
    @Test fun scriptsCannotGrantThemselvesATap() {
        val gate = EpubTemplateTapGate()
        assertFalse(gate.consume(1, 100))
    }

    @Test fun oneTapAllowsOnlyOneAction() {
        val gate = EpubTemplateTapGate()
        gate.record(7, 100)
        assertTrue(gate.consume(7, 200))
        assertFalse(gate.consume(7, 201))
    }

    @Test fun staleChaptersAndDelayedOrFutureEventsAreRejected() {
        val gate = EpubTemplateTapGate()
        gate.record(7, 100)
        assertFalse(gate.consume(8, 120))
        assertFalse(gate.consume(7, 99))
        assertFalse(gate.consume(7, 1_601))
    }

    @Test fun newGestureOrCancellationRevokesThePreviousTap() {
        val gate = EpubTemplateTapGate()
        gate.record(7, 100)
        gate.reset()
        assertFalse(gate.consume(7, 120))
        gate.record(8, 200)
        assertTrue(gate.consume(8, 200))
    }
}
