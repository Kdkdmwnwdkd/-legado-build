package com.shimmer.reader.model

import com.shimmer.reader.constant.BookType
import com.shimmer.reader.data.entities.Book
import com.shimmer.reader.help.book.addType
import com.shimmer.reader.help.book.isNotShelf
import com.shimmer.reader.help.book.removeType

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
