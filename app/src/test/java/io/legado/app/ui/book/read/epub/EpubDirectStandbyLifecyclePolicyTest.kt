package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubDirectStandbyLifecyclePolicyTest {

    @Test
    fun activePreparedCandidateSurvivesTrimWhileOldPageIsVisible() {
        assertEquals(
            EpubDirectStandbyLifecyclePolicy.Action.KeepRequiredCandidate,
            EpubDirectStandbyLifecyclePolicy.decide(
                standbyToken = 7,
                generation = 7,
                preloading = false,
                hasPreparedChapter = true,
                pendingActivation = false,
                preserveForegroundCandidate = true
            )
        )
    }

    @Test
    fun candidateInsideActivationGateSurvivesTrim() {
        assertEquals(
            EpubDirectStandbyLifecyclePolicy.Action.KeepRequiredCandidate,
            EpubDirectStandbyLifecyclePolicy.decide(
                standbyToken = 7,
                generation = 7,
                preloading = false,
                hasPreparedChapter = false,
                pendingActivation = true,
                preserveForegroundCandidate = true
            )
        )
    }

    @Test
    fun onlyForegroundCandidateIsKeptDuringMemoryTrim() {
        assertEquals(
            EpubDirectStandbyLifecyclePolicy.Action.KeepRequiredCandidate,
            EpubDirectStandbyLifecyclePolicy.decide(
                standbyToken = 7,
                generation = 7,
                preloading = false,
                hasPreparedChapter = true,
                pendingActivation = false,
                preserveForegroundCandidate = true
            )
        )
    }

    @Test
    fun hiddenLayerDiscardsOnlyCandidateAndAdvancesGeneration() {
        assertEquals(
            EpubDirectStandbyLifecyclePolicy.Action.DiscardAndAdvanceGeneration,
            EpubDirectStandbyLifecyclePolicy.decide(
                standbyToken = 7,
                generation = 7,
                preloading = false,
                hasPreparedChapter = true,
                pendingActivation = false,
                preserveForegroundCandidate = false
            )
        )
    }

    @Test
    fun staleStandbyDoesNotAdvanceGeneration() {
        assertEquals(
            EpubDirectStandbyLifecyclePolicy.Action.Discard,
            EpubDirectStandbyLifecyclePolicy.decide(
                standbyToken = 6,
                generation = 7,
                preloading = false,
                hasPreparedChapter = true,
                pendingActivation = true,
                preserveForegroundCandidate = true
            )
        )
    }

    @Test
    fun backgroundPreloadDoesNotAdvanceGeneration() {
        assertEquals(
            EpubDirectStandbyLifecyclePolicy.Action.Discard,
            EpubDirectStandbyLifecyclePolicy.decide(
                standbyToken = 7,
                generation = 7,
                preloading = true,
                hasPreparedChapter = true,
                pendingActivation = false,
                preserveForegroundCandidate = true
            )
        )
    }

    @Test
    fun idleCurrentGenerationStandbyDoesNotAdvanceGeneration() {
        assertEquals(
            EpubDirectStandbyLifecyclePolicy.Action.Discard,
            EpubDirectStandbyLifecyclePolicy.decide(
                standbyToken = 7,
                generation = 7,
                preloading = false,
                hasPreparedChapter = false,
                pendingActivation = false,
                preserveForegroundCandidate = true
            )
        )
    }
}
