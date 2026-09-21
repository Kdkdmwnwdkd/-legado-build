package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubDirectNavigationTargetPolicyTest {

    @Test
    fun `new explicit request overrides an older pending target`() {
        val pending = EpubDirectNavigationTargetPolicy.Target(
            chapterIndex = 9,
            resetPageOffset = false,
            boundaryTransition = true,
            fragmentId = "old-fragment"
        )
        assertEquals(
            EpubDirectNavigationTargetPolicy.Target(
                chapterIndex = 17,
                resetPageOffset = true,
                boundaryTransition = false,
                fragmentId = "new-fragment"
            ),
            EpubDirectNavigationTargetPolicy.resolve(
                requestedChapterIndex = 17,
                requestedResetPageOffset = true,
                requestedBoundaryTransition = false,
                requestedFragmentId = "new-fragment",
                pendingTarget = pending,
                currentChapterIndex = 3,
                relativePosition = 0,
                chapterCount = 30
            )
        )
    }

    @Test
    fun `layout reload keeps the complete pending explicit target`() {
        val pending = EpubDirectNavigationTargetPolicy.Target(
            chapterIndex = 17,
            resetPageOffset = true,
            boundaryTransition = true,
            fragmentId = "section-17"
        )
        assertEquals(
            pending,
            EpubDirectNavigationTargetPolicy.resolve(
                requestedChapterIndex = null,
                requestedResetPageOffset = false,
                requestedBoundaryTransition = false,
                requestedFragmentId = null,
                pendingTarget = pending,
                currentChapterIndex = 3,
                relativePosition = 0,
                chapterCount = 30
            )
        )
    }

    @Test
    fun `ordinary load falls back to current chapter and relative offset`() {
        assertEquals(
            EpubDirectNavigationTargetPolicy.Target(
                chapterIndex = 4,
                resetPageOffset = false,
                boundaryTransition = false,
                fragmentId = null
            ),
            EpubDirectNavigationTargetPolicy.resolve(
                requestedChapterIndex = null,
                requestedResetPageOffset = false,
                requestedBoundaryTransition = false,
                requestedFragmentId = null,
                pendingTarget = null,
                currentChapterIndex = 3,
                relativePosition = 1,
                chapterCount = 30
            )
        )
    }

    @Test
    fun `target is bounded and empty chapter list has no target`() {
        assertEquals(
            5,
            EpubDirectNavigationTargetPolicy.resolve(
                requestedChapterIndex = 50,
                requestedResetPageOffset = true,
                requestedBoundaryTransition = false,
                requestedFragmentId = null,
                pendingTarget = null,
                currentChapterIndex = 0,
                relativePosition = 0,
                chapterCount = 6
            )?.chapterIndex
        )
        assertNull(
            EpubDirectNavigationTargetPolicy.resolve(
                requestedChapterIndex = 0,
                requestedResetPageOffset = true,
                requestedBoundaryTransition = false,
                requestedFragmentId = null,
                pendingTarget = null,
                currentChapterIndex = 0,
                relativePosition = 0,
                chapterCount = 0
            )
        )
    }

    @Test
    fun `ordinary text target is not clamped after the stored directory shrinks`() {
        assertEquals(9, EpubDirectNavigationTargetPolicy.resolve(
            requestedChapterIndex = 9,
            requestedResetPageOffset = true,
            requestedBoundaryTransition = false,
            requestedFragmentId = "__legado_text_45",
            pendingTarget = null,
            currentChapterIndex = 0,
            relativePosition = 0,
            chapterCount = 8,
            preserveChapterIdentity = true
        )?.chapterIndex)
        assertNull(EpubDirectNavigationTargetPolicy.resolve(
            requestedChapterIndex = -1,
            requestedResetPageOffset = true,
            requestedBoundaryTransition = false,
            requestedFragmentId = null,
            pendingTarget = null,
            currentChapterIndex = 0,
            relativePosition = 0,
            chapterCount = 8,
            preserveChapterIdentity = true
        ))
    }

    @Test
    fun `accepted boundary target is never clamped to another chapter`() {
        assertEquals(
            9,
            EpubDirectNavigationTargetPolicy.resolve(
                requestedChapterIndex = 9,
                requestedResetPageOffset = true,
                requestedBoundaryTransition = true,
                requestedFragmentId = null,
                pendingTarget = null,
                currentChapterIndex = 7,
                relativePosition = 0,
                chapterCount = 8
            )?.chapterIndex
        )
    }
}
