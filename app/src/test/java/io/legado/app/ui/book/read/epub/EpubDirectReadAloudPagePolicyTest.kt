package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubDirectReadAloudPagePolicyTest {

    @Test
    fun mapsChapterStartAndEndToVisualEdges() {
        assertEquals(0, EpubDirectReadAloudPagePolicy.pageForChapterPosition(0, 100, 8))
        assertEquals(7, EpubDirectReadAloudPagePolicy.pageForChapterPosition(99, 100, 8))
        assertEquals(0, EpubDirectReadAloudPagePolicy.chapterPositionForPage(0, 8, 100))
        assertEquals(99, EpubDirectReadAloudPagePolicy.chapterPositionForPage(7, 8, 100))
    }

    @Test
    fun clampsInvalidCoordinatesWithoutChangingTheOtherDomain() {
        assertEquals(0, EpubDirectReadAloudPagePolicy.pageForChapterPosition(-5, 100, 8))
        assertEquals(7, EpubDirectReadAloudPagePolicy.pageForChapterPosition(500, 100, 8))
        assertEquals(0, EpubDirectReadAloudPagePolicy.chapterPositionForPage(-3, 8, 100))
        assertEquals(99, EpubDirectReadAloudPagePolicy.chapterPositionForPage(99, 8, 100))
    }

    @Test
    fun degenerateDocumentsAlwaysUseTheOnlyPageOrPosition() {
        assertEquals(0, EpubDirectReadAloudPagePolicy.pageForChapterPosition(50, 100, 0))
        assertEquals(0, EpubDirectReadAloudPagePolicy.chapterPositionForPage(2, 1, 100))
        assertEquals(0, EpubDirectReadAloudPagePolicy.chapterPositionForPage(2, 8, 1))
    }
}
