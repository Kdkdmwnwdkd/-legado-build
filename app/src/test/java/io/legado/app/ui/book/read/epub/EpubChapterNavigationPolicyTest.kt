package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubChapterNavigationPolicyTest {

    @Test
    fun `menu previous chapter opens the chapter start`() {
        assertTarget(
            direction = -1,
            intent = EpubChapterNavigationPolicy.Intent.ExplicitChapterJump,
            expectedEdge = EpubChapterNavigationPolicy.TargetEdge.Start,
            expectedBoundaryTransition = false
        )
    }

    @Test
    fun `menu next chapter opens the chapter start`() {
        assertTarget(
            direction = 1,
            intent = EpubChapterNavigationPolicy.Intent.ExplicitChapterJump,
            expectedEdge = EpubChapterNavigationPolicy.TargetEdge.Start,
            expectedBoundaryTransition = false
        )
    }

    @Test
    fun `backward page boundary opens the previous chapter end`() {
        assertTarget(
            direction = -1,
            intent = EpubChapterNavigationPolicy.Intent.PageTurnBoundary,
            expectedEdge = EpubChapterNavigationPolicy.TargetEdge.End,
            expectedBoundaryTransition = true
        )
    }

    @Test
    fun `forward page boundary opens the next chapter start`() {
        assertTarget(
            direction = 1,
            intent = EpubChapterNavigationPolicy.Intent.PageTurnBoundary,
            expectedEdge = EpubChapterNavigationPolicy.TargetEdge.Start,
            expectedBoundaryTransition = true
        )
    }

    @Test
    fun `zero direction has no chapter target`() {
        assertNull(
            EpubChapterNavigationPolicy.resolve(
                0,
                EpubChapterNavigationPolicy.Intent.ExplicitChapterJump
            )
        )
    }

    private fun assertTarget(
        direction: Int,
        intent: EpubChapterNavigationPolicy.Intent,
        expectedEdge: EpubChapterNavigationPolicy.TargetEdge,
        expectedBoundaryTransition: Boolean
    ) {
        assertEquals(
            EpubChapterNavigationPolicy.Target(
                chapterDelta = direction,
                edge = expectedEdge,
                boundaryTransition = expectedBoundaryTransition
            ),
            EpubChapterNavigationPolicy.resolve(direction, intent)
        )
    }
}
