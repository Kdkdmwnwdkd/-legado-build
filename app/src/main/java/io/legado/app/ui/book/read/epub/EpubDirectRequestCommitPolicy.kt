package io.legado.app.ui.book.read.epub

internal object EpubDirectRequestCommitPolicy {

    fun ownsActiveNavigation(
        requestSeq: Long,
        currentRequestSeq: Long,
        callbackRequestSeq: Long
    ): Boolean {
        return requestSeq != 0L &&
            requestSeq == currentRequestSeq &&
            requestSeq == callbackRequestSeq
    }

    fun canCommit(
        requestSeq: Long,
        currentRequestSeq: Long,
        requestBookUrl: String,
        currentBookUrl: String?,
        usesCore: Boolean
    ): Boolean {
        return usesCore &&
            requestSeq == currentRequestSeq &&
            requestBookUrl == currentBookUrl
    }
}
