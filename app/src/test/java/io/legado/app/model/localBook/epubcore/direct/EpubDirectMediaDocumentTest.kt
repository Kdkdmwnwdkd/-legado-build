package io.legado.app.model.localBook.epubcore.direct

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDirectMediaDocumentTest {

    @Test
    fun `image spine bypasses text fallback`() {
        var fallbackCalled = false

        val html = EpubDirectMediaDocument.read(
            href = "OPS/images/page&1.jpg",
            title = "Page <1>",
            mediaType = "IMAGE/JPEG"
        ) {
            fallbackCalled = true
            "not used"
        }

        assertFalse(fallbackCalled)
        assertTrue(html.contains("<img"))
        assertTrue(html.contains("xmlns=\"${EpubDirectDocumentBuilder.XHTML_NAMESPACE}\""))
        assertTrue(html.contains("page&amp;1.jpg"))
        assertTrue(html.contains("Page &lt;1&gt;"))
    }

    @Test
    fun `xhtml spine uses text fallback`() {
        var fallbackCalled = false

        val html = EpubDirectMediaDocument.read(
            href = "OPS/chapter.xhtml",
            title = "Chapter",
            mediaType = "application/xhtml+xml"
        ) {
            fallbackCalled = true
            "<html>chapter</html>"
        }

        assertTrue(fallbackCalled)
        assertTrue(html.contains("chapter"))
        assertNull(EpubDirectMediaDocument.build("OPS/chapter.xhtml", "Chapter", "application/xhtml+xml"))
    }

    @Test
    fun `video and audio spines expose native controls`() {
        val video = EpubDirectMediaDocument.build("video/movie.mp4", "Movie", "video/mp4")
        val audio = EpubDirectMediaDocument.build("audio/book.mp3", "Book", "audio/mpeg; codecs=mp3")

        assertTrue(video.orEmpty().contains("<video"))
        assertTrue(video.orEmpty().contains("controls=\"controls\""))
        assertTrue(video.orEmpty().contains("preload=\"metadata\""))
        assertTrue(audio.orEmpty().contains("<audio"))
        assertTrue(audio.orEmpty().contains("controls=\"controls\""))
        assertTrue(audio.orEmpty().contains("preload=\"metadata\""))
    }
}
