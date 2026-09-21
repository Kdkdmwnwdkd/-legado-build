package io.legado.app.ui.book

/** Prevents repeated back events from creating competing add-to-shelf decisions. */
internal class ShelfExitRequestGate {

    private var pending = false

    fun tryBegin(): Boolean {
        if (pending) return false
        pending = true
        return true
    }

    fun cancel() {
        pending = false
    }
}
