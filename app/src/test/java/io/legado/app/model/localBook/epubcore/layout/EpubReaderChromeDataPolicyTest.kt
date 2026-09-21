package io.legado.app.model.localBook.epubcore.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubReaderChromeDataPolicyTest {

    @Test
    fun `classic fields resolve without changing geometry`() {
        val config = EpubReaderChromeConfig(
            enabled = true,
            headerEnabled = true,
            footerEnabled = true,
            headerHeightPx = 40,
            footerHeightPx = 44,
            headerLeft = EpubReaderChromeField.TIME,
            headerRight = EpubReaderChromeField.BATTERY_PERCENTAGE,
            footerLeft = EpubReaderChromeField.CHAPTER_TITLE,
            footerRight = EpubReaderChromeField.PAGE_AND_TOTAL,
            geometryRevision = 12L
        )
        val template = EpubReaderChromeData(
            bookName = "Book",
            timeLabel = "09:30",
            batteryLabel = "88%",
            batteryPercentageLabel = "88%",
            chapterCount = 10,
            contentRevision = 5L
        )

        val result = EpubReaderChromeDataPolicy.resolve(
            config,
            template,
            EpubReaderChromeDataPolicy.Page(
                chapterIndex = 2,
                chapterTitle = "Chapter 3",
                pageIndex = 1,
                pageCount = 4
            )
        )

        assertEquals("09:30", result.headerLeft)
        assertEquals("88%", result.headerRight)
        assertEquals("Chapter 3", result.footerLeft)
        assertEquals("2/4 25.0%", result.footerRight)
        assertEquals("3/10", result.chapterProgressLabel)
        assertFalse(result.chapterFirstPage)
        assertEquals(5L, result.contentRevision)
        assertEquals(config.geometryKey(), config.copy().geometryKey())
    }

    @Test
    fun `first page state and legacy slot mapping are explicit`() {
        val result = EpubReaderChromeDataPolicy.resolve(
            EpubReaderChromeConfig(),
            EpubReaderChromeData(chapterCount = 1),
            EpubReaderChromeDataPolicy.Page(0, "Only", 0, 1)
        )

        assertTrue(result.chapterFirstPage)
        assertEquals("1/1", result.pageLabel)
        assertEquals("100.0%", result.progressLabel)
        assertEquals(
            EpubReaderChromeField.CHAPTER_PROGRESS,
            EpubReaderChromeLegacyFieldPolicy.resolve(11)
        )
        assertEquals(EpubReaderChromeField.NONE, EpubReaderChromeLegacyFieldPolicy.resolve(-1))
    }

    @Test
    fun `dynamic labels change content without changing geometry identity`() {
        val config = EpubReaderChromeConfig(
            enabled = true,
            headerEnabled = true,
            footerEnabled = true,
            headerHeightPx = 40,
            footerHeightPx = 44,
            headerLeft = EpubReaderChromeField.TIME,
            headerRight = EpubReaderChromeField.BATTERY_PERCENTAGE,
            footerRight = EpubReaderChromeField.PAGE_AND_TOTAL,
            geometryRevision = 12L
        )
        val first = EpubReaderChromeDataPolicy.resolve(
            config = config,
            template = EpubReaderChromeData(
                timeLabel = "09:30",
                batteryPercentageLabel = "88%",
                chapterCount = 10,
                contentRevision = 5L
            ),
            page = EpubReaderChromeDataPolicy.Page(2, "Chapter 3", 1, 4)
        )
        val updated = EpubReaderChromeDataPolicy.resolve(
            config = config,
            template = EpubReaderChromeData(
                timeLabel = "09:31",
                batteryPercentageLabel = "87%",
                chapterCount = 10,
                contentRevision = 6L
            ),
            page = EpubReaderChromeDataPolicy.Page(2, "Chapter 3", 2, 4)
        )

        assertEquals("09:30", first.headerLeft)
        assertEquals("09:31", updated.headerLeft)
        assertEquals("88%", first.headerRight)
        assertEquals("87%", updated.headerRight)
        assertEquals("2/4 25.0%", first.footerRight)
        assertEquals("3/4 27.5%", updated.footerRight)
        assertEquals(5L, first.contentRevision)
        assertEquals(6L, updated.contentRevision)
        assertEquals(config.geometryKey(), config.copy().geometryKey())
    }
}
