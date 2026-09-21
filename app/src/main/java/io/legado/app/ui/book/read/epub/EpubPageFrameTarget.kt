package io.legado.app.ui.book.read.epub

import io.legado.app.model.localBook.epubcore.direct.EpubDirectChapter
import io.legado.app.model.localBook.epubcore.direct.EpubDirectReaderChromePolicy
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import io.legado.app.model.localBook.epubcore.layout.EpubReaderChromeData

internal data class EpubPageFrameTarget(
    val sessionGeneration: Long,
    val chapterIndex: Int,
    val chapterHref: String,
    val chapterRevision: Int,
    val requestedPageIndex: Int,
    val openAtEnd: Boolean,
    val layoutSignature: String,
    val readerChromeContentRevision: Long = 0L
) {

    init {
        require(sessionGeneration > 0L)
        require(chapterIndex >= 0)
        require(chapterHref.isNotBlank())
        require(requestedPageIndex >= 0)
        require(layoutSignature.isNotBlank())
    }

    val cacheKey: String = buildString {
        append(sessionGeneration).append('|')
        append(chapterIndex).append('|').append(chapterHref).append('|')
        append(chapterRevision).append('|').append(requestedPageIndex).append('|')
        append(openAtEnd).append('|').append(layoutSignature).append('|')
        append(readerChromeContentRevision)
    }

    fun accepts(frame: EpubRenderedPageFrame): Boolean {
        if (frame.chapterIndex != chapterIndex || frame.chapterHref != chapterHref ||
            frame.layoutSignature != layoutSignature ||
            frame.readerChromeContentRevision != readerChromeContentRevision
        ) return false
        return if (openAtEnd) {
            frame.pageIndex == frame.pageCount.coerceAtLeast(1) - 1
        } else {
            frame.pageIndex == requestedPageIndex
        }
    }

    companion object {

        fun create(
            sessionGeneration: Long,
            chapter: EpubDirectChapter,
            config: EpubCoreLayoutConfig,
            request: EpubAdjacentPageRequest,
            viewportWidth: Int,
            viewportHeight: Int,
            readerChromeData: EpubReaderChromeData = EpubReaderChromeData()
        ): EpubPageFrameTarget {
            require(chapter.chapterIndex == request.chapterIndex)
            return EpubPageFrameTarget(
                sessionGeneration = sessionGeneration,
                chapterIndex = chapter.chapterIndex,
                chapterHref = chapter.href,
                chapterRevision = chapter.html.hashCode(),
                requestedPageIndex = request.pageIndex,
                openAtEnd = request.openAtEnd,
                layoutSignature = layoutSignature(config, viewportWidth, viewportHeight),
                readerChromeContentRevision = readerChromeContentRevision(
                    chapter,
                    config,
                    readerChromeData
                )
            )
        }

        internal fun readerChromeEnabled(
            chapter: EpubDirectChapter,
            config: EpubCoreLayoutConfig
        ): Boolean = chapter.readerTemplate != null || EpubDirectReaderChromePolicy.resolve(
            requested = config.readerChrome,
            input = EpubDirectReaderChromePolicy.Input(
                layoutMode = chapter.layoutMode,
                fullPageArtwork = chapter.fullPageArtwork,
                implicitSinglePage = chapter.implicitSinglePage,
                duokanGallery = chapter.duokanGallery,
                scripted = chapter.scripted,
                scrollMode = config.scrollMode
            ),
            pageHeightPx = config.pageHeightPx
        ).enabled

        internal fun readerChromeContentRevision(
            chapter: EpubDirectChapter,
            config: EpubCoreLayoutConfig,
            data: EpubReaderChromeData
        ): Long = if (readerChromeEnabled(chapter, config)) data.contentRevision else 0L

        internal fun readerChromeGeometryKey(
            chapter: EpubDirectChapter,
            config: EpubCoreLayoutConfig
        ): String = if (readerChromeEnabled(chapter, config)) {
            config.readerChromeGeometryKey
        } else {
            ""
        }

        internal fun layoutSignature(
            config: EpubCoreLayoutConfig,
            viewportWidth: Int,
            viewportHeight: Int
        ): String = buildString {
            append(viewportWidth).append('x').append(viewportHeight).append('|')
            append(config.pageWidthPx).append('x').append(config.pageHeightPx).append('|')
            append(config.paddingLeftPx).append(',').append(config.paddingTopPx).append(',')
            append(config.paddingRightPx).append(',').append(config.paddingBottomPx).append('|')
            append(config.readerPaddingLeftPx).append(',').append(config.readerPaddingTopPx).append(',')
            append(config.readerPaddingRightPx).append(',').append(config.readerPaddingBottomPx).append('|')
            append(config.readerSafeInsetLeftPx).append(',').append(config.readerSafeInsetTopPx).append(',')
            append(config.readerSafeInsetRightPx).append(',').append(config.readerSafeInsetBottomPx).append('|')
            append(config.paragraphSpacingPx).append('|').append(config.paragraphIndentPx).append('|')
            append(config.textPaint.textSize).append('|').append(config.textPaint.color).append('|')
            append(config.textPaint.letterSpacing).append('|')
            append(config.textFontWeight).append('|').append(config.textFontItalic).append('|')
            append(config.readerFontFamily).append('|').append(config.readerFontUrl).append('|')
            append(config.readerFontRevision).append('|').append(config.readerFontLength).append('|')
            append(config.readerFontOverridePublisher).append('|')
            append(config.alignment).append('|').append(config.textFullJustify).append('|')
            append(config.textBottomJustify).append('|')
            append(config.lineHeightPx).append('|')
            append(config.scrollMode).append('|').append(config.backgroundColor).append('|')
            append(config.readerBackgroundImage)
            config.readerChromeGeometryKey.takeIf { it.isNotEmpty() }?.let {
                append('|').append(it)
            }
            config.readerTemplateKey.takeIf { it.isNotEmpty() }?.let {
                append("|template:").append(it)
            }
        }
    }
}
