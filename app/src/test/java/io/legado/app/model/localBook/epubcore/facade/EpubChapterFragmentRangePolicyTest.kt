package io.legado.app.model.localBook.epubcore.facade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubChapterFragmentRangePolicyTest {

    @Test
    fun `same document uses next toc fragment as slice end`() {
        assertEquals(
            "section-2",
            EpubChapterFragmentRangePolicy.endFragmentId(
                "OPS/chapter.xhtml#section-1",
                "OPS/chapter.xhtml#section-2",
                "section-2"
            )
        )
    }

    @Test
    fun `different document never clips at an unrelated id`() {
        assertNull(
            EpubChapterFragmentRangePolicy.endFragmentId(
                "OPS/chapter-1.xhtml",
                "OPS/chapter-2.xhtml#summary",
                "summary"
            )
        )
    }
}
