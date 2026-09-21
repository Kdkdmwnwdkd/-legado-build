package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubDirectLayoutProgressPolicyTest {

    @Test
    fun preservesProgressWhenPageCountChanges() {
        assertEquals(0, EpubDirectLayoutProgressPolicy.remapPageIndex(0, 10, 20))
        assertEquals(11, EpubDirectLayoutProgressPolicy.remapPageIndex(5, 10, 20))
        assertEquals(19, EpubDirectLayoutProgressPolicy.remapPageIndex(9, 10, 20))
        assertEquals(2, EpubDirectLayoutProgressPolicy.remapPageIndex(5, 20, 10))
    }

    @Test
    fun handlesSinglePageAndInvalidCounts() {
        assertEquals(0, EpubDirectLayoutProgressPolicy.remapPageIndex(3, 1, 20))
        assertEquals(0, EpubDirectLayoutProgressPolicy.remapPageIndex(3, 20, 1))
        assertEquals(0, EpubDirectLayoutProgressPolicy.remapPageIndex(-2, 0, 0))
    }
}
