package io.legado.app.model.localBook.epubcore.layout

/**
 * Keeps Native Lottie modes out of the embedded Direct EPUB document contract.
 *
 * The policy does not rewrite the saved reader preference. A Direct EPUB simply
 * treats the advanced entry as unavailable, while ordinary text reading can keep
 * using the same preference after leaving the EPUB.
 */
object EpubReaderChromeModePolicy {

    fun isSupported(
        directEpub: Boolean,
        mode: Int,
        advancedMode: Int
    ): Boolean = !directEpub || mode != advancedMode

    fun <T> selectableModes(
        modes: Map<Int, T>,
        directEpub: Boolean,
        advancedMode: Int
    ): Map<Int, T> {
        if (!directEpub) return modes
        return modes.filterKeys { it != advancedMode }
    }
}
