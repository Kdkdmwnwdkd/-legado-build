package io.legado.app.ui.book.read.epub

import io.legado.app.model.localBook.epubcore.archive.EpubPath

internal object EpubTocNavigationPolicy {

    data class Selection(
        val chapterIndex: Int,
        val fragmentId: String?
    )

    fun canOpenParent(isEpub: Boolean, isVolume: Boolean, chapterUrl: String): Boolean {
        return isEpub && isVolume && chapterUrl.isNotBlank() && !chapterUrl.startsWith("skip:")
    }

    fun targetFragmentId(chapterUrl: String, startFragmentId: String?): String? {
        return startFragmentId?.takeIf { it.isNotBlank() }
            ?: EpubPath.decodedFragment(chapterUrl)
    }

    fun selection(
        chapterIndex: Int,
        chapterUrl: String,
        startFragmentId: String?
    ): Selection {
        return Selection(
            chapterIndex = chapterIndex,
            fragmentId = targetFragmentId(chapterUrl, startFragmentId)
        )
    }
}
