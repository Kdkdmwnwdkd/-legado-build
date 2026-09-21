package io.legado.app.model.localBook.epubcore.direct

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDirectReaderTypographyPolicyTest {

    @Test
    fun `only ordinary reflowable documents accept reader typography`() {
        val ordinary = EpubDirectReaderTypographyPolicy.Input(EpubDirectLayoutMode.REFLOWABLE)
        assertTrue(EpubDirectReaderTypographyPolicy.isSupported(ordinary))

        assertFalse(EpubDirectReaderTypographyPolicy.isSupported(ordinary.copy(fullPageArtwork = true)))
        assertFalse(EpubDirectReaderTypographyPolicy.isSupported(ordinary.copy(implicitSinglePage = true)))
        assertFalse(EpubDirectReaderTypographyPolicy.isSupported(ordinary.copy(duokanGallery = true)))
        assertFalse(EpubDirectReaderTypographyPolicy.isSupported(ordinary.copy(scripted = true)))
        assertFalse(
            EpubDirectReaderTypographyPolicy.isSupported(
                ordinary.copy(layoutMode = EpubDirectLayoutMode.PUBLISHER_STYLED)
            )
        )
        assertFalse(
            EpubDirectReaderTypographyPolicy.isSupported(
                ordinary.copy(layoutMode = EpubDirectLayoutMode.FIXED)
            )
        )
    }
}
