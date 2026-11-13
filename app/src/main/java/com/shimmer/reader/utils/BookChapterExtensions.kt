package com.shimmer.reader.utils

import com.shimmer.reader.data.entities.BookChapter

fun BookChapter.internString() {
    title = title.intern()
    bookUrl = bookUrl.intern()
}
