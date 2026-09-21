package io.legado.app.model.localBook.epubcore.direct

internal object EpubDirectReaderTypographyPolicy {

    data class Input(
        val layoutMode: EpubDirectLayoutMode,
        val fullPageArtwork: Boolean = false,
        val implicitSinglePage: Boolean = false,
        val duokanGallery: Boolean = false,
        val scripted: Boolean = false
    )

    fun isSupported(input: Input): Boolean {
        return input.layoutMode == EpubDirectLayoutMode.REFLOWABLE &&
            !input.fullPageArtwork &&
            !input.implicitSinglePage &&
            !input.duokanGallery &&
            !input.scripted
    }
}
