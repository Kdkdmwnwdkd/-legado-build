package io.legado.app.model.localBook.epubcore.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubWebMainDocumentUrlMatcherTest {

    @Test
    fun `matches callback when webview omits synthetic load token`() {
        assertTrue(
            EpubWebMainDocumentUrlMatcher.matches(
                "https://b123.epub.local/OPS/Text/chapter%201.xhtml?__legado_load__=41",
                "https://b123.epub.local/OPS/Text/chapter%201.xhtml"
            )
        )
    }

    @Test
    fun `matches normalized path while ignoring fragment`() {
        assertTrue(
            EpubWebMainDocumentUrlMatcher.matches(
                "https://epub.local/OPS/Text/../Text/chapter%201.xhtml",
                "https://EPUB.local/OPS/Text/chapter%201.xhtml#anchor"
            )
        )
    }

    @Test
    fun `rejects a different load token or chapter`() {
        assertFalse(
            EpubWebMainDocumentUrlMatcher.matches(
                "https://epub.local/OPS/Text/chapter.xhtml?__legado_load__=41",
                "https://epub.local/OPS/Text/chapter.xhtml?__legado_load__=42"
            )
        )
        assertFalse(
            EpubWebMainDocumentUrlMatcher.matches(
                "https://epub.local/OPS/Text/chapter.xhtml?__legado_load__=41",
                "https://epub.local/OPS/Text/other.xhtml"
            )
        )
    }

    @Test
    fun `rejects query parameters for a tokenless layout document`() {
        assertFalse(
            EpubWebMainDocumentUrlMatcher.matches(
                "https://epub.local/OPS/Text/chapter.xhtml",
                "https://epub.local/OPS/Text/chapter.xhtml?cache=1"
            )
        )
    }
}
