package io.legado.app.model

import io.legado.app.constant.BookType
import io.legado.app.constant.SourceType
import io.legado.app.data.entities.Book
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoBookshelfStateTest {

    @Test
    fun missingSearchBookIsNeverTreatedAsShelved() {
        assertFalse(resolveVideoBookshelfState(SourceType.book, null, fallback = true))
    }

    @Test
    fun temporaryPreloadIsNotTreatedAsShelved() {
        val temporary = Book(type = BookType.video or BookType.notShelf)

        assertFalse(resolveVideoBookshelfState(SourceType.book, temporary, fallback = true))
    }

    @Test
    fun storedFormalBookIsTreatedAsShelved() {
        val shelved = Book(type = BookType.video)

        assertTrue(resolveVideoBookshelfState(SourceType.book, shelved, fallback = false))
    }

    @Test
    fun nonBookSourcesKeepTheirRequestedState() {
        assertTrue(resolveVideoBookshelfState(SourceType.rss, null, fallback = true))
        assertFalse(resolveVideoBookshelfState(SourceType.rss, null, fallback = false))
    }
}
