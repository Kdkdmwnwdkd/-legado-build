package io.legado.app.ui.book.read.epub

internal object EpubDirectStandbyLifecyclePolicy {

    enum class Action {
        Discard,
        DiscardAndAdvanceGeneration,
        KeepRequiredCandidate
    }

    fun decide(
        standbyToken: Long,
        generation: Long,
        preloading: Boolean,
        hasPreparedChapter: Boolean,
        pendingActivation: Boolean,
        preserveForegroundCandidate: Boolean
    ): Action {
        val foregroundCandidate = standbyToken == generation &&
            !preloading &&
            (hasPreparedChapter || pendingActivation)
        if (!foregroundCandidate) return Action.Discard
        if (preserveForegroundCandidate) return Action.KeepRequiredCandidate
        return Action.DiscardAndAdvanceGeneration
    }
}
