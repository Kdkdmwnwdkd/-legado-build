package io.legado.app.model.localBook.epubcore.direct

import android.text.Layout
import android.text.TextPaint
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import io.legado.app.model.localBook.epubcore.layout.EpubReaderChromeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDirectDocumentBuilderTest {

    private fun testPaint(): TextPaint = object : TextPaint() {
        override fun getColor(): Int = 0xFF000000.toInt()
        override fun getTextSize(): Float = 16f
        override fun getLetterSpacing(): Float = 0f
    }

    @Test
    fun `readable text keeps heading and paragraph on separate lines`() {
        val chapter = EpubDirectDocumentBuilder.build(
            chapterIndex = 0,
            href = "OPS/chapter.xhtml",
            title = "Chapter",
            sourceHtml = "<html><body><h1>Chapter One</h1><p>First paragraph.</p><p>Second <em>paragraph</em>.</p></body></html>",
            config = EpubCoreLayoutConfig(
                pageWidthPx = 400,
                pageHeightPx = 800,
                textPaint = testPaint(),
                backgroundColor = 0xFFFFFFFF.toInt(),
                selectionColor = 0x14000000
            ),
            density = 1f
        )

        assertEquals("Chapter One\nFirst paragraph.\nSecond paragraph.", chapter.plainText)
    }

    @Test
    fun `reflow columns reserve reader chrome inside the page box`() {
        val config = EpubCoreLayoutConfig(
            pageWidthPx = 400,
            pageHeightPx = 800,
            textPaint = testPaint(),
            backgroundColor = 0xFFFFFFFF.toInt(),
            selectionColor = 0x14000000,
            readerChrome = EpubReaderChromeConfig(
                enabled = true,
                headerEnabled = true,
                footerEnabled = true,
                headerHeightPx = 40,
                footerHeightPx = 32
            )
        )

        val css = EpubDirectDocumentBuilder.readerCss(
            config = config,
            density = 1f,
            layoutMode = EpubDirectLayoutMode.REFLOWABLE,
            viewport = null,
            fullPageArtwork = false,
            implicitSinglePage = false,
            duokanGallery = false,
            readerFontUrl = null
        )

        assertTrue(css.contains("padding:40.000px 0.000px 32.000px 0.000px!important"))
        assertTrue(css.contains("column-width:400.000px"))
        assertTrue(css.contains("max-height:728.000px"))
    }

    @Test
    fun `disabled chrome preserves the existing reflow padding`() {
        val config = EpubCoreLayoutConfig(
            pageWidthPx = 400,
            pageHeightPx = 800,
            readerPaddingTopPx = 12,
            readerPaddingBottomPx = 18,
            textPaint = testPaint(),
            backgroundColor = 0xFFFFFFFF.toInt(),
            selectionColor = 0x14000000
        )

        val css = EpubDirectDocumentBuilder.readerCss(
            config = config,
            density = 1f,
            layoutMode = EpubDirectLayoutMode.REFLOWABLE,
            viewport = null,
            fullPageArtwork = false,
            implicitSinglePage = false,
            duokanGallery = false,
            readerFontUrl = null
        )

        assertTrue(css.contains("padding:12.000px 0.000px 18.000px 0.000px!important"))
        assertTrue(css.contains("max-height:770.000px"))
    }

    @Test
    fun `display cutout insets pad only ordinary reflow content`() {
        val config = EpubCoreLayoutConfig(
            pageWidthPx = 400,
            pageHeightPx = 800,
            readerPaddingLeftPx = 10,
            readerPaddingTopPx = 12,
            readerPaddingRightPx = 14,
            readerPaddingBottomPx = 16,
            readerSafeInsetLeftPx = 20,
            readerSafeInsetTopPx = 30,
            readerSafeInsetRightPx = 40,
            readerSafeInsetBottomPx = 50,
            textPaint = testPaint(),
            backgroundColor = 0xFFFFFFFF.toInt(),
            selectionColor = 0x14000000
        )

        val reflow = EpubDirectDocumentBuilder.readerCss(
            config = config,
            density = 1f,
            layoutMode = EpubDirectLayoutMode.REFLOWABLE,
            viewport = null,
            fullPageArtwork = false,
            implicitSinglePage = false,
            duokanGallery = false,
            readerFontUrl = null
        )
        val fixed = EpubDirectDocumentBuilder.readerCss(
            config = config,
            density = 1f,
            layoutMode = EpubDirectLayoutMode.FIXED,
            viewport = 400f to 800f,
            fullPageArtwork = true,
            implicitSinglePage = true,
            duokanGallery = false,
            readerFontUrl = null
        )

        assertTrue(reflow.contains("padding:42.000px 54.000px 66.000px 30.000px!important"))
        assertTrue(reflow.contains("column-width:316.000px"))
        assertFalse(fixed.contains("padding:42.000px 54.000px 66.000px 30.000px"))
    }

    @Test
    fun `reflow typography emits exact native metrics without minimum spacing or ch units`() {
        val config = EpubCoreLayoutConfig(
            pageWidthPx = 400,
            pageHeightPx = 800,
            paragraphSpacingPx = 0f,
            paragraphIndentPx = 33.5f,
            textPaint = testPaint(),
            textFontWeight = 550,
            textFontItalic = true,
            lineHeightPx = 19.25f,
            backgroundColor = 0xFFFFFFFF.toInt(),
            selectionColor = 0x14000000
        )

        val css = EpubDirectDocumentBuilder.readerCss(
            config = config,
            density = 2f,
            layoutMode = EpubDirectLayoutMode.REFLOWABLE,
            viewport = null,
            fullPageArtwork = false,
            implicitSinglePage = false,
            duokanGallery = false,
            readerFontUrl = null
        )

        assertTrue(css.contains("line-height:9.625px"))
        assertTrue(css.contains("margin-bottom:0.000px"))
        assertTrue(
            css.contains(
                "p[data-legado-reader-paragraph]{line-height:" +
                    "var(--legado-line-grid,var(--legado-line-height-base))!important;" +
                    "text-indent:16.750px!important;"
            )
        )
        assertTrue(css.contains("orphans:1!important;widows:1!important"))
        assertTrue(css.contains("font-weight:550"))
        assertTrue(css.contains("font-style:italic"))
        assertFalse(css.contains("text-indent:2ch"))
    }

    @Test
    fun `zero indent explicitly overrides publisher indentation`() {
        val config = EpubCoreLayoutConfig(
            pageWidthPx = 400,
            pageHeightPx = 800,
            paragraphIndentPx = 0f,
            textPaint = testPaint(),
            backgroundColor = 0xFFFFFFFF.toInt(),
            selectionColor = 0x14000000
        )

        val css = EpubDirectDocumentBuilder.readerCss(
            config = config,
            density = 2f,
            layoutMode = EpubDirectLayoutMode.REFLOWABLE,
            viewport = null,
            fullPageArtwork = false,
            implicitSinglePage = false,
            duokanGallery = false,
            readerFontUrl = null
        )

        assertTrue(css.contains("text-indent:0.000px!important"))
        assertTrue(css.contains("orphans:1!important"))
        assertTrue(css.contains("widows:1!important"))
        assertTrue(css.contains("break-inside:auto!important"))
        assertTrue(css.contains("page-break-inside:auto!important"))
    }

    @Test
    fun `scroll typography keeps indent without fragmentation overrides`() {
        val config = EpubCoreLayoutConfig(
            pageWidthPx = 400,
            pageHeightPx = 800,
            paragraphIndentPx = 24f,
            textPaint = testPaint(),
            scrollMode = true,
            backgroundColor = 0xFFFFFFFF.toInt(),
            selectionColor = 0x14000000
        )

        val css = EpubDirectDocumentBuilder.readerCss(
            config = config,
            density = 2f,
            layoutMode = EpubDirectLayoutMode.REFLOWABLE,
            viewport = null,
            fullPageArtwork = false,
            implicitSinglePage = false,
            duokanGallery = false,
            readerFontUrl = null
        )

        assertTrue(css.contains("text-indent:12.000px!important"))
        assertFalse(css.contains("orphans:1!important"))
        assertFalse(css.contains("widows:1!important"))
        assertFalse(css.contains("break-inside:auto!important"))
    }

    @Test
    fun `unsupported layout modes ignore reader chrome geometry`() {
        val config = EpubCoreLayoutConfig(
            pageWidthPx = 400,
            pageHeightPx = 800,
            textPaint = testPaint(),
            backgroundColor = 0xFFFFFFFF.toInt(),
            selectionColor = 0x14000000,
            readerChrome = EpubReaderChromeConfig(
                enabled = true,
                headerEnabled = true,
                footerEnabled = true,
                headerHeightPx = 100,
                footerHeightPx = 100
            )
        )

        val css = EpubDirectDocumentBuilder.readerCss(
            config = config,
            density = 1f,
            layoutMode = EpubDirectLayoutMode.PUBLISHER_STYLED,
            viewport = null,
            fullPageArtwork = false,
            implicitSinglePage = false,
            duokanGallery = false,
            readerFontUrl = null
        )

        assertFalse(css.contains("padding:100.000px"))
        assertTrue(css.contains("width:400.000px!important"))

    }

    @Test
    fun `publisher background metadata keeps reflow chrome inside the document`() {
        val config = EpubCoreLayoutConfig(
            pageWidthPx = 400,
            pageHeightPx = 800,
            textPaint = testPaint(),
            backgroundColor = 0xFFFFFFFF.toInt(),
            selectionColor = 0x14000000,
            readerChrome = EpubReaderChromeConfig(
                enabled = true,
                headerEnabled = true,
                footerEnabled = true,
                headerHeightPx = 100,
                footerHeightPx = 100
            )
        )
        val chapter = EpubDirectDocumentBuilder.build(
            chapterIndex = 1,
            href = "OPS/chapter.xhtml",
            title = "Chapter",
            sourceHtml = "<html><body><p>Text</p></body></html>",
            config = config,
            density = 1f,
            layoutMode = EpubDirectLayoutMode.REFLOWABLE,
            publisherPageBackground = true
        )

        assertTrue(chapter.publisherPageBackground)
        assertTrue(chapter.html.contains("padding:100.000px 0.000px 100.000px 0.000px!important"))
        assertTrue(chapter.html.contains("max-height:600.000px"))
    }

    @Test
    fun `srcset rewrites relative candidates without splitting data urls`() {
        val source = "data:image/svg+xml,%3Csvg%3E 1x, images/cover.jpg 2x"

        val rewritten = EpubDirectDocumentBuilder.rewriteSrcSet(source) { url ->
            if (url.startsWith("data:")) null else "https://epub.local/$url"
        }

        assertEquals(
            "data:image/svg+xml,%3Csvg%3E 1x, https://epub.local/images/cover.jpg 2x",
            rewritten
        )
    }

    @Test
    fun `srcset preserves commas embedded inside a candidate url`() {
        val source = "images/cover,large.jpg 2x, fallback.jpg 1x"

        val rewritten = EpubDirectDocumentBuilder.rewriteSrcSet(source) { "local:$it" }

        assertEquals("local:images/cover,large.jpg 2x, local:fallback.jpg 1x", rewritten)
    }

    @Test
    fun `css url scanner handles quoted parentheses and escaped closing parentheses`() {
        val source = "a{background:url('images/chapter(1).png')}b{mask:url(icons/close\\).svg)}"

        val rewritten = EpubDirectDocumentBuilder.rewriteCssUrls(source) { "local:$it" }

        assertEquals(
            "a{background:url(\"local:images/chapter(1).png\")}b{mask:url(\"local:icons/close).svg\")}",
            rewritten
        )
    }

    @Test
    fun `css url scanner decodes hexadecimal escapes before rewriting`() {
        val source = "div{background:url(images/cover\\20 art.jpg)}"

        val rewritten = EpubDirectDocumentBuilder.rewriteCssUrls(source) { "local:$it" }

        assertEquals("div{background:url(\"local:images/cover art.jpg\")}", rewritten)
    }

    @Test
    fun `css scanner rewrites quoted imports and url imports`() {
        val source = "@import '../theme/base.css' screen;@import url(\"print.css\") print;"

        val rewritten = EpubDirectDocumentBuilder.rewriteCssUrls(source) { "local:$it" }

        assertEquals(
            "@import 'local:../theme/base.css' screen;@import url(\"local:print.css\") print;",
            rewritten
        )
    }

    @Test
    fun `malformed css url is left unchanged`() {
        val source = "div{background:url('images/cover.jpg'}"

        assertEquals(source, EpubDirectDocumentBuilder.rewriteCssUrls(source) { "local:$it" })
    }

    @Test
    fun `directory base and nested xml base retain directory semantics`() {
        val base = EpubDirectDocumentBuilder.resolveBaseHref("OPS/text/chapter.xhtml", "../assets/")
        val nested = EpubDirectDocumentBuilder.resolveBaseHref(base, "images/")

        assertEquals("OPS/assets/${EpubDirectDocumentBuilder.BASE_SENTINEL}", base)
        assertEquals("OPS/assets/images/${EpubDirectDocumentBuilder.BASE_SENTINEL}", nested)
    }

    @Test
    fun `duokan audio state images follow scoped xml base without rewriting text placeholders`() {
        val source = """
            <html xml:base="../Assets/"><head></head><body>
              <audio placeholder="Images/sleep.png" activestate="Images/playing.png">
                <source src="Audio/chapter.mp3" type="audio/mpeg" />
              </audio>
              <input placeholder="Search text" />
            </body></html>
        """.trimIndent()

        val prepared = EpubDirectDocumentBuilder.prepareDocument(
            sourceHtml = source,
            chapterHref = "OPS/Text/chapter.xhtml",
            css = "",
            resourceHost = EpubDirectSession.HOST,
            sourceDocument = EpubDirectParsedSource(source).document()
        )

        assertTrue(prepared.html.contains("placeholder=\"https://epub.local/OPS/Assets/Images/sleep.png\""))
        assertTrue(prepared.html.contains("activestate=\"https://epub.local/OPS/Assets/Images/playing.png\""))
        assertTrue(prepared.html.contains("src=\"https://epub.local/OPS/Assets/Audio/chapter.mp3\""))
        assertTrue(prepared.html.contains("placeholder=\"Search text\""))
    }

    @Test
    fun `relative base cannot turn an external base back into a local path`() {
        assertEquals(
            "https://cdn.example.com/book/",
            EpubDirectDocumentBuilder.resolveBaseHref(
                "https://cdn.example.com/book/",
                "images/"
            )
        )
    }

    @Test
    fun `root vertical writing uses its physical page direction`() {
        val verticalRl = """
            <html><head><style>body.book { writing-mode: vertical-rl; }</style></head>
            <body class="book">Text</body></html>
        """.trimIndent()
        val verticalLr = "<html><body style='-epub-writing-mode:tb-lr'>Text</body></html>"

        assertEquals("vertical-rl", EpubDirectDocumentBuilder.resolveRootWritingMode(verticalRl))
        assertEquals("rtl", EpubDirectDocumentBuilder.resolvePageLayoutDirection(verticalRl, "ltr"))
        assertEquals("vertical-lr", EpubDirectDocumentBuilder.resolveRootWritingMode(verticalLr))
        assertEquals("ltr", EpubDirectDocumentBuilder.resolvePageLayoutDirection(verticalLr, "rtl"))
    }

    @Test
    fun `local vertical text does not change the chapter page flow`() {
        val html = """
            <html dir="ltr"><head><style>.vertical-title { writing-mode: vertical-rl; }</style></head>
            <body><h1 class="vertical-title">Title</h1><p>Text</p></body></html>
        """.trimIndent()

        assertEquals(null, EpubDirectDocumentBuilder.resolveRootWritingMode(html))
        assertEquals("ltr", EpubDirectDocumentBuilder.resolvePageLayoutDirection(html, "rtl"))
    }

    @Test
    fun `root css direction controls physical layout independently from package progression`() {
        val html = "<html dir='ltr'><head><style>body { direction: rtl; }</style></head><body>Text</body></html>"

        assertEquals("rtl", EpubDirectDocumentBuilder.resolvePageLayoutDirection(html, "ltr"))
    }

    @Test
    fun `publisher styled flow uses exact viewport columns without changing publisher padding`() {
        val css = EpubDirectDocumentBuilder.publisherStyledBodyFlowCss("411.000px", "731.000px")

        assertTrue(css.contains("width:411.000px!important"))
        assertTrue(css.contains("height:731.000px!important"))
        assertTrue(css.contains("column-width:411.000px"))
        assertTrue(css.contains("column-gap:0"))
        assertTrue(css.contains("overflow:visible!important"))
        assertFalse(css.contains("100vw"))
        assertFalse(css.contains("padding"))
    }

    @Test
    fun `reader font is inherited without overriding publisher body font`() {
        val rules = EpubDirectDocumentBuilder.readerFontRules("legado-reader-font", false)

        assertEquals(
            ":where(html){font-family:'legado-reader-font',sans-serif;}",
            rules.inherited
        )
        assertEquals("", rules.publisherOverride)
        assertFalse(rules.inherited.contains("body"))
    }

    @Test
    fun `explicit reader font override remains authoritative`() {
        val rules = EpubDirectDocumentBuilder.readerFontRules("reader's font", true)

        assertTrue(rules.inherited.contains("'reader\\'s font'"))
        assertEquals(
            "body,body *{font-family:'reader\\'s font',sans-serif!important;}",
            rules.publisherOverride
        )
    }

    @Test
    fun `reader font is served from the fixed epub origin`() {
        val revision = "8b7f4b5d"
        val url = EpubDirectDocumentBuilder.readerFontResourceUrl(
            resourceHost = EpubDirectSession.HOST,
            readerFontUrl = "https://epub.local/__legado_reader_font__",
            readerFontRevision = revision
        )

        assertEquals(
            "https://epub.local/__legado_reader_font__?v=$revision",
            url
        )
        assertEquals(
            null,
            EpubDirectDocumentBuilder.readerFontResourceUrl(
                resourceHost = EpubDirectSession.HOST,
                readerFontUrl = null,
                readerFontRevision = revision
            )
        )
        assertEquals(
            null,
            EpubDirectDocumentBuilder.readerFontResourceUrl(
                resourceHost = EpubDirectSession.HOST,
                readerFontUrl = "https://epub.local/__legado_reader_font__",
                readerFontRevision = null
            )
        )
    }

    @Test
    fun `raw preparation injects reader head without serializing publisher xhtml`() {
        val source = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE html>
            <html lang="en"><head>
            <meta name="viewport" content="width=640,height=960" />
            <meta http-equiv="Content-Security-Policy" content="default-src *" />
            <link rel="stylesheet" href="../Styles/book.css" />
            </head><body><svg viewBox="0 0 640 960"><use xlink:href="../Images/art.svg#page" /></svg></body></html>
        """.trimIndent()

        val prepared = EpubDirectDocumentBuilder.prepareRawDocument(
            sourceHtml = source,
            css = "body{column-width:400px}"
        )

        assertTrue(prepared.html.contains("<link rel=\"stylesheet\" href=\"../Styles/book.css\" />"))
        assertTrue(prepared.html.contains("<svg viewBox=\"0 0 640 960\"><use xlink:href=\"../Images/art.svg#page\" /></svg>"))
        assertTrue(prepared.html.contains("<html lang=\"en\"><head>"))
        assertFalse(prepared.html.contains("xmlns=\"${EpubDirectDocumentBuilder.XHTML_NAMESPACE}\""))
        assertEquals(1, Regex("name=\\\"viewport\\\"").findAll(prepared.html).count())
        assertEquals(1, Regex("Content-Security-Policy").findAll(prepared.html).count())
        assertTrue(prepared.html.contains("content=\"default-src *\""))
        assertTrue(prepared.html.contains("<style id=\"legado-epub-reader-style\">body{column-width:400px}</style>"))
        assertTrue(
            prepared.html.indexOf("<link rel=\"stylesheet\"") <
                prepared.html.indexOf("<style id=\"legado-epub-reader-style\"")
        )
    }

    @Test
    fun `prepared chapter text keeps heading and paragraph boundaries`() {
        val source = """
            <html><body><section><h1>Chapter One</h1><p>First paragraph.</p><p>Second <em>paragraph</em>.</p></section></body></html>
        """.trimIndent()

        val prepared = EpubDirectDocumentBuilder.prepareDocument(
            sourceHtml = source,
            chapterHref = "OPS/Text/chapter.xhtml",
            css = "",
            resourceHost = EpubDirectSession.HOST,
            sourceDocument = EpubDirectParsedSource(source).document()
        )

        assertEquals("Chapter One\nFirst paragraph.\nSecond paragraph.", prepared.plainText)
    }

    @Test
    fun `ordinary chapter preserves publisher base and relative resources`() {
        val source = """
            <?xml version="1.0" encoding="utf-8"?>
            <html><head>
            <base href="../Assets/" />
            <link rel="stylesheet" href="Styles/book.css" />
            </head><body>
            <img src="Images/cover.png" />
            </body></html>
        """.trimIndent()

        val prepared = EpubDirectDocumentBuilder.prepareDocument(
            sourceHtml = source,
            chapterHref = "OPS/Text/chapter.xhtml",
            css = "body{color:black}",
            resourceHost = EpubDirectSession.HOST,
            sourceDocument = EpubDirectParsedSource(source).document()
        )

        assertEquals(1, Regex("<base\\b", RegexOption.IGNORE_CASE).findAll(prepared.html).count())
        assertTrue(prepared.html.contains("<base href=\"../Assets/\" />"))
        assertTrue(prepared.html.contains("href=\"Styles/book.css\""))
        assertTrue(prepared.html.contains("src=\"Images/cover.png\""))
        assertFalse(prepared.html.contains("https://epub.local"))
        assertFalse(prepared.html.contains("Content-Security-Policy"))
        assertTrue(
            prepared.html.indexOf("href=\"Styles/book.css\"") <
                prepared.html.indexOf("id=\"legado-epub-reader-style\"")
        )
    }

    @Test
    fun `ordinary chapter without publisher base relies on webview chapter base url`() {
        val source = """
            <html><head><link rel="stylesheet" href="../Styles/book.css"></head>
            <body style="background:url('../Images/paper.png')"><img src="../Images/cover.png"></body></html>
        """.trimIndent()

        val prepared = EpubDirectDocumentBuilder.prepareDocument(
            sourceHtml = source,
            chapterHref = "OPS/Text/chapter.xhtml",
            css = "body{color:black}",
            resourceHost = EpubDirectSession.HOST,
            sourceDocument = EpubDirectParsedSource(source).document()
        )

        assertFalse(Regex("<base\\b", RegexOption.IGNORE_CASE).containsMatchIn(prepared.html))
        assertTrue(prepared.html.contains("href=\"../Styles/book.css\""))
        assertTrue(prepared.html.contains("url('../Images/paper.png')"))
        assertTrue(prepared.html.contains("src=\"../Images/cover.png\""))
        assertFalse(prepared.html.contains("https://epub.local"))
    }

    @Test
    fun `xml base resources are rewritten before xhtml is loaded as html`() {
        val source = """
            <html xml:base="../Assets/"><head>
            <link rel="stylesheet" href="Styles/book.css" />
            <style>.hero{background-image:url('Images/paper.png')}</style>
            </head><body><svg>
              <image href="Images/cover.png" />
              <use xlink:href="Symbols/art.svg#page" />
              <rect filter="url(Filters/effects.svg#blur)" />
            </svg></body></html>
        """.trimIndent()

        val prepared = EpubDirectDocumentBuilder.prepareDocument(
            sourceHtml = source,
            chapterHref = "OPS/Text/chapter.xhtml",
            css = "",
            resourceHost = EpubDirectSession.HOST,
            sourceDocument = EpubDirectParsedSource(source).document()
        )

        assertTrue(prepared.html.contains("https://epub.local/OPS/Assets/Styles/book.css"))
        assertTrue(prepared.html.contains("https://epub.local/OPS/Assets/Images/paper.png"))
        assertTrue(prepared.html.contains("https://epub.local/OPS/Assets/Images/cover.png"))
        assertTrue(prepared.html.contains("https://epub.local/OPS/Assets/Symbols/art.svg#page"))
        assertTrue(prepared.html.contains("url(&quot;https://epub.local/OPS/Assets/Filters/effects.svg#blur&quot;)"))
        assertFalse(Regex("<base\\b", RegexOption.IGNORE_CASE).containsMatchIn(prepared.html))
        assertFalse(prepared.html.contains("Content-Security-Policy"))
    }
}
