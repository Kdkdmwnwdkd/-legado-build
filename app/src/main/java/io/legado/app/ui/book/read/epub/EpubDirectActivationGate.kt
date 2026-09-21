package io.legado.app.ui.book.read.epub

internal class EpubDirectActivationGate {

    private var pendingToken: Long? = null

    fun begin(token: Long): Boolean {
        if (pendingToken != null) return false
        pendingToken = token
        return true
    }

    fun complete(token: Long): Boolean {
        if (pendingToken != token) return false
        pendingToken = null
        return true
    }

    fun cancel(token: Long): Boolean {
        if (pendingToken != token) return false
        pendingToken = null
        return true
    }

    fun clear() {
        pendingToken = null
    }
}
