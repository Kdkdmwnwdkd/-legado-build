package io.legado.app.model.localBook.epubcore.archive

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubPathTest {

    @Test
    fun resolveNormalizesRelativeSegmentsAndPreservesFragment() {
        assertEquals(
            "OEBPS/images/cover image.jpg#preview",
            EpubPath.resolve("OEBPS/text/chapter.xhtml", "../images/cover%20image.jpg?size=large#preview")
        )
    }

    @Test
    fun resolveTreatsLeadingSlashAsArchiveRoot() {
        assertEquals(
            "images/cover.jpg",
            EpubPath.resolve("OEBPS/text/chapter.xhtml", "/images/cover.jpg")
        )
    }

    @Test
    fun resolveTreatsTrailingSlashBaseAsDirectory() {
        assertEquals(
            "OEBPS/assets/images/cover.png",
            EpubPath.resolve("OEBPS/assets/", "images/cover.png")
        )
    }

    @Test
    fun resolveDoesNotRewriteExternalUrls() {
        assertEquals(
            "https://example.com/book.css#theme",
            EpubPath.resolve("OEBPS/text/chapter.xhtml", "https://example.com/book.css#theme")
        )
        assertEquals(
            "//cdn.example.com/cover.jpg",
            EpubPath.resolve("OEBPS/text/chapter.xhtml", "//cdn.example.com/cover.jpg")
        )
    }

    @Test
    fun normalizeCannotEscapeArchiveRoot() {
        assertEquals("META-INF/container.xml", EpubPath.normalize("../../META-INF/container.xml"))
    }

    @Test
    fun encodeFragmentNormalizesExistingEscapesWithoutDoubleEncoding() {
        assertEquals("section%201", EpubPath.encodeFragment("section%201"))
        assertEquals("section%201", EpubPath.encodeFragment("section 1"))
        assertEquals("a%2Bb", EpubPath.encodeFragment("a+b"))
        assertEquals("%E7%AB%A0%E8%8A%82", EpubPath.encodeFragment("章节"))
    }

    @Test
    fun encodePathSegmentPreservesEscapesAndLiteralPlus() {
        assertEquals("chapter%201.xhtml", EpubPath.encodePathSegment("chapter 1.xhtml"))
        assertEquals("chapter%201.xhtml", EpubPath.encodePathSegment("chapter%201.xhtml"))
        assertEquals("a%2Bb.xhtml", EpubPath.encodePathSegment("a+b.xhtml"))
        assertEquals("%E7%AB%A0%E8%8A%82.xhtml", EpubPath.encodePathSegment("\u7ae0\u8282.xhtml"))
    }

    @Test
    fun decodedFragmentReturnsDomIdentifierWithoutTreatingPlusAsSpace() {
        assertEquals("section 1", EpubPath.decodedFragment("chapter.xhtml#section%201"))
        assertEquals("a+b", EpubPath.decodedFragment("chapter.xhtml#a+b"))
    }
}
