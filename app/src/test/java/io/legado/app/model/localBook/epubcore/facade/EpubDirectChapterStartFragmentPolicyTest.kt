package io.legado.app.model.localBook.epubcore.facade

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubDirectChapterStartFragmentPolicyTest {

    @Test
    fun `canonical leaf keeps the normalized structural parent range`() {
        assertEquals(
            "part",
            EpubDirectChapterStartFragmentPolicy.resolve(
                requestedUrl = "OPS/chapter.xhtml#section-1",
                resolvedUrl = "OPS/chapter.xhtml#section-1",
                requestedHref = "OPS/chapter.xhtml",
                resolvedHref = "OPS/chapter.xhtml",
                requestedFragment = "section-1",
                normalizedStartFragmentId = "part"
            )
        )
    }

    @Test
    fun `external fragment request overrides the canonical range in the same resource`() {
        assertEquals(
            "note-7",
            EpubDirectChapterStartFragmentPolicy.resolve(
                requestedUrl = "OPS/chapter.xhtml#note-7",
                resolvedUrl = "OPS/chapter.xhtml#section-1",
                requestedHref = "OPS/chapter.xhtml",
                resolvedHref = "OPS/chapter.xhtml",
                requestedFragment = "note-7",
                normalizedStartFragmentId = "part"
            )
        )
    }

    @Test
    fun `fragment from another resource cannot override the resolved range`() {
        assertEquals(
            "part",
            EpubDirectChapterStartFragmentPolicy.resolve(
                requestedUrl = "OPS/other.xhtml#note-7",
                resolvedUrl = "OPS/chapter.xhtml#section-1",
                requestedHref = "OPS/other.xhtml",
                resolvedHref = "OPS/chapter.xhtml",
                requestedFragment = "note-7",
                normalizedStartFragmentId = "part"
            )
        )
    }
}
