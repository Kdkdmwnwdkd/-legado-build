package io.legado.app.model.localBook.epubcore.layout

data class EpubCorePage(
    val chapterIndex: Int,
    val chapterHref: String,
    val pageIndex: Int,
    val totalPagesInChapter: Int,
    val text: CharSequence = ""
)
