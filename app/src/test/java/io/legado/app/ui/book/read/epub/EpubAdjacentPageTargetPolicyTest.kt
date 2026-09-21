package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubAdjacentPageTargetPolicyTest {

    @Test
    fun `same chapter neighbours do not depend on chapter preload`() {
        val plan = EpubAdjacentPageTargetPolicy.plan(
            chapterIndex = 12,
            pageIndex = 3,
            pageCount = 8,
            previousChapterAvailable = false,
            nextChapterAvailable = false
        )

        assertEquals(EpubAdjacentPageRequest(12, 2, false), plan.previous)
        assertEquals(EpubAdjacentPageRequest(12, 4, false), plan.next)
        assertEquals(EpubAdjacentPageRequest(12, 1, false), plan.previousPrefetch)
        assertEquals(EpubAdjacentPageRequest(12, 5, false), plan.nextPrefetch)
    }

    @Test
    fun `chapter edges require a prepared adjacent chapter`() {
        val firstPage = EpubAdjacentPageTargetPolicy.plan(7, 0, 5, false, true)
        assertNull(firstPage.previous)
        assertEquals(EpubAdjacentPageRequest(7, 1, false), firstPage.next)
        assertEquals(EpubAdjacentPageRequest(7, 2, false), firstPage.nextPrefetch)

        val lastPage = EpubAdjacentPageTargetPolicy.plan(7, 4, 5, true, true)
        assertEquals(EpubAdjacentPageRequest(7, 3, false), lastPage.previous)
        assertEquals(EpubAdjacentPageRequest(8, 0, false), lastPage.next)
        assertEquals(EpubAdjacentPageRequest(7, 2, false), lastPage.previousPrefetch)
        assertNull(lastPage.nextPrefetch)

        val previousBoundary = EpubAdjacentPageTargetPolicy.plan(7, 0, 1, true, false)
        assertEquals(EpubAdjacentPageRequest(6, 0, true), previousBoundary.previous)
        assertNull(previousBoundary.next)
    }

    @Test
    fun `two page window reaches a prepared chapter before the boundary turn`() {
        val beforeLast = EpubAdjacentPageTargetPolicy.plan(7, 3, 5, true, true)
        assertEquals(EpubAdjacentPageRequest(7, 4, false), beforeLast.next)
        assertEquals(EpubAdjacentPageRequest(8, 0, false), beforeLast.nextPrefetch)

        val afterFirst = EpubAdjacentPageTargetPolicy.plan(7, 1, 5, true, true)
        assertEquals(EpubAdjacentPageRequest(7, 0, false), afterFirst.previous)
        assertEquals(EpubAdjacentPageRequest(6, 0, true), afterFirst.previousPrefetch)
    }

    @Test
    fun `invalid current position produces no target`() {
        assertEquals(
            EpubAdjacentPagePlan(null, null),
            EpubAdjacentPageTargetPolicy.plan(2, 3, 3, true, true)
        )
    }

    @Test
    fun `chapter boundary targets use readable neighbours instead of arithmetic indexes`() {
        val plan = EpubAdjacentPageTargetPolicy.plan(
            chapterIndex = 3,
            pageIndex = 0,
            pageCount = 1,
            previousChapterIndex = 0,
            nextChapterIndex = 7
        )

        assertEquals(EpubAdjacentPageRequest(0, 0, true), plan.previous)
        assertEquals(EpubAdjacentPageRequest(7, 0, false), plan.next)
    }
}
