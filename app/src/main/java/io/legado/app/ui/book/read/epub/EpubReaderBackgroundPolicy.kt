package io.legado.app.ui.book.read.epub

import io.legado.app.model.localBook.epubcore.direct.EpubDirectChapter
import io.legado.app.model.localBook.epubcore.direct.EpubDirectLayoutMode
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig

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
