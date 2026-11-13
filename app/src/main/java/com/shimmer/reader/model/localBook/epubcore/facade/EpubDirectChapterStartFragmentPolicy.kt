package com.shimmer.reader.model.localBook.epubcore.facade

internal object EpubDirectChapterStartFragmentPolicy {

    fun resolve(
        requestedUrl: String,
        resolvedUrl: String,
        requestedHref: String,
        resolvedHref: String,
        requestedFragment: String?,
        normalizedStartFragmentId: String?
    ): String? {
        return requestedFragment
            ?.takeIf { requestedUrl != resolvedUrl && requestedHref == resolvedHref }
            ?: normalizedStartFragmentId
    }
}
