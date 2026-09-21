package io.legado.app.model.localBook.epubcore.toc

import io.legado.app.model.localBook.epubcore.archive.EpubArchive
import io.legado.app.model.localBook.epubcore.pkg.EpubMetadata
import io.legado.app.model.localBook.epubcore.pkg.EpubPackage
import io.legado.app.model.localBook.epubcore.pkg.EpubRendition
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class EpubTocParserTest {

    @Test
    fun `nav fragments are decoded for DOM lookup`() {
        val archive = MemoryArchive(
            mapOf(
                "OPS/nav.xhtml" to """
                    <html><body><nav epub:type="toc"><ol>
                      <li><a href="text/chapter.xhtml#section%201">Section</a></li>
                    </ol></nav></body></html>
                """.trimIndent().toByteArray()
            )
        )
        val pkg = EpubPackage(
            opfPath = "OPS/package.opf",
            metadata = EpubMetadata(null, null, null, null),
            manifest = emptyMap(),
            spine = emptyList(),
            navHref = "OPS/nav.xhtml",
            ncxHref = null,
            coverHref = null,
            rendition = EpubRendition(),
            pageProgressionDirection = null
        )

        val item = EpubTocParser().parse(archive, pkg).single()

        assertEquals("OPS/text/chapter.xhtml#section%201", item.href)
        assertEquals("section 1", item.fragment)
        assertEquals(8L * 1024L * 1024L, archive.readLimits.getValue("OPS/nav.xhtml"))
    }

    @Test
    fun `nav base and xml base resolve to canonical archive path`() {
        val archive = MemoryArchive(
            mapOf(
                "OPS/Nav/nav.xhtml" to """
                    <html><head><base href="../Content/"/></head><body>
                    <nav epub:type="toc"><ol><li xml:base="Text/">
                      <a href="chapter.xhtml#section%201">Section</a>
                    </li></ol></nav></body></html>
                """.trimIndent().toByteArray(),
                "OPS/Content/Text/Chapter.XHTML" to "<html/>".toByteArray()
            )
        )
        val pkg = EpubPackage(
            opfPath = "OPS/package.opf",
            metadata = EpubMetadata(null, null, null, null),
            manifest = emptyMap(),
            spine = emptyList(),
            navHref = "OPS/Nav/nav.xhtml",
            ncxHref = null,
            coverHref = null,
            rendition = EpubRendition(),
            pageProgressionDirection = null
        )

        val item = EpubTocParser().parse(archive, pkg).single()

        assertEquals("OPS/Content/Text/Chapter.XHTML#section%201", item.href)
        assertEquals("section 1", item.fragment)
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
