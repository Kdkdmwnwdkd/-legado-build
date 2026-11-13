package com.shimmer.reader.ui.book.read.epub

import com.shimmer.reader.model.localBook.epubcore.direct.EpubDirectChapter
import com.shimmer.reader.model.localBook.epubcore.direct.EpubDirectLayoutMode
import com.shimmer.reader.model.localBook.epubcore.layout.EpubCoreLayoutConfig

/** Keeps the reader theme below ordinary body text without restyling publisher pages. */
internal object EpubReaderBackgroundPolicy {

    fun shouldUseReaderBackground(
        chapter: EpubDirectChapter?,
        config: EpubCoreLayoutConfig?
    ): Boolean {
        val activeChapter = chapter ?: return false
        return shouldUseReaderBackground(
            readerBackgroundImage = config?.readerBackgroundImage == true,
            layoutMode = activeChapter.layoutMode,
            fullPageArtwork = activeChapter.fullPageArtwork,
            implicitSinglePage = activeChapter.implicitSinglePage,
            duokanGallery = activeChapter.duokanGallery,
            publisherPageBackground = activeChapter.publisherPageBackground
        )
    }

    fun shouldUseReaderBackground(
        readerBackgroundImage: Boolean,
        layoutMode: EpubDirectLayoutMode,
        fullPageArtwork: Boolean,
        implicitSinglePage: Boolean,
        duokanGallery: Boolean,
        publisherPageBackground: Boolean
    ): Boolean {
        return readerBackgroundImage &&
            layoutMode == EpubDirectLayoutMode.REFLOWABLE &&
            !fullPageArtwork &&
            !implicitSinglePage &&
            !duokanGallery &&
            !publisherPageBackground
    }
}
