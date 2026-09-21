package io.legado.app.model

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.help.book.addType
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.removeType

internal fun Book.inheritNotShelfStateFrom(source: Book?): Book {
    when (source?.isNotShelf) {
        true -> addType(BookType.notShelf)
        false -> removeType(BookType.notShelf)
        null -> Unit
    }
    return this
}

internal fun resolveStoredBookshelfState(storedBook: Book?): Boolean {
    return storedBook?.isNotShelf == false
}
