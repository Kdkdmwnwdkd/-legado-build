package io.legado.app.model.localBook.epubcore.pkg

import io.legado.app.model.localBook.epubcore.archive.EpubArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class EpubPackageParserTest {

    @Test
    fun `reads rendition and spine properties`() {
        val archive = MemoryArchive(
            mapOf(
                "META-INF/container.xml" to """
                    <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles><rootfile full-path="OPS/package.opf"/></rootfiles>
                    </container>
                """.trimIndent(),
                "OPS/package.opf" to """
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                      <metadata>
                        <meta property="rendition:layout">pre-paginated</meta>
                        <meta property="rendition:orientation">portrait</meta>
                        <meta property="rendition:spread">both</meta>
                        <meta name="original-resolution" content="1200x1600"/>
                      </metadata>
                      <manifest><item id="page" href="page.xhtml" media-type="application/xhtml+xml"/></manifest>
                      <spine page-progression-direction="rtl">
                        <itemref idref="page" properties="rendition:layout-reflowable rendition:orientation-landscape rendition:spread-none page-spread-right"/>
                      </spine>
                    </package>
                """.trimIndent()
            ).mapValues { it.value.toByteArray() }
        )

        val pkg = EpubPackageParser().parse(archive)

        assertEquals("pre-paginated", pkg.renditionLayout)
        assertEquals("portrait", pkg.rendition.orientation)
        assertEquals("both", pkg.rendition.spread)
        assertEquals(1200f, pkg.rendition.viewportWidth)
        assertEquals(1600f, pkg.rendition.viewportHeight)
        assertEquals("rtl", pkg.pageProgressionDirection)
        assertTrue("rendition:layout-reflowable" in pkg.spine.single().properties)
        assertTrue("page-spread-right" in pkg.spine.single().properties)
        assertEquals("reflowable", pkg.spine.single().rendition.layout)
        assertEquals("landscape", pkg.spine.single().rendition.orientation)
        assertEquals("none", pkg.spine.single().rendition.spread)
        assertEquals(1200f, pkg.spine.single().rendition.viewportWidth)
        assertEquals(1600f, pkg.spine.single().rendition.viewportHeight)
        assertEquals(1L * 1024L * 1024L, archive.readLimits.getValue("META-INF/container.xml"))
        assertEquals(8L * 1024L * 1024L, archive.readLimits.getValue("OPS/package.opf"))
    }

    @Test
    fun `normalizes property tokens and ignores invalid rendition values`() {
        val archive = MemoryArchive(
            mapOf(
                "META-INF/container.xml" to """
                    <container><rootfiles><rootfile full-path="package.opf"/></rootfiles></container>
                """.trimIndent(),
                "package.opf" to """
                    <package version="3.0">
                      <metadata>
                        <meta property="RENDITION:LAYOUT">invalid</meta>
                        <meta property="rendition:orientation">sideways</meta>
                        <meta property="rendition:spread">BOTH</meta>
                      </metadata>
                      <manifest>
                        <item id="page" href="page.xhtml" media-type="application/xhtml+xml" properties="NAV COVER-IMAGE"/>
                      </manifest>
                      <spine page-progression-direction="RTL">
                        <itemref idref="page" linear="NO" properties="RENDITION:LAYOUT-PRE-PAGINATED RENDITION:SPREAD-INVALID"/>
                      </spine>
                    </package>
                """.trimIndent(),
                "page.xhtml" to "<html/>"
            ).mapValues { it.value.toByteArray() }
        )

        val pkg = EpubPackageParser().parse(archive)

        assertEquals("page.xhtml", pkg.navHref)
        assertEquals("page.xhtml", pkg.coverHref)
        assertEquals("rtl", pkg.pageProgressionDirection)
        assertEquals(null, pkg.rendition.layout)
        assertEquals("auto", pkg.rendition.orientation)
        assertEquals("both", pkg.rendition.spread)
        assertEquals(false, pkg.spine.single().linear)
        assertEquals("pre-paginated", pkg.spine.single().rendition.layout)
        assertEquals("both", pkg.spine.single().rendition.spread)
    }

    @Test
    fun `resolves inherited xml base and canonicalizes manifest entry case`() {
        val archive = MemoryArchive(
            mapOf(
                "META-INF/container.xml" to """
                    <container><rootfiles><rootfile full-path="OPS/package.opf"/></rootfiles></container>
                """.trimIndent(),
                "OPS/package.opf" to """
                    <package version="3.0" xml:base="../OEBPS/">
                      <metadata/>
                      <manifest xml:base="Content/">
                        <item id="chapter" xml:base="Text/" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                        <item id="style" href="Styles/book.css" media-type="text/css"/>
                      </manifest>
                      <spine><itemref idref="chapter"/></spine>
                    </package>
                """.trimIndent(),
                "OEBPS/Content/Text/Chapter.XHTML" to "<html/>",
                "OEBPS/Content/Styles/Book.CSS" to "body{}"
            ).mapValues { it.value.toByteArray() }
        )

        val pkg = EpubPackageParser().parse(archive)

        assertEquals("OEBPS/Content/Text/Chapter.XHTML", pkg.manifest.getValue("chapter").href)
        assertEquals("OEBPS/Content/Styles/Book.CSS", pkg.manifest.getValue("style").href)
        assertEquals("OEBPS/Content/Text/Chapter.XHTML", pkg.spine.single().href)
    }

    @Test
    fun `uses the package unique identifier instead of the first metadata identifier`() {
        val archive = MemoryArchive(
            mapOf(
                "META-INF/container.xml" to """
                    <container><rootfiles><rootfile full-path="package.opf"/></rootfiles></container>
                """.trimIndent(),
                "package.opf" to """
                    <package xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0"
                             unique-identifier="publication-id">
                      <metadata>
                        <dc:identifier id="catalog-id">9780000000000</dc:identifier>
                        <dc:identifier id="publication-id">urn:uuid:01234567-89ab-cdef-0123-456789abcdef</dc:identifier>
                      </metadata>
                      <manifest><item id="page" href="page.xhtml" media-type="application/xhtml+xml"/></manifest>
                      <spine><itemref idref="page"/></spine>
                    </package>
                """.trimIndent(),
                "page.xhtml" to "<html/>"
            ).mapValues { it.value.toByteArray() }
        )

        val pkg = EpubPackageParser().parse(archive)

        assertEquals("urn:uuid:01234567-89ab-cdef-0123-456789abcdef", pkg.metadata.identifier)
    }

    @Test
    fun `finds hinted cover image when package omits cover metadata`() {
        val archive = MemoryArchive(
            mapOf(
                "META-INF/container.xml" to """
                    <container><rootfiles><rootfile full-path="package.opf"/></rootfiles></container>
                """.trimIndent(),
                "package.opf" to """
                    <package version="3.0">
                      <metadata/>
                      <manifest>
                        <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                        <item id="frontcover" href="Images/front-cover.jpg" media-type="image/jpeg"/>
                        <item id="photo" href="Images/photo.jpg" media-type="image/jpeg"/>
                      </manifest>
                      <spine><itemref idref="chapter"/></spine>
                    </package>
                """.trimIndent(),
                "chapter.xhtml" to "<html/>",
                "Images/front-cover.jpg" to "cover-bytes"
            ).mapValues { it.value.toByteArray() }
        )

        assertEquals("Images/front-cover.jpg", EpubPackageParser().parse(archive).coverHref)
    }

    @Test
    fun `resolves epub3 refined cover declaration by manifest id`() {
        val archive = MemoryArchive(
            mapOf<String, ByteArray>(
                "META-INF/container.xml" to """
                    <container><rootfiles><rootfile full-path="OPS/package.opf"/></rootfiles></container>
                """.trimIndent().toByteArray(),
                "OPS/package.opf" to """
                    <package version="3.0">
                      <metadata>
                        <meta property="cover-image" refines="#CoverImage"/>
                      </metadata>
                      <manifest>
                        <item id="coverimage" href="Images/Cover.JPG" media-type="image/jpeg"/>
                      </manifest>
                      <spine/>
                    </package>
                """.trimIndent().toByteArray(),
                "OPS/Images/Cover.JPG" to byteArrayOf(1, 2, 3)
            )
        )

        assertEquals("OPS/Images/Cover.JPG", EpubPackageParser().parse(archive).coverHref)
    }

    @Test
    fun `resolves epub2 guide cover relative to package document`() {
        val archive = MemoryArchive(
            mapOf(
                "META-INF/container.xml" to """
                    <container><rootfiles><rootfile full-path="OPS/package.opf"/></rootfiles></container>
                """.trimIndent(),
                "OPS/package.opf" to """
                    <package version="2.0">
                      <metadata/>
                      <manifest>
                        <item id="cover-page" href="Text/titlepage.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine/>
                      <guide><reference type="cover" href="Text/titlepage.xhtml#cover"/></guide>
                    </package>
                """.trimIndent(),
                "OPS/Text/titlepage.xhtml" to "<html/>"
            ).mapValues { it.value.toByteArray() }
        )

        assertEquals("OPS/Text/titlepage.xhtml#cover", EpubPackageParser().parse(archive).coverHref)
    }

    private class MemoryArchive(private val entries: Map<String, ByteArray>) : EpubArchive {
        val readLimits = hashMapOf<String, Long>()

        override fun exists(path: String): Boolean = entries.containsKey(path)
        override fun list(): List<String> = entries.keys.toList()
        override fun readBytes(path: String, maxBytes: Long): ByteArray {
            readLimits[path] = maxBytes
            return entries.getValue(path)
        }
        override fun entrySize(path: String): Long? = entries[path]?.size?.toLong()
        override fun openStream(path: String) = ByteArrayInputStream(entries.getValue(path))
        override fun close() = Unit
    }
}
