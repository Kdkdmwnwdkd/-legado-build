package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubDirectInitialFragmentPolicyTest {

    @Test
    fun explicitLinkTargetWinsOverLogicalChapterStart() {
        assertEquals(
            "note-4",
            EpubDirectInitialFragmentPolicy.resolve("note-4", "chapter-2", true)
        )
    }

    @Test
    fun tocNavigationUsesLogicalChapterStartAsFallback() {
        assertEquals(
            "chapter-2",
            EpubDirectInitialFragmentPolicy.resolve(null, "chapter-2", true)
        )
    }

    @Test
    fun progressRestoreDoesNotForceChapterStart() {
        assertNull(EpubDirectInitialFragmentPolicy.resolve(null, "chapter-2", false))
    }
}
