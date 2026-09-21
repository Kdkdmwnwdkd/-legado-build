package io.legado.app.model.localBook.epubcore.direct

/** Immutable image metadata owned strongly by its prepared chapter, never by a URL. */
data class TextReaderImageRequest(
    val chapterIndex: Int,
    val chapterUrl: String,
    val image: TextReaderImage,
    val renderRevision: String,
    val managedBubble: Boolean
)
