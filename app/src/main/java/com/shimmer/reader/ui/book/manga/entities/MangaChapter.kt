package com.shimmer.reader.ui.book.manga.entities

import com.shimmer.reader.data.entities.BookChapter

data class MangaChapter(
    val chapter: BookChapter,
    val pages: List<BaseMangaPage>,
    val imageCount: Int
)
