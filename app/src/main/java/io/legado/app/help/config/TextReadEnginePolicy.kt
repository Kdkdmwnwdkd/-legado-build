package io.legado.app.help.config

/** File format and renderer selection are deliberately independent. */
object TextReadEnginePolicy {
    const val NATIVE = "native"
    const val EPUB = "epub"
    const val DEFAULT = NATIVE

    fun normalize(value: String?): String = if (value == EPUB) EPUB else NATIVE

    fun usesDirect(
        isEpub: Boolean,
        isOrdinaryText: Boolean,
        epubUsesCore: Boolean,
        textEngine: String?
    ): Boolean = if (isEpub) epubUsesCore else isOrdinaryText && normalize(textEngine) == EPUB
}
