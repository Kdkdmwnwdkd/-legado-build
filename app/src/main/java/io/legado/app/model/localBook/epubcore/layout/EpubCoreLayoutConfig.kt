package io.legado.app.model.localBook.epubcore.layout

import android.text.Layout
import android.text.TextPaint
import android.graphics.Color
import io.legado.app.model.localBook.epubcore.template.EpubReaderTemplate

data class EpubCoreLayoutConfig(
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val paddingLeftPx: Int = 0,
    val paddingTopPx: Int = 0,
    val paddingRightPx: Int = 0,
    val paddingBottomPx: Int = 0,
    val readerPaddingLeftPx: Int = 0,
    val readerPaddingTopPx: Int = 0,
    val readerPaddingRightPx: Int = 0,
    val readerPaddingBottomPx: Int = 0,
    val readerSafeInsetLeftPx: Int = 0,
    val readerSafeInsetTopPx: Int = 0,
    val readerSafeInsetRightPx: Int = 0,
    val readerSafeInsetBottomPx: Int = 0,
    val paragraphSpacingPx: Float = 16f,
    val paragraphIndentPx: Float = 0f,
    val textPaint: TextPaint,
    val textFontWeight: Int = 400,
    val textFontItalic: Boolean = false,
    val readerFontFamily: String? = null,
    val readerFontUrl: String? = null,
    val readerFontPath: String? = null,
    val readerFontRevision: String? = null,
    val readerFontMimeType: String? = null,
    val readerFontLength: Long? = null,
    val readerFontOverridePublisher: Boolean = false,
    val alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    val textFullJustify: Boolean = false,
    val textBottomJustify: Boolean = true,
    val lineHeightPx: Float = textPaint.textSize,
    val scrollMode: Boolean = false,
    val backgroundColor: Int = Color.WHITE,
    val selectionColor: Int = Color.argb(20, 0, 0, 0),
    val readerBackgroundImage: Boolean = false,
    val readerChrome: EpubReaderChromeConfig = EpubReaderChromeConfig.DISABLED,
    val readerTemplate: EpubReaderTemplate? = null
) {
    val horizontalPaddingPx: Int
        get() = paddingLeftPx + paddingRightPx

    val verticalPaddingPx: Int
        get() = paddingTopPx + paddingBottomPx

    val contentWidthPx: Int
        get() = (pageWidthPx - horizontalPaddingPx).coerceAtLeast(1)

    val contentHeightPx: Int
        get() = (
            pageHeightPx - verticalPaddingPx -
                readerChrome.reservedHeaderHeightPx - readerChrome.reservedFooterHeightPx
            ).coerceAtLeast(1)

    val readerContentPaddingTopPx: Int
        get() = readerPaddingTopPx + readerChrome.reservedHeaderHeightPx

    val readerContentPaddingBottomPx: Int
        get() = readerPaddingBottomPx + readerChrome.reservedFooterHeightPx

    /**
     * Empty for the disabled/default contract so existing chapter keys remain
     * byte-for-byte compatible until chrome geometry is actually in use.
     */
    val readerChromeGeometryKey: String
        get() = readerChrome.takeIf {
            it.reservedHeaderHeightPx > 0 || it.reservedFooterHeightPx > 0
        }?.geometryKey().orEmpty()

    val readerTemplateKey: String = readerTemplate?.contentHash().orEmpty()
}
