package io.legado.app.ui.book.read

internal object ReadBookStartupPolicy {

    enum class ContentLoader {
        DIRECT_EPUB,
        STANDARD
    }

    data class Decision(
        val contentLoader: ContentLoader,
        val notifyContentDuringReset: Boolean
    )

    fun decide(isEpub: Boolean, useEpubCore: Boolean, directText: Boolean = false): Decision {
        val loader = if ((isEpub && useEpubCore) || directText) {
            ContentLoader.DIRECT_EPUB
        } else {
            ContentLoader.STANDARD
        }
        return Decision(
            contentLoader = loader,
            notifyContentDuringReset = loader != ContentLoader.DIRECT_EPUB
        )
    }

    fun shouldPrepareDirectContent(statusMessage: String?): Boolean {
        return statusMessage == null
    }
}
