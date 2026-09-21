package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubLayoutReadyActionGateTest {

    @Test
    fun latestNavigationReplacesOlderActionWithoutRegisteringAnotherCallback() {
        val gate = EpubLayoutReadyActionGate()
        val calls = mutableListOf<String>()

        val registration = gate.submit { calls += "old" }
        assertNotNull(registration)
        assertNull(gate.submit { calls += "explicit-toc" })

        gate.consume(registration!!)?.invoke()

        assertTrue(calls == listOf("explicit-toc"))
        assertNull(gate.consume(registration))
    }

    @Test
    fun cancellationInvalidatesOldCallbackAndAllowsNewRegistration() {
        val gate = EpubLayoutReadyActionGate()
        val calls = mutableListOf<String>()

        val cancelledRegistration = gate.submit { calls += "cancelled" }
        assertNotNull(cancelledRegistration)
        gate.cancel()
        val newRegistration = gate.submit { calls += "new" }
        assertNotNull(newRegistration)

        assertNull(gate.consume(cancelledRegistration!!))
        gate.consume(newRegistration!!)?.invoke()

        assertTrue(calls == listOf("new"))
    }

    @Test
    fun duplicateLayoutCallbackCannotExecuteActionTwice() {
        val gate = EpubLayoutReadyActionGate()
        var calls = 0

        val registration = gate.submit { calls++ }
        assertNotNull(registration)

        gate.consume(registration!!)?.invoke()
        assertNull(gate.consume(registration))

        assertTrue(calls == 1)
    }
}
