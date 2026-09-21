package io.legado.app.model.localBook.epubcore.direct

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubDirectContentClassifierTest {

    @Test
    fun `semantic pagebreak marker remains reflowable`() {
        assertEquals(
            EpubDirectLayoutMode.REFLOWABLE,
            classify("""
                <html xmlns:epub="http://www.idpf.org/2007/ops">
                <body><p>Readable chapter text.</p><span epub:type="pagebreak">12</span></body>
                </html>
            """.trimIndent())
        )
    }

    @Test
    fun `numeric viewport alone remains reflowable`() {
        assertEquals(
            EpubDirectLayoutMode.REFLOWABLE,
            classify("""
                <html><head><meta name="viewport" content="width=600,height=800"/></head>
                <body><p>${"Readable paragraph. ".repeat(20)}</p></body></html>
            """.trimIndent())
        )
    }

    @Test
    fun `small visual canvas with viewport is fixed layout`() {
        assertEquals(
            EpubDirectLayoutMode.FIXED,
            classify("""
                <html><head><meta name="viewport" content="width=600,height=800"/></head>
                <body><svg width="600" height="800"><rect width="600" height="800"/></svg></body></html>
            """.trimIndent())
        )
    }

    @Test
    fun `single svg viewbox is fixed even without viewport metadata`() {
        assertEquals(
            EpubDirectLayoutMode.FIXED,
            classify("""<html><body><svg viewBox="0 0 600 800"><rect width="600" height="800"/></svg></body></html>""")
        )
    }

    @Test
    fun `image only cover is a fixed single page without viewport metadata`() {
        val profile = analyze(
            """<html><body class="cover"><img src="cover.jpg" alt=""/></body></html>"""
        )

        assertEquals(EpubDirectLayoutMode.FIXED, profile.layoutMode)
        assertEquals(true, profile.fullPageArtwork)
        assertEquals(true, profile.implicitSinglePage)
    }

    @Test
    fun `hidden cover heading does not turn svg artwork into reflowable text`() {
        val profile = analyze(
            """
                <html><body>
                  <h2 style="display: none" title="Cover">Cover</h2>
                  <div><svg viewBox="0 0 5760 7680"><image href="cover.png"/></svg></div>
                </body></html>
            """.trimIndent()
        )

        assertEquals(EpubDirectLayoutMode.FIXED, profile.layoutMode)
        assertEquals(true, profile.fullPageArtwork)
        assertEquals(true, profile.implicitSinglePage)
        assertEquals(5760f, profile.viewportWidth)
        assertEquals(7680f, profile.viewportHeight)
    }

    @Test
    fun `explicit package fixed layout is honored`() {
        assertEquals(
            EpubDirectLayoutMode.FIXED,
            classify("<html><body><p>Page</p></body></html>", renditionLayout = "pre-paginated")
        )
    }

    @Test
    fun `spine reflowable property overrides package fixed layout and canvas heuristic`() {
        assertEquals(
            EpubDirectLayoutMode.REFLOWABLE,
            classify(
                """
                    <html><head><meta name="viewport" content="width=600,height=800"/></head>
                    <body><svg width="600" height="800"></svg></body></html>
                """.trimIndent(),
                renditionLayout = "pre-paginated",
                spineProperties = setOf("rendition:layout-reflowable")
            )
        )
    }

    @Test
    fun `scripted reflowable content remains paginated`() {
        assertEquals(
            EpubDirectLayoutMode.REFLOWABLE,
            classify("<html><body><p>Quiz</p></body></html>", spineProperties = setOf("scripted"))
        )
        assertEquals(
            EpubDirectLayoutMode.REFLOWABLE,
            classify("<html><body><form><input/></form></body></html>")
        )
    }

    @Test
    fun `scripted fixed layout remains an interactive page`() {
        assertEquals(
            EpubDirectLayoutMode.INTERACTIVE,
            classify(
                "<html><body><button>Next</button></body></html>",
                renditionLayout = "pre-paginated",
                spineProperties = setOf("scripted")
            )
        )
    }

    @Test
    fun `ordinary canvas and modern layout css do not force single page mode`() {
        assertEquals(
            EpubDirectLayoutMode.REFLOWABLE,
            classify("""<html><head><style>.row{display:flex}.grid{display:grid}</style></head><body><canvas></canvas><p>Text</p></body></html>""")
        )
    }

    @Test
    fun `decorative transform does not discard reader styling`() {
        assertEquals(
            EpubDirectLayoutMode.REFLOWABLE,
            classify("<html><head><style>.arrow{transform:rotate(90deg)}</style></head><body><p>Text</p><span class='arrow'>></span></body></html>")
        )
    }

    @Test
    fun `direct image audio and video spines are media pages`() {
        listOf("image/jpeg", "audio/mpeg", "video/mp4").forEach { mediaType ->
            assertEquals(
                mediaType,
                EpubDirectLayoutMode.MEDIA,
                classify("", mediaType = mediaType)
            )
        }
    }

    @Test
    fun `compact xhtml audio and video chapters are media pages`() {
        assertEquals(
            EpubDirectLayoutMode.MEDIA,
            classify("<html><body><h1>Track</h1><audio src='track.mp3'></audio></body></html>")
        )
        assertEquals(
            EpubDirectLayoutMode.REFLOWABLE,
            classify("<html><body><video src='clip.mp4'></video><p>${"Long commentary. ".repeat(30)}</p></body></html>")
        )
    }

    @Test
    fun `publisher controlled geometry is publisher styled`() {
        assertEquals(
            EpubDirectLayoutMode.PUBLISHER_STYLED,
            classify("""<html><head><style>.spread{position:absolute;inset:0}</style></head><body><div class="spread">Layout</div></body></html>""")
        )
    }

    @Test
    fun `external publisher geometry participates in classification`() {
        val profile = EpubDirectContentClassifier.analyze(
            renditionLayout = null,
            spineProperties = emptySet(),
            manifestProperties = emptySet(),
            mediaType = "application/xhtml+xml",
            sourceHtml = "<html><body><header class='chapter-header'>Title</header><p>Text</p></body></html>",
            publisherCss = ".chapter-header{position:absolute;top:0;left:0}"
        )

        assertEquals(EpubDirectLayoutMode.PUBLISHER_STYLED, profile.layoutMode)
    }

    @Test
    fun `print only geometry does not change screen layout classification`() {
        val html = """
            <html><head><style>
              @media print { .chapter-header { position:absolute;top:0;left:0 } }
            </style></head><body><header class='chapter-header'>Title</header><p>Text</p></body></html>
        """.trimIndent()

        assertEquals(EpubDirectLayoutMode.REFLOWABLE, classify(html))
        assertEquals(
            EpubDirectLayoutMode.REFLOWABLE,
            EpubDirectContentClassifier.classify(
                renditionLayout = null,
                spineProperties = emptySet(),
                manifestProperties = emptySet(),
                mediaType = "application/xhtml+xml",
                sourceHtml = "<html><body><p>Text</p></body></html>",
                publisherCss = "@media print{.page{position:absolute;inset:0}}"
            )
        )
    }

    @Test
    fun `inactive style elements do not change screen layout classification`() {
        listOf(
            "<style media='print'>.page{position:absolute;inset:0}</style>",
            "<style media='speech'>.page{position:absolute;inset:0}</style>",
            "<style media='tv'>.page{position:absolute;inset:0}</style>",
            "<style disabled='disabled'>.page{position:absolute;inset:0}</style>"
        ).forEach { style ->
            assertEquals(
                style,
                EpubDirectLayoutMode.REFLOWABLE,
                classify("<html><head>$style</head><body><p>Text</p></body></html>")
            )
        }
    }

    @Test
    fun `screen and not print media remain classification inputs`() {
        listOf(
            "@media screen{.page{position:absolute;inset:0}}",
            "@media not print{.page{position:absolute;inset:0}}",
            "@media (min-width: 1px){.page{position:absolute;inset:0}}",
            "<style media='not print'>.page{position:absolute;inset:0}</style>"
        ).forEach { publisherCss ->
            val html = if (publisherCss.startsWith("<style")) {
                "<html><head>$publisherCss</head><body><p>Text</p></body></html>"
            } else {
                "<html><body><p>Text</p></body></html>"
            }
            assertEquals(
                publisherCss,
                EpubDirectLayoutMode.PUBLISHER_STYLED,
                EpubDirectContentClassifier.classify(
                    renditionLayout = null,
                    spineProperties = emptySet(),
                    manifestProperties = emptySet(),
                    mediaType = "application/xhtml+xml",
                    sourceHtml = html,
                    publisherCss = publisherCss.takeUnless { it.startsWith("<style") }.orEmpty()
                )
            )
        }
    }

    @Test
    fun `short semantic notice preserves publisher styling`() {
        assertEquals(
            EpubDirectLayoutMode.PUBLISHER_STYLED,
            classify("""<html><body><section class="copyright-card"><p>Copyright 2026</p><p>All rights reserved.</p></section></body></html>""")
        )
    }

    @Test
    fun `ordinary single wrapper remains reflowable`() {
        val profile = analyze(
            """<html><body><section class="chapter"><p>First paragraph.</p><p>Second paragraph.</p></section></body></html>"""
        )

        assertEquals(EpubDirectLayoutMode.REFLOWABLE, profile.layoutMode)
        assertEquals(false, profile.implicitSinglePage)
    }

    @Test
    fun `viewport title artwork is treated as a single fixed page`() {
        val profile = analyze(
            """
                <html><head><meta name="viewport" content="width=600,height=800"/></head>
                <body class="title-page"><h1>Volume One</h1><img src="cover.jpg"/></body></html>
            """.trimIndent()
        )

        assertEquals(EpubDirectLayoutMode.FIXED, profile.layoutMode)
        assertEquals(true, profile.implicitSinglePage)
    }

    @Test
    fun `background artwork wrapper is an implicit single page`() {
        val profile = analyze(
            """
                <html><head><meta name="viewport" content="width=600,height=800"/></head>
                <body class="cover" style="background-image:url('cover.jpg')">
                  <div class="intro"><svg viewBox="0 0 600 800"></svg></div>
                </body></html>
            """.trimIndent()
        )

        assertEquals(EpubDirectLayoutMode.FIXED, profile.layoutMode)
        assertEquals(true, profile.implicitSinglePage)
    }

    @Test
    fun `publisher page backgrounds are kept separate from ordinary reader backgrounds`() {
        val inline = analyze(
            """<html><body style="background:#111 url('paper.jpg') repeat"><p>Text</p></body></html>"""
        )
        val external = EpubDirectContentClassifier.analyze(
            renditionLayout = null,
            spineProperties = emptySet(),
            manifestProperties = emptySet(),
            mediaType = "application/xhtml+xml",
            sourceHtml = "<html><body><p>Text</p></body></html>",
            publisherCss = ".chapter{background-image:linear-gradient(#fff,#eee)}"
        )
        val ordinary = analyze("<html><body><p>Text</p></body></html>")

        assertEquals(true, inline.publisherPageBackground)
        assertEquals(true, external.publisherPageBackground)
        assertEquals(false, ordinary.publisherPageBackground)
    }

    @Test
    fun `duokan multi cell gallery is interactive`() {
        val html = """<html><body><div class="duokan-image-gallery"><div class="duokan-image-gallery-cell"><img src="1.jpg"/></div><div class="duokan-image-gallery-cell"><img src="2.jpg"/></div></div></body></html>"""
        val profile = EpubDirectContentClassifier.analyze(
            renditionLayout = null,
            spineProperties = emptySet(),
            manifestProperties = emptySet(),
            mediaType = "application/xhtml+xml",
            sourceHtml = html
        )

        assertEquals(EpubDirectLayoutMode.INTERACTIVE, profile.layoutMode)
        assertEquals(true, profile.duokanGallery)
    }

    @Test
    fun `profile extracts svg viewport and artwork flags`() {
        val profile = EpubDirectContentClassifier.analyze(
            renditionLayout = "pre-paginated",
            spineProperties = emptySet(),
            manifestProperties = emptySet(),
            mediaType = "application/xhtml+xml",
            sourceHtml = """<html><body><svg viewBox="0 0 720 1280"><image href="page.jpg"/></svg></body></html>"""
        )

        assertEquals(EpubDirectLayoutMode.FIXED, profile.layoutMode)
        assertEquals(720f, profile.viewportWidth)
        assertEquals(1280f, profile.viewportHeight)
        assertEquals(true, profile.fullPageArtwork)
        assertEquals(true, profile.implicitSinglePage)
        assertEquals(false, profile.duokanGallery)
    }

    private fun classify(
        html: String,
        renditionLayout: String? = null,
        spineProperties: Set<String> = emptySet(),
        manifestProperties: Set<String> = emptySet(),
        mediaType: String? = "application/xhtml+xml"
    ): EpubDirectLayoutMode {
        return EpubDirectContentClassifier.classify(
            renditionLayout = renditionLayout,
            spineProperties = spineProperties,
            manifestProperties = manifestProperties,
            mediaType = mediaType,
            sourceHtml = html
        )
    }

    private fun analyze(html: String): EpubDirectContentClassifier.Profile {
        return EpubDirectContentClassifier.analyze(
            renditionLayout = null,
            spineProperties = emptySet(),
            manifestProperties = emptySet(),
            mediaType = "application/xhtml+xml",
            sourceHtml = html
        )
    }
}
