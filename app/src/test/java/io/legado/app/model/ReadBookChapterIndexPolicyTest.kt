package io.legado.app.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadBookChapterIndexPolicyTest {

    @Test
    fun rejectsAdjacentIndexesOutsideKnownChapterList() {
        assertFalse(ReadBookChapterIndexPolicy.canLoad(-1, 1485))
        assertFalse(ReadBookChapterIndexPolicy.canLoad(1485, 1485))
        assertTrue(ReadBookChapterIndexPolicy.canLoad(0, 1485))
        assertTrue(ReadBookChapterIndexPolicy.canLoad(1484, 1485))
    }

    @Test
    fun keepsMissingListOnNormalErrorPath() {
        assertFalse(ReadBookChapterIndexPolicy.canLoad(-1, 0))
        assertTrue(ReadBookChapterIndexPolicy.canLoad(0, 0))
    }

    @Test
    fun selectionRequiresAValidKnownIndex() {
        assertFalse(ReadBookChapterIndexPolicy.canSelect(-1, 1485))
        assertFalse(ReadBookChapterIndexPolicy.canSelect(1485, 1485))
        assertTrue(ReadBookChapterIndexPolicy.canSelect(0, 1485))
    }
}
