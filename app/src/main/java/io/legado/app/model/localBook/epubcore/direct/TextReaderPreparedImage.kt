package io.legado.app.model.localBook.epubcore.direct

/** Final local pixels and presentation metadata available before the document is shown. */
data class TextReaderPreparedImage(
    val dataUri: String,
    val scale: Float = 1f,
    val isBubble: Boolean = true
)
