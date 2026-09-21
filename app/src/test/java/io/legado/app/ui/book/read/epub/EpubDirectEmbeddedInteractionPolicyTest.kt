package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDirectEmbeddedInteractionPolicyTest {

    @Test
    fun `bridge interaction is accepted only during native touch`() {
        val policy = EpubDirectEmbeddedInteractionPolicy()

        assertFalse(policy.onBridgeState(1, true))
        policy.onNativeTouchStarted()
        assertTrue(policy.onBridgeState(1, true))
        policy.onNativeTouchFinished()
        assertFalse(policy.onBridgeState(2, true))
    }

    @Test
    fun `stale bridge completion cannot clear a newer interaction`() {
        val policy = EpubDirectEmbeddedInteractionPolicy()

        policy.onNativeTouchStarted()
        assertTrue(policy.onBridgeState(4, true))
        assertTrue(policy.onBridgeState(3, false))
        assertFalse(policy.onBridgeState(4, false))
    }

    @Test
    fun `current interaction can release and reclaim the gesture`() {
        val policy = EpubDirectEmbeddedInteractionPolicy()

        policy.onNativeTouchStarted()
        assertTrue(policy.onBridgeState(7, true))
        assertFalse(policy.onBridgeState(7, false))
        assertTrue(policy.onBridgeState(7, true))
    }
}
