package io.legado.app.ui.book.read.epub

internal object EpubDirectRecoveryPolicy {

    data class Decision(
        val retry: Boolean,
        val requireBlankCurrent: Boolean
    )

    fun decide(
        wasCurrent: Boolean,
        wasPending: Boolean,
        hadVisibleDocument: Boolean,
        destroyed: Boolean
    ): Decision {
        if (destroyed) return Decision(retry = false, requireBlankCurrent = true)
        return when {
            wasCurrent -> Decision(retry = true, requireBlankCurrent = true)
            wasPending -> Decision(retry = true, requireBlankCurrent = !hadVisibleDocument)
            else -> Decision(retry = false, requireBlankCurrent = true)
        }
    }
}
