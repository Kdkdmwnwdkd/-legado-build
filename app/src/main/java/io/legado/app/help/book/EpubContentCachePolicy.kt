package io.legado.app.help.book

import io.legado.app.model.localBook.EpubFile

internal object EpubContentCachePolicy {

    private const val LOCAL_CONTENT_FAILURE_PREFIX = "获取本地书籍内容失败"
    private const val LEGACY_TEXT_WRAPPER = "<usehtml"

    fun isFailurePayload(isEpub: Boolean, content: String): Boolean {
        return isEpub && content.trimStart().startsWith(LOCAL_CONTENT_FAILURE_PREFIX)
    }

    fun needsRendererRefresh(
        isEpub: Boolean,
        adaptSpecialStyle: Boolean,
        useEpubCore: Boolean,
        content: String
    ): Boolean {
        if (!isEpub || !adaptSpecialStyle) return false
        return if (useEpubCore) {
            !content.contains(EpubFile.NATIVE_CONTENT_FLAG) ||
                !content.contains(EpubFile.NATIVE_LAYOUT_FLAG) ||
                !content.contains(EpubFile.NATIVE_CONTENT_VERSION_FLAG)
        } else {
            content.contains(EpubFile.NATIVE_CONTENT_FLAG) ||
                content.trimStart().startsWith(LEGACY_TEXT_WRAPPER, ignoreCase = true)
        }
    }
}
