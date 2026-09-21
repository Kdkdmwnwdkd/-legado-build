package io.legado.app.model.localBook.epubcore.facade

import io.legado.app.data.entities.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubReadableChapterPolicyTest {

    private val chapters = listOf(
        chapter(0, "0.xhtml"),
        chapter(1, "skip:1:section"),
        chapter(2, "skip:2:subsection"),
        chapter(3, "3.xhtml"),
        chapter(4, "4.xhtml")
    )

    @Test
    fun `sequential navigation skips structural toc entries`() {
        assertEquals(3, EpubReadableChapterPolicy.adjacent(chapters, 0, 1))
        assertEquals(0, EpubReadableChapterPolicy.adjacent(chapters, 3, -1))
        assertEquals(4, EpubReadableChapterPolicy.adjacent(chapters, 3, 1))
        assertNull(EpubReadableChapterPolicy.adjacent(chapters, 4, 1))
    }

    @Test
    fun `a structural saved position resolves in the preferred direction`() {
        assertEquals(3, EpubReadableChapterPolicy.resolve(chapters, 1, 1))
        assertEquals(0, EpubReadableChapterPolicy.resolve(chapters, 2, -1))
        assertEquals(3, EpubReadableChapterPolicy.resolve(chapters, 3, -1))
    }

    @Test
    fun `logical navigation skips physical continuation pages`() {
        val withContinuation = chapters.map { chapter ->
            if (chapter.index == 3) {
                chapter.copy(variable = "{\"${EpubChapterMetadata.TocHiddenKey}\":\"true\"}")
            } else {
                chapter
            }
        }

        assertEquals(4, EpubReadableChapterPolicy.adjacentLogical(withContinuation, 0, 1))
        assertEquals(0, EpubReadableChapterPolicy.adjacentLogical(withContinuation, 4, -1))
        assertEquals(4, EpubReadableChapterPolicy.adjacentLogical(withContinuation, 3, 1))
    }

    private fun chapter(index: Int, url: String) = BookChapter(
        bookUrl = "book",
        index = index,
        url = url,
        title = "Chapter $index"
    )
}
