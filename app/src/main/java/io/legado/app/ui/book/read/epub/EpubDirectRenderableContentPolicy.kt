package io.legado.app.ui.book.read.epub

internal object EpubDirectRenderableContentPolicy {

    fun requiresRenderableContent(
        hasText: Boolean,
        singlePage: Boolean,
        fullPageArtwork: Boolean,
        duokanGallery: Boolean
    ): Boolean {
        return hasText || singlePage || fullPageArtwork || duokanGallery
    }

    fun canActivate(requiresRenderableContent: Boolean, hasRenderableContent: Boolean): Boolean {
        return !requiresRenderableContent || hasRenderableContent
    }
}
