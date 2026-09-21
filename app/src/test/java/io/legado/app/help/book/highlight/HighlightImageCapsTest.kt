package io.legado.app.help.book.highlight

import org.junit.Assert.*
import org.junit.Test

class HighlightImageCapsTest {
    @Test fun unslicedInlineArtworkProtectsNarrowEdgesInsteadOfScalingTransparentMarginsWithLineWidth() {
        val rule = HighlightRule(keyword = "文字", asset = "image", imageWidth = 960, imageHeight = 254,
            styleCssText = "display:inline;padding:9px 16px;background-size:100% 100%;")
        val css = HighlightStyle.css(rule, HighlightStyle.Palette()) { "asset.png" }
        assertTrue(css.contains("border-image-slice:49.803150% 12.500000% 49.803150% 12.500000% fill!important"))
        assertTrue(css.contains("border-image-width:auto!important"))
        assertTrue(css.contains("border-image-outset:9px 16px 9px 16px!important"))
        assertTrue(css.contains("padding:0!important"))
    }

    @Test fun retainsExplicitStretchRectanglesAndUnknownImageDimensions() {
        val rule = HighlightRule(keyword = "文字", asset = "image", imageWidth = 100, imageHeight = 20,
            styleCssText = "display:inline;padding:4px 12px;reeden-background-nine-slice:49 9 51 11;")
        val css = HighlightStyle.css(rule, HighlightStyle.Palette()) { "asset.png" }
        assertTrue(css.contains("border-image-slice:45.0000% 49.0000% 45.0000% 49.0000% fill!important"))
        assertTrue(css.contains("border-image-width:auto!important"))
        val unknown = HighlightStyle.css(rule.copy(imageWidth = 0, imageHeight = 0,
            styleCssText = "display:inline;padding:4px 12px;"), HighlightStyle.Palette()) { "asset.png" }
        assertTrue(unknown.contains("border-image-slice:0 fill!important"))
        assertTrue(unknown.contains("border-image-width:0!important"))
    }
}
