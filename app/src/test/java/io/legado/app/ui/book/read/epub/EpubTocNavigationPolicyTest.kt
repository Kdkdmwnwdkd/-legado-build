package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubTocNavigationPolicyTest {

    @Test
    fun readableEpubParentCanOpenItsOwnChapter() {
        assertTrue(
            EpubTocNavigationPolicy.canOpenParent(
                isEpub = true,
                isVolume = true,
                chapterUrl = "OPS/chapter.xhtml#part-1"
            )
        )
        assertTrue(
            EpubTocNavigationPolicy.canOpenParent(
                isEpub = true,
                isVolume = true,
                chapterUrl = "OPS/chapter.xhtml"
            )
        )
    }

    @Test
    fun structuralAndNonEpubParentsKeepToggleOnlyBehavior() {
        assertFalse(EpubTocNavigationPolicy.canOpenParent(true, true, "skip:0:Volume"))
        assertFalse(EpubTocNavigationPolicy.canOpenParent(false, true, "chapter-1"))
        assertFalse(EpubTocNavigationPolicy.canOpenParent(true, false, "chapter-1"))
    }

    @Test
    fun explicitStartFragmentWinsAndHrefFragmentIsFallback() {
        assertEquals(
            "declared-start",
            EpubTocNavigationPolicy.targetFragmentId("OPS/chapter.xhtml#href-start", "declared-start")
        )
        assertEquals(
            "section 2",
            EpubTocNavigationPolicy.targetFragmentId("OPS/chapter.xhtml#section%202", null)
        )
        assertNull(EpubTocNavigationPolicy.targetFragmentId("OPS/chapter.xhtml", ""))
    }

    @Test
    fun selectedLogicalIndexRemainsAuthoritativeOverItsHref() {
        val selection = EpubTocNavigationPolicy.selection(
            chapterIndex = 7,
            chapterUrl = "OPS/shared.xhtml#section-2",
            startFragmentId = null
        )

        assertEquals(7, selection.chapterIndex)
        assertEquals("section-2", selection.fragmentId)
    }
}
