package io.legado.app.model.localBook.epubcore.facade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubCoreSpineStructureTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `unlisted spine document stays physical but is hidden from logical toc`() {
        val file = temporaryFolder.newFile("spine-structure.epub")
        ZipOutputStream(file.outputStream()).use { output ->
            put(output, "META-INF/container.xml", """
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OPS/package.opf" /></rootfiles>
                </container>
            """.trimIndent())
            put(output, "OPS/package.opf", """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata><dc:identifier xmlns:dc="http://purl.org/dc/elements/1.1/">id</dc:identifier></metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav" />
                    <item id="a" href="Text/a.xhtml" media-type="application/xhtml+xml" />
                    <item id="extra" href="Text/gallery-extra.xhtml" media-type="application/xhtml+xml" />
                    <item id="b" href="Text/b.xhtml" media-type="application/xhtml+xml" />
                  </manifest>
                  <spine><itemref idref="a" /><itemref idref="extra" /><itemref idref="b" /></spine>
                </package>
            """.trimIndent())
            put(output, "OPS/nav.xhtml", """
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                  <body><nav epub:type="toc"><ol>
                    <li><a href="Text/a.xhtml">Chapter A</a></li>
                    <li><a href="Text/b.xhtml">Chapter B</a></li>
                  </ol></nav></body>
                </html>
            """.trimIndent())
            put(output, "OPS/Text/a.xhtml", "<html><body class=\"a\"><p>A</p></body></html>")
            put(
                output,
                "OPS/Text/gallery-extra.xhtml",
                "<html><head><style>body{background:#123456}</style></head>" +
                    "<body class=\"gallery\"><div>Gallery</div></body></html>"
            )
            put(output, "OPS/Text/b.xhtml", "<html><body class=\"b\"><p>B</p></body></html>")
        }

        val cacheDir = temporaryFolder.newFolder("cache")
        EpubCoreFacade.open(
            file = file,
            bookUrl = "book://structure",
            bookCacheDir = cacheDir,
            bookSignature = "structure-signature"
        ).use { facade ->
            val chapters = facade.chapters()
            assertEquals(3, chapters.size)
            assertEquals("OPS/Text/a.xhtml", chapters[0].url)
            assertEquals("OPS/Text/gallery-extra.xhtml", chapters[1].url)
            assertEquals("OPS/Text/b.xhtml", chapters[2].url)
            assertEquals("Chapter A", chapters[1].title)
            assertTrue(EpubChapterMetadata.isHiddenFromToc(chapters[1]))
            assertFalse(EpubChapterMetadata.isHiddenFromToc(chapters[0]))
            assertFalse(EpubChapterMetadata.isHiddenFromToc(chapters[2]))
            assertEquals(1, facade.adjacentReadableChapterIndex(0, 1))
            assertEquals(2, facade.adjacentLogicalChapterIndex(0, 1))
            assertEquals(0, facade.adjacentLogicalChapterIndex(1, -1))

            val resource = facade.openDirectResource("OPS/Text/gallery-extra.xhtml", null)
            assertNotNull(resource)
            val html = resource!!.stream.use { it.readBytes().toString(Charsets.UTF_8) }
            assertTrue(html.contains("class=\"gallery\""))
            assertTrue(html.contains("background:#123456"))
        }
    }

    @Test
    fun `cover document remains the first readable spine page`() {
        val file = temporaryFolder.newFile("cover-spine.epub")
        ZipOutputStream(file.outputStream()).use { output ->
            put(output, "META-INF/container.xml", """
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OPS/package.opf" /></rootfiles>
                </container>
            """.trimIndent())
            put(output, "OPS/package.opf", """
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                  <metadata><meta name="cover" content="cover-image" /></metadata>
                  <manifest>
                    <item id="cover-image" href="Images/cover.jpg" media-type="image/jpeg" />
                    <item id="cover-page" href="Text/cover.xhtml" media-type="application/xhtml+xml" />
                    <item id="chapter" href="Text/chapter.xhtml" media-type="application/xhtml+xml" />
                  </manifest>
                  <spine><itemref idref="cover-page" /><itemref idref="chapter" /></spine>
                </package>
            """.trimIndent())
            put(output, "OPS/Text/cover.xhtml", """
                <html xmlns="http://www.w3.org/1999/xhtml"><body><img src="../Images/cover.jpg" /></body></html>
            """.trimIndent())
            put(output, "OPS/Text/chapter.xhtml", "<html><body><p>Body</p></body></html>")
            put(output, "OPS/Images/cover.jpg", "cover-bytes")
        }

        EpubCoreFacade.open(
            file = file,
            bookUrl = "book://cover-spine",
            bookCacheDir = temporaryFolder.newFolder("cover-cache"),
            bookSignature = "cover-spine-signature"
        ).use { facade ->
            val chapters = facade.chapters()
            assertEquals(2, chapters.size)
            assertEquals("OPS/Text/cover.xhtml", chapters.first().url)
            assertEquals(1, facade.adjacentReadableChapterIndex(0, 1))
            val cover = facade.coverResource()
            assertNotNull(cover)
            assertEquals("OPS/Images/cover.jpg", cover!!.path)
            assertEquals("image/jpeg", cover.mediaType)
            assertEquals("cover-bytes", cover.bytes.toString(Charsets.UTF_8))
        }
    }

    private fun put(output: ZipOutputStream, path: String, content: String) {
        output.putNextEntry(ZipEntry(path))
        output.write(content.toByteArray(Charsets.UTF_8))
        output.closeEntry()
    }
}
