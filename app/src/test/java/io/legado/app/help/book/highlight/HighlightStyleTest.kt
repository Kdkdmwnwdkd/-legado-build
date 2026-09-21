package io.legado.app.help.book.highlight

import org.junit.Assert.*
import org.junit.Test

class HighlightStyleTest {
    private val palette = HighlightStyle.Palette(text = "#123456", accent = "#654321", background = "#fafafa")
    private fun style(rule: HighlightRule) = HighlightStyle.declarations(rule, palette) { "https://book.epub.local/highlight-asset/" + it }

    @Test fun basicStylesAndNightColorsStayReadable() {
        assertEquals("#654321", style(HighlightRule())["color"])
        assertEquals("double", style(HighlightRule(styleType = "doubleLine"))["text-decoration-style"])
        assertEquals("#ffffff", style(HighlightRule(styleType = "background"))["color"])
        assertNotEquals(HighlightStyle.color("teal", palette), HighlightStyle.color("teal", palette.copy(night = true)))
    }

    @Test fun substitutesReaderPaletteAndMissingFont() {
        val values = style(HighlightRule(styleCssText =
            "color:{primaryColor};border-left:4px solid {accentColor};background-color:{backgroundColor};font-family:\"reeden-font:ABC\";"))
        assertEquals("#123456", values["color"])
        assertEquals("4px solid #654321", values["border-left"])
        assertEquals("#fafafa", values["background-color"])
        assertEquals("inherit", values["font-family"])
    }

    @Test fun advancedStyleDoesNotAddUnrequestedBasicBackground() {
        val values = style(HighlightRule(styleType = "background", styleCssText = "font-size:1.3em;"))
        assertFalse(values.containsKey("background-color"))
        assertEquals("1.3em", values["font-size"])
    }

    @Test fun dropsInjectedStylesUrlsScriptsAndHiddenText() {
        val values = style(HighlightRule(styleCssText =
            "color:expression(alert(1));background-image:url(https://tracking.example/a);position:fixed;display:none;content:'fake';font-family:x}body{color:red;"))
        assertFalse(values.containsKey("color"))
        assertFalse(values.containsKey("background-image"))
        assertFalse(values.containsKey("position"))
        assertFalse(values.containsKey("display"))
        assertFalse(values.containsKey("font-family"))
        assertFalse(values.containsKey("content"))
    }

    @Test fun usesOnlyManagedBackgroundAssets() {
        val values = style(HighlightRule(asset = "hash", styleCssText = "background-image:url(untrusted);padding-left:16px;"))
        assertEquals("url(\"https://book.epub.local/highlight-asset/hash\")", values["background-image"])
        assertEquals("16px", values["padding-left"])
    }

    @Test fun mapsReedenStretchRectangleToNineSlicePercentages() {
        val values = style(HighlightRule(asset = "hash", imageWidth = 960, imageHeight = 200,
            styleCssText = "reeden-background-nine-slice:480 100 481 101 / 960 200; padding-left:16px;"))
        assertEquals("50.0000% 49.8958% 49.5000% 50.0000% fill", values["border-image-slice"])
        assertEquals("none", values["background-image"])
        assertEquals("auto", values["border-image-width"])
        assertEquals("16px", values["padding-left"])
    }

    @Test fun supportsLegacyNineSliceCustomPropertyAndIgnoresBrokenValues() {
        assertNotNull(style(HighlightRule(asset = "hash", imageWidth = 960, imageHeight = 200,
            styleCssText = "--reeden-background-nine-slice:461.5 97.5 462.5 98.5;"))["border-image-source"])
        assertNull(style(HighlightRule(asset = "hash", imageWidth = 960, imageHeight = 200,
            styleCssText = "reeden-background-nine-slice:960 200 1 0;"))["border-image-source"])
        assertFalse(style(HighlightRule(styleCssText = "padding-left:px;")) .containsKey("padding-left"))
    }

    @Test fun inlineArtworkReservesBothAsymmetricEdgesAndVerticalPadding() {
        val rule = HighlightRule(asset = "lotus", styleCssText =
            "display:inline;padding:4px 16px 12px 8px;padding-left:20px;")
        val layout = HighlightStyle.inlineLayout(rule, palette)!!
        assertEquals("max(16px,20px)", layout.inset)
        assertEquals("calc(max(1lh,1.5em) + max(4px,12px) + max(4px,12px))", layout.lineHeight)
        assertTrue(layout.fallbackLineHeight.contains("1.5em"))
        assertEquals("4px 16px 12px 8px", style(rule)["padding"])
        assertEquals("inline", style(rule)["display"])
    }

    @Test fun artworkLayoutHonorsRequestedLeadingAndLeavesOtherStylesAlone() {
        val rule = HighlightRule(asset = "image", styleCssText = "display:inline;padding:1em;line-height:1.8;")
        assertTrue(HighlightStyle.inlineLayout(rule, palette)!!.lineHeight.startsWith("calc(max(1.8em,1.5em) + "))
        assertNull(HighlightStyle.inlineLayout(rule.copy(asset = null), palette))
        assertNull(HighlightStyle.inlineLayout(rule.copy(styleCssText = "display:block;padding:12px;"), palette))
        assertNull(HighlightStyle.inlineLayout(rule.copy(styleCssText = "display:inline;padding:10%;"), palette))
    }

    @Test fun inlineImagePaintingDoesNotDisableTextJustificationOrCropTheImage() {
        val rule = HighlightRule(asset = "lotus", styleCssText = "display:inline;padding:12px 16px;")
        val css = HighlightStyle.css(rule, palette) { "https://book.epub.local/$it" }
        assertTrue(css.contains("padding:0!important"))
        assertTrue(css.contains("border-image-source:url(\"https://book.epub.local/lotus\")"))
        assertTrue(css.contains("border-image-slice:0 fill!important"))
        assertTrue(css.contains("border-image-outset:12px 16px 12px 16px!important"))
        assertFalse(css.contains("text-align:"))
        val layout = HighlightStyle.inlineLayout(rule.copy(styleCssText = "display:inline;font-size:150%;padding:1em;"), palette)!!
        assertTrue(layout.spaceLeft.contains("1.5em"))
    }
}
