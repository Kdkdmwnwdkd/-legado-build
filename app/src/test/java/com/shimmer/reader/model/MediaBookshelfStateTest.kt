package com.shimmer.reader.model

import com.shimmer.reader.constant.BookType
import com.shimmer.reader.data.entities.Book
import com.shimmer.reader.help.book.isNotShelf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaBookshelfStateTest {

    @Test
    fun temporarySourceKeepsReplacementOutOfBookshelf() {
        val source = Book(type = BookType.audio or BookType.notShelf)
        val replacement = Book(type = BookType.image)

        replacement.inheritNotShelfStateFrom(source)

        assertTrue(replacement.isNotShelf)
        assertTrue(replacement.type and BookType.image > 0)
    }

    @Test
    fun formalSourceClearsStaleTemporaryMarker() {
        val source = Book(type = BookType.audio)
        val replacement = Book(type = BookType.image or BookType.notShelf)

        replacement.inheritNotShelfStateFrom(source)

        assertFalse(replacement.isNotShelf)
        assertTrue(replacement.type and BookType.image > 0)
    }

    @Test
    fun missingSourceLeavesReplacementMarkerUnchanged() {
        val replacement = Book(type = BookType.audio or BookType.notShelf)

        replacement.inheritNotShelfStateFrom(null)

        assertTrue(replacement.isNotShelf)
    }

    @Test
    fun storedTemporaryBookOverridesOptimisticLaunchState() {
        val storedBook = Book(type = BookType.audio or BookType.notShelf)

        assertFalse(resolveStoredBookshelfState(storedBook))
    }

    @Test
    fun storedFormalBookIsInBookshelf() {
        val storedBook = Book(type = BookType.audio)

        assertTrue(resolveStoredBookshelfState(storedBook))
    }

    @Test
    fun missingStoredBookIsNeverAssumedToBeInBookshelf() {
        assertFalse(resolveStoredBookshelfState(null))
    }
}
