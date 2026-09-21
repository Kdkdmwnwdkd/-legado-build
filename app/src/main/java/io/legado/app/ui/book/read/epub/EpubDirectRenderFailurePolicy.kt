package io.legado.app.ui.book.read.epub

internal object EpubDirectRenderFailurePolicy {

    enum class Action {
        KeepVisibleDocument,
        ShowError
    }

    fun decide(hasVisibleDocument: Boolean): Action {
        if (hasVisibleDocument) return Action.KeepVisibleDocument
        return Action.ShowError
    }
}
