package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.model.localBook.epubcore.archive.EpubArchive
import io.legado.app.model.localBook.epubcore.archive.ZipEpubArchive
import io.legado.app.model.localBook.epubcore.font.EpubFontDeobfuscatingArchive
import io.legado.app.model.localBook.epubcore.pkg.EpubPackageParser
import io.legado.app.model.localBook.epubcore.toc.EpubTocParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubDirectZipResourceIntegrationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `linked css imports images and embedded fonts survive case mismatched archive paths`() {
        val chapter = """
            <html><head><link rel="stylesheet" href="../styles/main.css"/></head>
            <body><div class="page"><img src="../images/cover.png"/></div></body></html>
        """.trimIndent()
        val mainCss = """
            @import "nested/theme.css";
            @font-face { font-family: BookBody; src: url("../fonts/body.woff2") format("woff2"); }
            .page { position: absolute; inset: 0; background-image: url("../images/paper.png"); }
        """.trimIndent()
        val entries = linkedMapOf(
            "OPS/Text/Chapter.XHTML" to chapter.toByteArray(),
            "OPS/Styles/Main.CSS" to mainCss.toByteArray(),
            "OPS/Styles/Nested/Theme.CSS" to ".page{font-family:BookBody}".toByteArray(),
            "OPS/Fonts/Body.WOFF2" to byteArrayOf(1, 2, 3, 4),
            "OPS/Images/Cover.PNG" to byteArrayOf(5, 6, 7),
            "OPS/Images/Paper.PNG" to byteArrayOf(8, 9)
        )
        val file = temporaryFolder.newFile("complex-layout.epub")
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }

        ZipEpubArchive(file).use { archive ->
            val source = archive.readText("ops/text/chapter.xhtml")
            val css = EpubDirectPublisherCss.collectForClassification(
                sourceHtml = source,
                chapterHref = "ops/text/chapter.xhtml",
                resourceHost = "b123.epub.local"
            ) { path, maxBytes ->
                archive.readBytes(path, maxBytes)
            }

            assertTrue(css.contains("position: absolute"))
            assertTrue(css.contains("font-family:BookBody"))
            assertTrue(css.contains("https://b123.epub.local/ops/fonts/body.woff2"))
            assertTrue(css.contains("https://b123.epub.local/ops/images/paper.png"))
            assertEquals(
                EpubDirectLayoutMode.PUBLISHER_STYLED,
                EpubDirectContentClassifier.classify(
                    renditionLayout = null,
                    spineProperties = emptySet(),
                    manifestProperties = emptySet(),
                    mediaType = "application/xhtml+xml",
                    sourceHtml = source,
                    publisherCss = css
                )
            )

            val image = EpubDirectResourceFactory.open(
                archive = archive,
                path = "ops/images/cover.png",
                declaredMimeType = "application/octet-stream",
                rangeHeader = null
            )!!
            assertEquals("image/png", image.mimeType)
            assertArrayEquals(byteArrayOf(5, 6, 7), image.stream.use { it.readBytes() })

            val font = EpubDirectResourceFactory.open(
                archive = archive,
                path = "ops/fonts/body.woff2",
                declaredMimeType = "application/octet-stream",
                rangeHeader = null
            )!!
            assertEquals("font/woff2", font.mimeType)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), font.stream.use { it.readBytes() })
        }
    }

    @Test
    fun `package navigation layout and publisher resources survive the complete direct pipeline`() {
        val chapter = """
            <html xmlns="http://www.w3.org/1999/xhtml"
                  xmlns:xlink="http://www.w3.org/1999/xlink"
                  xml:base="../Assets/">
            <head>
              <meta name="viewport" content="width=600,height=800" />
              <link rel="stylesheet" href="styles/main.css" />
              <style>.inline-art { background-image: url('Images/Paper.PNG'); }</style>
            </head>
            <body><div id="panel" class="page inline-art">
              <img src="images/cover.png" alt="cover" />
              <svg viewBox="0 0 600 800">
                <use xlink:href="svg/symbols.svg#ornament" />
                <rect filter="url(Svg/Effects.SVG#shadow)" />
              </svg>
            </div></body></html>
        """.trimIndent()
        val mainCss = """
            @import "Nested/Theme.CSS";
            @font-face { font-family: BookBody; src: url("../Fonts/Book.WOFF2") format("woff2"); }
            .page { position: absolute; inset: 0; background-image: url("../Images/Paper.PNG"); }
        """.trimIndent()
        val themeCss = ".page { font-family: BookBody; transform: translateZ(0); }"
        val fontBytes = byteArrayOf(1, 3, 5, 7)
        val coverBytes = byteArrayOf(2, 4, 6)
        val paperBytes = byteArrayOf(8, 9)
        val symbolsBytes = "<svg><symbol id='ornament'/></svg>".toByteArray()
        val effectsBytes = "<svg><filter id='shadow'/></svg>".toByteArray()
        val entries = linkedMapOf(
            "META-INF/container.xml" to """
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="package/book.opf" /></rootfiles>
                </container>
            """.trimIndent().toByteArray(),
            "Package/Book.OPF" to """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" xml:base="../OEBPS/">
                  <metadata>
                    <meta property="rendition:layout">pre-paginated</meta>
                    <meta property="rendition:viewport">width=600,height=800</meta>
                  </metadata>
                  <manifest xml:base="Content/">
                    <item id="nav" xml:base="../Navigation/" href="toc.xhtml"
                          media-type="application/xhtml+xml" properties="nav" />
                    <item id="page" xml:base="Pages/" href="page01.xhtml"
                          media-type="application/xhtml+xml" />
                    <item id="style" xml:base="Assets/Styles/" href="main.css"
                          media-type="application/octet-stream" />
                    <item id="font" xml:base="Assets/Fonts/" href="book.woff2"
                          media-type="application/octet-stream" />
                  </manifest>
                  <spine><itemref idref="page" /></spine>
                </package>
            """.trimIndent().toByteArray(),
            "OEBPS/Navigation/TOC.XHTML" to """
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <head><base href="../Content/" /></head><body>
                  <nav epub:type="toc"><ol xml:base="Pages/">
                    <li><a href="page01.xhtml#panel">Artwork</a></li>
                  </ol></nav>
                </body></html>
            """.trimIndent().toByteArray(),
            "OEBPS/Content/Pages/Page01.XHTML" to chapter.toByteArray(),
            "OEBPS/Content/Assets/Styles/Main.CSS" to mainCss.toByteArray(),
            "OEBPS/Content/Assets/Styles/Nested/Theme.CSS" to themeCss.toByteArray(),
            "OEBPS/Content/Assets/Fonts/Book.WOFF2" to fontBytes,
            "OEBPS/Content/Assets/Images/Cover.PNG" to coverBytes,
            "OEBPS/Content/Assets/Images/Paper.PNG" to paperBytes,
            "OEBPS/Content/Assets/Svg/Symbols.SVG" to symbolsBytes,
            "OEBPS/Content/Assets/Svg/Effects.SVG" to effectsBytes
        )
        val file = temporaryFolder.newFile("complete-direct-pipeline.epub")
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }

        ZipEpubArchive(file).use { archive ->
            val pkg = EpubPackageParser().parse(archive)
            assertEquals("Package/Book.OPF", pkg.opfPath)
            assertEquals("OEBPS/Navigation/TOC.XHTML", pkg.navHref)
            assertEquals("OEBPS/Content/Pages/Page01.XHTML", pkg.spine.single().href)

            val toc = EpubTocParser().parse(archive, pkg).single()
            assertEquals("Artwork", toc.title)
            assertEquals("OEBPS/Content/Pages/Page01.XHTML#panel", toc.href)

            val page = pkg.spine.single()
            val manifestItem = pkg.manifest.getValue(page.idRef)
            val source = archive.readText(page.href)
            val parsed = EpubDirectParsedSource(source)
            val publisherCss = EpubDirectPublisherCss.collectForClassification(
                document = checkNotNull(parsed.document()),
                chapterHref = page.href,
                resourceHost = "complete.epub.local"
            ) { path, maxBytes ->
                archive.readBytes(path, maxBytes)
            }
            assertTrue(publisherCss.contains("font-family: BookBody"))
            assertTrue(publisherCss.contains("https://complete.epub.local/OEBPS/Content/Assets/Fonts/Book.WOFF2"))
            assertTrue(publisherCss.contains("https://complete.epub.local/OEBPS/Content/Assets/Images/Paper.PNG"))

            val profile = EpubDirectContentClassifier.analyze(
                renditionLayout = page.rendition.layout,
                spineProperties = page.properties,
                manifestProperties = manifestItem.properties,
                mediaType = manifestItem.mediaType,
                parsedSource = parsed,
                packageViewportWidth = page.rendition.viewportWidth,
                packageViewportHeight = page.rendition.viewportHeight,
                publisherCss = publisherCss
            )
            assertEquals(EpubDirectLayoutMode.FIXED, profile.layoutMode)
            assertEquals(600f, profile.viewportWidth)
            assertEquals(800f, profile.viewportHeight)

            val prepared = EpubDirectDocumentBuilder.prepareDocument(
                sourceHtml = source,
                chapterHref = page.href,
                css = "html{overflow:hidden}",
                resourceHost = "complete.epub.local",
                sourceDocument = parsed.document()
            )
            assertTrue(prepared.html.contains("https://complete.epub.local/OEBPS/Content/Assets/styles/main.css"))
            assertTrue(prepared.html.contains("https://complete.epub.local/OEBPS/Content/Assets/images/cover.png"))
            assertTrue(prepared.html.contains("https://complete.epub.local/OEBPS/Content/Assets/svg/symbols.svg#ornament"))
            assertTrue(prepared.html.contains("https://complete.epub.local/OEBPS/Content/Assets/Svg/Effects.SVG#shadow"))
            assertTrue(prepared.html.contains(".inline-art"))
            assertTrue(prepared.html.contains("background-image"))
            assertTrue(prepared.html.contains("https://complete.epub.local/OEBPS/Content/Assets/Images/Paper.PNG"))

            assertResource(
                archive = archive,
                path = "oebps/content/assets/styles/main.css",
                declaredMimeType = "application/octet-stream",
                expectedMimeType = "text/css",
                expectedEncoding = null,
                expectedBytes = mainCss.toByteArray()
            )
            assertResource(archive, "oebps/content/assets/styles/nested/theme.css", null, "text/css", null, themeCss.toByteArray())
            assertResource(archive, "oebps/content/assets/fonts/book.woff2", "application/octet-stream", "font/woff2", null, fontBytes)
            assertResource(archive, "oebps/content/assets/images/cover.png", null, "image/png", null, coverBytes)
            assertResource(archive, "oebps/content/assets/svg/symbols.svg", null, "image/svg+xml", "UTF-8", symbolsBytes)
            assertResource(archive, "oebps/content/assets/svg/effects.svg", null, "image/svg+xml", "UTF-8", effectsBytes)
        }
    }

    @Test
    fun `obfuscated embedded fonts are decoded through the direct resource pipeline`() {
        val identifier = "urn:uuid:01234567-89ab-cdef-0123-456789abcdef"
        val idpfPlain = ByteArray(1280) { index -> ((index * 29 + 17) and 0xff).toByte() }
        val adobePlain = ByteArray(1280) { index -> ((index * 13 + 5) and 0xff).toByte() }
        val idpfKey = MessageDigest.getInstance("SHA-1")
            .digest(identifier.toByteArray(Charsets.UTF_8))
        val adobeKey = hexBytes("0123456789abcdef0123456789abcdef")
        val entries = linkedMapOf(
            "META-INF/container.xml" to """
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OPS/package.opf" /></rootfiles>
                </container>
            """.trimIndent().toByteArray(),
            "OPS/package.opf" to """
                <package xmlns="http://www.idpf.org/2007/opf"
                         xmlns:dc="http://purl.org/dc/elements/1.1/"
                         version="3.0" unique-identifier="pub-id">
                  <metadata><dc:identifier id="pub-id">$identifier</dc:identifier></metadata>
                  <manifest>
                    <item id="chapter" href="Text/%E7%AC%AC%201%E7%AB%A0.xhtml"
                          media-type="application/xhtml+xml" />
                    <item id="style" href="Styles/book.css" media-type="text/css" />
                    <item id="idpf-font" href="Fonts/%E6%AD%A3%E6%96%87%20IDPF.otf"
                          media-type="application/vnd.ms-opentype" />
                    <item id="adobe-font" href="Fonts/Adobe.ttf"
                          media-type="application/x-font-ttf" />
                  </manifest>
                  <spine><itemref idref="chapter" /></spine>
                </package>
            """.trimIndent().toByteArray(),
            "META-INF/encryption.xml" to """
                <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
                            xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
                  <enc:EncryptedData>
                    <enc:EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding" />
                    <enc:CipherData>
                      <enc:CipherReference URI="OPS/Fonts/%E6%AD%A3%E6%96%87%20IDPF.otf" />
                    </enc:CipherData>
                  </enc:EncryptedData>
                  <enc:EncryptedData>
                    <enc:EncryptionMethod Algorithm="http://ns.adobe.com/pdf/enc#RC" />
                    <enc:CipherData><enc:CipherReference URI="OPS/Fonts/Adobe.ttf" /></enc:CipherData>
                  </enc:EncryptedData>
                </encryption>
            """.trimIndent().toByteArray(),
            "OPS/Text/第 1章.xhtml" to """
                <html><head><base href="../"/><link rel="stylesheet" href="Styles/book.css"/></head>
                <body><p>Embedded font</p></body></html>
            """.trimIndent().toByteArray(),
            "OPS/Styles/book.css" to """
                @font-face { font-family: IDPFBody; src: url('../Fonts/正文 IDPF.otf'); }
                @font-face { font-family: AdobeBody; src: url('../Fonts/Adobe.ttf'); }
                body { font-family: IDPFBody, AdobeBody; }
            """.trimIndent().toByteArray(),
            "OPS/Fonts/正文 IDPF.otf" to xorPrefix(idpfPlain, idpfKey, 1040),
            "OPS/Fonts/Adobe.ttf" to xorPrefix(adobePlain, adobeKey, 1024)
        )
        val file = temporaryFolder.newFile("obfuscated-direct-resources.epub")
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }

        val rawArchive = ZipEpubArchive(file)
        val pkg = EpubPackageParser().parse(rawArchive)
        assertEquals(identifier, pkg.metadata.identifier)
        assertEquals("OPS/Text/第 1章.xhtml", pkg.spine.single().href)
        EpubFontDeobfuscatingArchive.wrap(rawArchive, pkg.metadata.identifier).use { archive ->
            val source = archive.readText(pkg.spine.single().href)
            val prepared = EpubDirectDocumentBuilder.prepareDocument(
                sourceHtml = source,
                chapterHref = pkg.spine.single().href,
                css = "body{margin:0}",
                resourceHost = "fonts.epub.local",
                sourceDocument = EpubDirectParsedSource(source).document()
            )
            assertTrue(prepared.html.contains("<base href=\"../\"/>"))
            assertTrue(prepared.html.contains("href=\"Styles/book.css\""))
            assertFalse(prepared.html.contains("href=\"https://fonts.epub.local"))
            assertFalse(prepared.html.contains("src=\"https://fonts.epub.local"))

            assertResource(
                archive,
                "ops/fonts/正文 idpf.otf",
                "application/octet-stream",
                "font/otf",
                null,
                idpfPlain
            )
            assertResource(
                archive,
                "OPS/Fonts/Adobe.ttf",
                "application/octet-stream",
                "font/ttf",
                null,
                adobePlain
            )
        }
    }

    private fun assertResource(
        archive: EpubArchive,
        path: String,
        declaredMimeType: String?,
        expectedMimeType: String,
        expectedEncoding: String?,
        expectedBytes: ByteArray
    ) {
        val resource = checkNotNull(
            EpubDirectResourceFactory.open(
                archive = archive,
                path = path,
                declaredMimeType = declaredMimeType,
                rangeHeader = null
            )
        )
        assertEquals(200, resource.statusCode)
        assertEquals(expectedMimeType, resource.mimeType)
        assertEquals(expectedEncoding, resource.encoding)
        assertArrayEquals(expectedBytes, resource.stream.use { it.readBytes() })
    }

    private fun xorPrefix(source: ByteArray, key: ByteArray, length: Int): ByteArray {
        return source.copyOf().also { output ->
            repeat(minOf(length, output.size)) { index ->
                output[index] = (output[index].toInt() xor key[index % key.size].toInt()).toByte()
            }
        }
    }

    private fun hexBytes(value: String): ByteArray {
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
