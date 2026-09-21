package io.legado.app.model.localBook.epubcore.facade

import io.legado.app.data.entities.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Test

class EpubChapterIdentityPolicyTest {

    @Test
    fun `url fragment identity wins over stale database index`() {
        val candidates = listOf(
            chapter(index = 4, fragment = "part-a"),
            chapter(index = 5, fragment = "part-b")
        )

        val selected = EpubChapterIdentityPolicy.select(
            candidates = candidates,
            fragmentId = "part-b",
            requestedIndex = 4
        )

        assertEquals(5, selected?.index)
    }

    @Test
    fun `document owner resolves arbitrary fragment before index fallback`() {
        val candidates = listOf(
            chapter(index = 8, fragment = "part-a"),
            chapter(index = 9, fragment = "part-b")
        )

        val selected = EpubChapterIdentityPolicy.select(
            candidates = candidates,
            fragmentId = "middle-of-b",
            requestedIndex = 8,
            ownerIndex = 9
        )

        assertEquals(9, selected?.index)
    }

    @Test
    fun `index remains final fallback when identity is unavailable`() {
        val candidates = listOf(
            chapter(index = 12, fragment = null),
            chapter(index = 13, fragment = null)
        )

        val selected = EpubChapterIdentityPolicy.select(
            candidates = candidates,
            fragmentId = null,
            requestedIndex = 13
        )

        assertEquals(13, selected?.index)
    }

    private fun chapter(index: Int, fragment: String?): BookChapter {
        return BookChapter(
            bookUrl = "book",
            index = index,
            url = "OPS/chapter.xhtml${fragment?.let { "#$it" }.orEmpty()}",
            title = "Chapter $index",
            startFragmentId = fragment
        )
    }
}
