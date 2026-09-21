package io.legado.app.ui.book.read.epub

import io.legado.app.model.localBook.epubcore.direct.EpubDirectLayoutMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubReaderBackgroundPolicyTest {

    @Test
    fun `reader image background is limited to ordinary reflowable body text`() {
        assertTrue(shouldUse(EpubDirectLayoutMode.REFLOWABLE))

        assertFalse(shouldUse(EpubDirectLayoutMode.PUBLISHER_STYLED))
        assertFalse(shouldUse(EpubDirectLayoutMode.FIXED))
        assertFalse(shouldUse(EpubDirectLayoutMode.MEDIA))
        assertFalse(shouldUse(EpubDirectLayoutMode.INTERACTIVE))
    }

    @Test
    fun `publisher artwork signals always keep the publisher canvas`() {
        assertFalse(shouldUse(EpubDirectLayoutMode.REFLOWABLE, fullPageArtwork = true))
        assertFalse(shouldUse(EpubDirectLayoutMode.REFLOWABLE, implicitSinglePage = true))
        assertFalse(shouldUse(EpubDirectLayoutMode.REFLOWABLE, duokanGallery = true))
        assertFalse(shouldUse(EpubDirectLayoutMode.REFLOWABLE, publisherPageBackground = true))
    }

    @Test
    fun `disabled reader image never installs a reader background`() {
        assertFalse(
            EpubReaderBackgroundPolicy.shouldUseReaderBackground(
                readerBackgroundImage = false,
                layoutMode = EpubDirectLayoutMode.REFLOWABLE,
                fullPageArtwork = false,
                implicitSinglePage = false,
                duokanGallery = false,
                publisherPageBackground = false
            )
        )
    }

    private fun shouldUse(
        layoutMode: EpubDirectLayoutMode,
        fullPageArtwork: Boolean = false,
        implicitSinglePage: Boolean = false,
        duokanGallery: Boolean = false,
        publisherPageBackground: Boolean = false
    ): Boolean {
        return EpubReaderBackgroundPolicy.shouldUseReaderBackground(
            readerBackgroundImage = true,
            layoutMode = layoutMode,
            fullPageArtwork = fullPageArtwork,
            implicitSinglePage = implicitSinglePage,
            duokanGallery = duokanGallery,
            publisherPageBackground = publisherPageBackground
        )
    }
}
