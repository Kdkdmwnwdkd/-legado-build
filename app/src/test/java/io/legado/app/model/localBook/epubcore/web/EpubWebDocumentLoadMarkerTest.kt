package io.legado.app.model.localBook.epubcore.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubWebDocumentLoadMarkerTest {

    @Test
    fun `inject keeps publisher base and inserts marker before head close`() {
        val html = "<html><head><base href=\"../assets/\"></head><body>chapter</body></html>"

        val result = EpubWebDocumentLoadMarker.inject(html, 42L)

        assertTrue(result.contains("<base href=\"../assets/\">"))
        assertTrue(result.contains("data-token=\"42\"></head>"))
        assertTrue(result.indexOf("legado-epub-document-load-marker") < result.indexOf("</head>"))
    }

    @Test
    fun `inject replaces self closing head without creating a second head`() {
        val result = EpubWebDocumentLoadMarker.inject("<html><head/><body>chapter</body></html>", 7L)

        assertTrue(result.contains("<head><meta id=\"legado-epub-document-load-marker\" data-token=\"7\"></head>"))
        assertFalse(result.contains("<head/><head>"))
    }

    @Test
    fun `ready script and result are token scoped`() {
        val script = EpubWebDocumentLoadMarker.readyScript(91L)

        assertTrue(script.contains("data-token')==='91'"))
        assertTrue(EpubWebDocumentLoadMarker.isReadyResult(" true "))
        assertFalse(EpubWebDocumentLoadMarker.isReadyResult("\"true\""))
        assertFalse(EpubWebDocumentLoadMarker.isReadyResult(null))
    }
}
