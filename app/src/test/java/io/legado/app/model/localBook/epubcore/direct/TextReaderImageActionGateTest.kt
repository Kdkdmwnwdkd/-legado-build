package io.legado.app.model.localBook.epubcore.direct

import org.junit.Assert.*
import org.junit.Test

class TextReaderImageActionGateTest {
    private val action = TextReaderImageAction("image.png,{\"click\":\"source()\"}", "source()")
    private val images = TextReaderSourceImages("https://source/", true, mapOf("image-0" to action))
    private fun chapter() = EpubDirectChapter(
        chapterIndex = 3, href = "text/3/hash.html", title = "Chapter", baseUrl = "https://text.epub.local/text/3/hash.html",
        html = "", plainText = "text", startFragmentId = null, endFragmentId = null,
        layoutMode = EpubDirectLayoutMode.REFLOWABLE, viewportWidth = null, viewportHeight = null,
        publisherOrientation = "auto", publisherSpread = "auto", publisherFullscreen = false,
        fullPageArtwork = false, implicitSinglePage = false, duokanGallery = false, scripted = false,
        pageProgressionDirection = null, sourceChapterUrl = "chapter-3", sourceImages = images
    )
    private fun event(sequence: Long = 1) = TextReaderImageActionGate.Event(10, 2, 8, "image-0", sequence)

    @Test fun `stale page layout token and hidden view events cannot consume a valid action`() {
        val gate = TextReaderImageActionGate()
        val chapter = chapter()
        for (event in listOf(event().copy(generation = 9), event().copy(page = 1),
            event().copy(revision = 7), event().copy(imageId = "unknown"), event().copy(sequence = 0))) {
            assertNull(gate.accept(chapter, 10, 2, 8, event, true, 1000))
        }
        assertNull(gate.accept(chapter, 10, 2, 8, event(), false, 1000))
        assertSame(action, gate.accept(chapter, 10, 2, 8, event(), true, 1000))
    }

    @Test fun `replays and rapid duplicates are rejected even after a delay`() {
        val gate = TextReaderImageActionGate()
        val chapter = chapter()
        assertSame(action, gate.accept(chapter, 10, 2, 8, event(), true, 1000))
        assertNull(gate.accept(chapter, 10, 2, 8, event(2), true, 1100))
        assertNull(gate.accept(chapter, 10, 2, 8, event(2), true, 2000))
        assertSame(action, gate.accept(chapter, 10, 2, 8, event(3), true, 2000))
        assertNull(gate.accept(chapter, 10, 2, 8, event(), true, 3000))
    }

    @Test fun `promoted document starts its own action sequence without accepting old tokens`() {
        val gate = TextReaderImageActionGate()
        val first = chapter()
        assertSame(action, gate.accept(first, 10, 2, 8, event(40), true, 1000))
        val second = first.copy(chapterIndex = 4, sourceChapterUrl = "chapter-4", href = "text/4/hash.html")
        assertNull(gate.accept(second, 11, 2, 8, event(41), true, 2000))
        assertSame(action, gate.accept(second, 11, 2, 8, event().copy(generation = 11), true, 2000))
    }

    @Test fun `publisher EPUB and paragraph namespace cannot enter source executor`() {
        val gate = TextReaderImageActionGate()
        val chapter = chapter()
        assertNull(gate.accept(chapter.copy(sourceChapterUrl = null), 10, 2, 8, event(), true, 1000))
        assertNull(gate.accept(chapter.copy(sourceImages = null), 10, 2, 8, event(), true, 1000))
        assertNull(gate.accept(chapter.copy(sourceImages = images.copy(sourceKey = null)), 10, 2, 8, event(), true, 1000))
        for (click in listOf("rule:1:bad()", " paragraphRule:bad()", "")) {
            val rejected = chapter.copy(sourceImages = images.copy(actions = mapOf("image-0" to action.copy(click = click))))
            assertNull(gate.accept(rejected, 10, 2, 8, event(), true, 1000))
        }
    }

    @Test fun `image preferences preserve preview disable compatibility and double click behavior`() {
        assertEquals("epub", TextReaderImageClickPolicy.mode("1", null))
        assertFalse(TextReaderImageClickPolicy.allowsAction(null, null))
        for (preference in listOf(null, "0", "2", "4")) assertTrue(TextReaderImageClickPolicy.allowsAction(preference, images))
        for (preference in listOf("1", "3")) assertFalse(TextReaderImageClickPolicy.allowsAction(preference, images))
        val local = images.copy(onlineText = false, sourceKey = null)
        assertEquals("3", TextReaderImageClickPolicy.mode("2", local))
        assertEquals("1", TextReaderImageClickPolicy.mode("1", local))
        assertFalse(TextReaderImageClickPolicy.allowsAction("4", local))
    }
}
