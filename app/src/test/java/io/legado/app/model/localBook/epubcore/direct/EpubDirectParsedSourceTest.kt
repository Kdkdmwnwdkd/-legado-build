package io.legado.app.model.localBook.epubcore.direct

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EpubDirectParsedSourceTest {

    @Test
    fun parseIsAttemptedOnceAndDocumentIdentityIsStable() {
        var attempts = 0
        val source = EpubDirectParsedSource("<html><body><p id='one'>Text</p></body></html>") {
            attempts++
            Jsoup.parse(it) as Document
        }

        val first = source.document()
        val second = source.document()

        assertEquals(1, attempts)
        assertSame(first, second)
    }
}
