package io.legado.app.model

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.help.book.addType
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.removeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the flag that separates a book being sampled from one the reader added.
 *
 * Deleting on exit is predicated on [BookType.notShelf], so a book that loses the flag
 * stops being removable and silently stays on the shelf.
 */
class ShelfStateTest {

    private fun book(type: Int = BookType.text) = Book(
        bookUrl = "https://example.test/book/1",
        name = "示例书名",
        author = "示例作者",
        origin = "https://example.test",
        type = type
    )

    @Test
    fun notShelfSurvivesBookTypeReassignment() {
        // WebBook re-derives the source type on every info/toc fetch. That must not
        // disturb the shelf flag, which lives outside BookType.allBookType.
        val target = book(BookType.text or BookType.notShelf)
        target.removeType(BookType.allBookType)
        target.addType(BookType.image)
        assertTrue("re-deriving the source type dropped notShelf", target.isNotShelf)
    }

    @Test
    fun allBookTypeExcludesNotShelf() {
        assertEquals(0, BookType.allBookType and BookType.notShelf)
    }

    @Test
    fun inheritNotShelfStateFromCopiesStoredState() {
        val temporary = book(BookType.text or BookType.notShelf)
        val shelved = book(BookType.text)

        // A replacement book starts with no shelf state of its own; it must adopt the
        // stored row's, or a change of source turns a sampled book into a shelf book.
        assertTrue(book().inheritNotShelfStateFrom(temporary).isNotShelf)
        assertFalse(book(BookType.text or BookType.notShelf).inheritNotShelfStateFrom(shelved).isNotShelf)
    }

    @Test
    fun inheritNotShelfStateFromLeavesStateAloneWhenNoStoredRow() {
        // Nothing is known about a book with no stored row, so neither state is invented.
        assertTrue(book(BookType.text or BookType.notShelf).inheritNotShelfStateFrom(null).isNotShelf)
        assertFalse(book(BookType.text).inheritNotShelfStateFrom(null).isNotShelf)
    }

    @Test
    fun resolveStoredBookshelfStateTreatsMissingRowAsNotOnShelf() {
        assertFalse(resolveStoredBookshelfState(null))
        assertFalse(resolveStoredBookshelfState(book(BookType.text or BookType.notShelf)))
        assertTrue(resolveStoredBookshelfState(book(BookType.text)))
    }
}
