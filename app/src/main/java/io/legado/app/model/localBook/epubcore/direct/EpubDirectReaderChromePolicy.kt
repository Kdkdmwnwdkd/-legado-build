package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.model.localBook.epubcore.layout.EpubReaderChromeConfig

/**
 * Conservative eligibility gate for reader-owned chrome.
 *
 * Only ordinary reflowable chapters are eligible. Publisher backgrounds are
 * kept by the document itself and therefore do not disable transparent,
 * in-document reader chrome. Full-page and interactive content keeps its
 * existing rendering path unchanged.
 */
internal object EpubDirectReaderChromePolicy {

    data class Input(
        val layoutMode: EpubDirectLayoutMode,
        val fullPageArtwork: Boolean = false,
        val implicitSinglePage: Boolean = false,
        val duokanGallery: Boolean = false,
        val scripted: Boolean = false,
        val scrollMode: Boolean = false
    )

    fun isSupported(input: Input): Boolean {
        return input.layoutMode == EpubDirectLayoutMode.REFLOWABLE &&
            !input.fullPageArtwork &&
            !input.implicitSinglePage &&
            !input.duokanGallery &&
            !input.scripted &&
            !input.scrollMode
    }

    fun resolve(
        requested: EpubReaderChromeConfig,
        input: Input,
        pageHeightPx: Int
    ): EpubReaderChromeConfig {
        if (!requested.enabled || !isSupported(input)) return EpubReaderChromeConfig.DISABLED
        return requested.normalizedForPage(pageHeightPx)
    }
}
