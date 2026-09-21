package io.legado.app.ui.book.read.epub

internal object EpubDirectInitialFragmentPolicy {

    fun resolve(
        requestedFragmentId: String?,
        chapterStartFragmentId: String?,
        resetPageOffset: Boolean
    ): String? {
        return requestedFragmentId?.takeIf { it.isNotBlank() }
            ?: chapterStartFragmentId?.takeIf { resetPageOffset && it.isNotBlank() }
    }
}
