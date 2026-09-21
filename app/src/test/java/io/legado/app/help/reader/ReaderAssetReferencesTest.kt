package io.legado.app.help.reader

import io.legado.app.help.book.highlight.HighlightDocument
import io.legado.app.help.book.highlight.HighlightRule
import io.legado.app.help.book.highlight.HighlightStyle
import io.legado.app.model.localBook.epubcore.template.EpubReaderTemplate
import org.junit.Assert.*
import org.junit.Test

class ReaderAssetReferencesTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @Test fun selectionReplacesAllFontOverridesAndPreservesOtherValues() {
        val css = "color:red;FONT-family:old;font-family:'second';background-image:url('data:image/png;base64,a;b');padding:calc(1em + 2px)"
        val changed = ReaderAssetReferences.withFont(css, a)
        assertEquals(a, ReaderAssetReferences.selectedFont(changed))
        assertFalse(changed.contains("old")); assertFalse(changed.contains("second"))
        assertTrue(changed.contains("background-image:url('data:image/png;base64,a;b')"))
        assertTrue(changed.contains("padding:calc(1em + 2px)"))
        assertEquals(1, Regex("font-family").findAll(changed).count())
    }

    @Test fun followingReadingFontRemovesTheOverrideWithoutChangingColorOrQuotes() {
        assertEquals("color:blue;text-shadow:0 0 1px red", ReaderAssetReferences.withFont(
            "color:blue;font-family:'semi;colon';text-shadow:0 0 1px red", null))
        assertNull(ReaderAssetReferences.selectedFont(ReaderAssetReferences.withFont("", null)))
    }

    @Test fun commentedDeclarationsAndEscapedQuotesDoNotConfuseFontReplacement() {
        val css = "/* font-family:fake; */ color:red; font-family: /*note*/ 'old'; --x:'a\\\';b';border:1px solid blue"
        val replaced = ReaderAssetReferences.withFont(css, b)
        assertEquals(b, ReaderAssetReferences.selectedFont(replaced))
        assertTrue(replaced.contains("--x:'a\\\';b'"))
        assertTrue(replaced.contains("border:1px solid blue"))
    }

    @Test fun assetReferencesDoNotMatchTruncatedIdsOrForeignHosts() {
        val font = ReaderAssetReferences.fontFamily(a)
        val url = ReaderAssetReferences.url(b)
        assertEquals(setOf(a, b), ReaderAssetReferences.ids("font-family:'$font';background:url('$url')"))
        assertTrue(ReaderAssetReferences.ids(font + "x " + url + "/unexpected").isEmpty())
        for (bad in listOf("http://reader-assets.epub.local/$a", "https://reader-assets.epub.local.evil/$a",
            "https://user@reader-assets.epub.local/$a", "https://reader-assets.epub.local:443/$a",
            "https://reader-assets.epub.local/%2e%2e/$a", ReaderAssetReferences.url(a) + "?path=elsewhere")) {
            assertNull(bad, ReaderAssetReferences.idFromUrl(bad))
        }
        assertEquals(a, ReaderAssetReferences.idFromUrl(ReaderAssetReferences.url(a)))
    }

    @Test fun generatedFontFacesHaveOnlyLocalContentAddressedUrls() {
        val css = ReaderAssetReferences.fontCss(ReaderAssetReferences.fontFamily(a))
        assertTrue(css.contains("src:url('${ReaderAssetReferences.url(a)}')"))
        assertThrows(IllegalArgumentException::class.java) { ReaderAssetReferences.fontFamily("../font.ttf") }
    }

    @Test fun choosingFontKeepsSimpleHighlightColorAndExportsAUsableFontFace() {
        val rule = HighlightRule(keyword = "正文", styleCssText = ReaderAssetReferences.withFont("", a))
        val declarations = HighlightStyle.declarations(rule, HighlightStyle.Palette(accent = "#123456")) { "" }
        assertEquals("#123456", declarations["color"])
        assertEquals("'${ReaderAssetReferences.fontFamily(a)}'", declarations["font-family"])
        val html = HighlightDocument.apply("<p>正文</p>", listOf(rule)) { "" }
        assertTrue(html.contains("@font-face")); assertTrue(html.contains(ReaderAssetReferences.url(a)))
    }

    @Test fun templatePicksPreserveAuthorCodeAndReplaceOnlyTheirOwnSection() {
        val original = "/* author */\n[data-reader-page]{padding:1rem}\n.x{content:'/* reader-assets:font */ x /* /reader-assets:font */'}"
        val changed = ReaderTemplateAssetStyle.background(ReaderTemplateAssetStyle.font(original, a), b)
        val replaced = ReaderTemplateAssetStyle.font(changed, b)
        assertTrue(replaced.startsWith(original))
        assertFalse(replaced.contains(ReaderAssetReferences.fontFamily(a)))
        assertEquals(b, ReaderTemplateAssetStyle.selected(replaced, "font"))
        assertEquals(b, ReaderTemplateAssetStyle.selected(replaced, "background"))
        assertFalse(ReaderTemplateAssetStyle.font(replaced, null).contains(ReaderAssetReferences.fontFamily(b)))
    }

    @Test fun disabledRulesAndAllTemplateFieldsStillProtectReferences() {
        val rules = listOf(HighlightRule(name = "图片", asset = a, enabled = false),
            HighlightRule(name = "字体", styleCssText = ReaderAssetReferences.withFont("", a)))
        val template = EpubReaderTemplate(id = "user.asset", name = "页面", firstPageHtml = "<main data-reader-flow></main>",
            otherPageHtml = "<main data-reader-flow></main>", javascript = "const image='${ReaderAssetReferences.url(a)}';")
        assertEquals(listOf("高亮规则 · 图片", "高亮规则 · 字体", "EPUB 页面 · 页面"), ReaderAssetUsage.references(a, rules, listOf(template)))
        assertTrue(ReaderAssetUsage.references(b, rules, listOf(template)).isEmpty())
    }

    @Test fun mixedCaseLocalHostsStillProtectAssetsUsedByRulesAndTemplates() {
        val url = "https://READER-ASSETS.EPUB.LOCAL/$a"
        val rule = HighlightRule(name = "图片", styleCssText = "background-image:url('$url')")
        val template = EpubReaderTemplate(id = "user.asset", name = "页面", firstPageHtml = "<main data-reader-flow></main>",
            otherPageHtml = "<main data-reader-flow></main>", css = "body{background:url('$url')}")
        assertEquals(a, ReaderAssetReferences.idFromUrl(url))
        assertEquals(setOf(a), ReaderAssetReferences.ids(rule.styleCssText))
        assertEquals(listOf("高亮规则 · 图片", "EPUB 页面 · 页面"),
            ReaderAssetUsage.references(a, listOf(rule), listOf(template)))
        assertEquals(a, ReaderAssetReferences.idFromUrl("HTTPS://Reader-Assets.Epub.Local/$a"))
        assertEquals(setOf(a), ReaderAssetReferences.ids("HTTPS://Reader-Assets.Epub.Local/$a"))
        assertTrue(ReaderAssetReferences.ids("$url/another").isEmpty())
        assertNull(ReaderAssetReferences.idFromUrl("https://READER-ASSETS.EPUB.LOCAL/${a.uppercase()}"))
    }
}
