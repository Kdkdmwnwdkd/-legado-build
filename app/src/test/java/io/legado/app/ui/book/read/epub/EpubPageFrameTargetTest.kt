package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertNotEquals
import org.junit.Test

class EpubPageFrameTargetTest {

    @Test
    fun `frame cache identity covers chapter page boundary layout and chrome content`() {
        val target = EpubPageFrameTarget(
            sessionGeneration = 1L,
            chapterIndex = 2,
            chapterHref = "chapter.xhtml",
            chapterRevision = 31,
            requestedPageIndex = 4,
            openAtEnd = false,
            layoutSignature = "layout-with-header-40",
            readerChromeContentRevision = 7L
        )

        assertNotEquals(target.cacheKey, target.copy(sessionGeneration = 2L).cacheKey)
        assertNotEquals(target.cacheKey, target.copy(chapterIndex = 3).cacheKey)
        assertNotEquals(target.cacheKey, target.copy(chapterHref = "next.xhtml").cacheKey)
        assertNotEquals(target.cacheKey, target.copy(chapterRevision = 32).cacheKey)
        assertNotEquals(target.cacheKey, target.copy(requestedPageIndex = 5).cacheKey)
        assertNotEquals(target.cacheKey, target.copy(openAtEnd = true).cacheKey)
        assertNotEquals(
            target.cacheKey,
            target.copy(layoutSignature = "layout-with-header-52").cacheKey
        )
        assertNotEquals(
            target.cacheKey,
            target.copy(readerChromeContentRevision = 8L).cacheKey
        )
    }
}
