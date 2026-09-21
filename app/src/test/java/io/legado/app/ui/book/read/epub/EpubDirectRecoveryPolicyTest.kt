package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubDirectRecoveryPolicyTest {

    @Test
    fun currentCrashRequiresBlankReplacementRecovery() {
        assertEquals(
            EpubDirectRecoveryPolicy.Decision(retry = true, requireBlankCurrent = true),
            EpubDirectRecoveryPolicy.decide(
                wasCurrent = true,
                wasPending = false,
                hadVisibleDocument = true,
                destroyed = false
            )
        )
    }

    @Test
    fun candidateCrashRetriesBehindVisibleCommittedPage() {
        assertEquals(
            EpubDirectRecoveryPolicy.Decision(retry = true, requireBlankCurrent = false),
            EpubDirectRecoveryPolicy.decide(
                wasCurrent = false,
                wasPending = true,
                hadVisibleDocument = true,
                destroyed = false
            )
        )
    }

    @Test
    fun initialCandidateCrashRequiresBlankRecovery() {
        assertEquals(
            EpubDirectRecoveryPolicy.Decision(retry = true, requireBlankCurrent = true),
            EpubDirectRecoveryPolicy.decide(
                wasCurrent = false,
                wasPending = true,
                hadVisibleDocument = false,
                destroyed = false
            )
        )
    }
}
