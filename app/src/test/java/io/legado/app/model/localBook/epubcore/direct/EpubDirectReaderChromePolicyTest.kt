package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.model.localBook.epubcore.layout.EpubReaderChromeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDirectReaderChromePolicyTest {

    private val request = EpubReaderChromeConfig(
        enabled = true,
        headerEnabled = true,
        footerEnabled = true,
        headerHeightPx = 80,
        footerHeightPx = 60,
        geometryRevision = 7L
    )

    private val reflowable = EpubDirectReaderChromePolicy.Input(
        layoutMode = EpubDirectLayoutMode.REFLOWABLE
    )

    @Test
    fun `ordinary reflowable chapter is eligible and geometry is clamped`() {
        val resolved = EpubDirectReaderChromePolicy.resolve(request, reflowable, pageHeightPx = 100)

        assertTrue(EpubDirectReaderChromePolicy.isSupported(reflowable))
        assertEquals(80, resolved.reservedHeaderHeightPx)
        assertEquals(20, resolved.reservedFooterHeightPx)
        assertEquals(7L, resolved.geometryRevision)
    }

    @Test
    fun `default request stays disabled`() {
        val input = reflowable

        assertFalse(EpubReaderChromeConfig.DISABLED.enabled)
        assertEquals(
            EpubReaderChromeConfig.DISABLED,
            EpubDirectReaderChromePolicy.resolve(EpubReaderChromeConfig.DISABLED, input, 800)
        )
    }

    @Test
    fun `full-page and non-reflowable chapters are rejected`() {
        val rejected = listOf(
            reflowable.copy(fullPageArtwork = true),
            reflowable.copy(implicitSinglePage = true),
            reflowable.copy(duokanGallery = true),
            reflowable.copy(scripted = true),
            reflowable.copy(scrollMode = true),
            reflowable.copy(layoutMode = EpubDirectLayoutMode.PUBLISHER_STYLED),
            reflowable.copy(layoutMode = EpubDirectLayoutMode.FIXED),
            reflowable.copy(layoutMode = EpubDirectLayoutMode.MEDIA),
            reflowable.copy(layoutMode = EpubDirectLayoutMode.INTERACTIVE)
        )

        rejected.forEach { input ->
            assertFalse(EpubDirectReaderChromePolicy.isSupported(input))
            assertEquals(
                EpubReaderChromeConfig.DISABLED,
                EpubDirectReaderChromePolicy.resolve(request, input, 800)
            )
        }
    }

    @Test
    fun `disabled header still reserves no header space`() {
        val resolved = EpubDirectReaderChromePolicy.resolve(
            request.copy(headerEnabled = false),
            reflowable,
            pageHeightPx = 400
        )

        assertEquals(0, resolved.reservedHeaderHeightPx)
        assertEquals(60, resolved.reservedFooterHeightPx)
    }

    @Test
    fun `geometry key excludes dynamic chrome data`() {
        val sameGeometry = request.copy()
        assertEquals(request.geometryKey(), sameGeometry.geometryKey())
        assertEquals(140, request.reservedHeaderHeightPx + request.reservedFooterHeightPx)
    }

    @Test
    fun `disabled chrome contributes no layout identity`() {
        assertEquals("", EpubReaderChromeConfig.DISABLED.geometryKey().takeIf {
            EpubReaderChromeConfig.DISABLED.reservedHeaderHeightPx > 0 ||
                EpubReaderChromeConfig.DISABLED.reservedFooterHeightPx > 0
        } ?: "")
    }
}
