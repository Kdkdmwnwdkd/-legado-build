package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDirectActivationTargetPolicyTest {

    @Test
    fun `chapter end follows the current page count`() {
        val target = EpubDirectActivationTargetPolicy.chapterBoundary(openAtEnd = true)

        assertEquals(4, EpubDirectActivationTargetPolicy.resolve(target, pageCount = 5))
        assertEquals(7, EpubDirectActivationTargetPolicy.resolve(target, pageCount = 8))
        assertTrue(
            EpubDirectActivationTargetPolicy.isSatisfied(
                target = target,
                pageIndex = 7,
                pageCount = 8
            )
        )
    }

    @Test
    fun `chapter boundary requires the resolved boundary page`() {
        val target = EpubDirectActivationTargetPolicy.chapterBoundary(openAtEnd = false)

        assertFalse(
            EpubDirectActivationTargetPolicy.isSatisfied(
                target = target,
                pageIndex = 1,
                pageCount = 8
            )
        )
    }

    @Test
    fun `ordinary page target remains local to its measured page count`() {
        val target = EpubDirectActivationTargetPolicy.pageIndex(9)

        assertEquals(4, EpubDirectActivationTargetPolicy.resolve(target, pageCount = 5))
        assertTrue(
            EpubDirectActivationTargetPolicy.isSatisfied(
                target = target,
                pageIndex = 4,
                pageCount = 5
            )
        )
    }
}
