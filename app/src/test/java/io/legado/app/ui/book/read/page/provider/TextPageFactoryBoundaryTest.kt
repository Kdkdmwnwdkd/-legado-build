package io.legado.app.ui.book.read.page.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPageFactoryBoundaryTest {

    @Test
    fun `scroll mode waits for next chapter layout to finish`() {
        assertFalse(
            canEnterNextTextChapter(
                hasCurrentChapter = true,
                isScroll = true,
                hasNextChapter = true,
                isNextChapterCompleted = false
            )
        )
    }

    @Test
    fun `scroll mode enters completed next chapter`() {
        assertTrue(
            canEnterNextTextChapter(
                hasCurrentChapter = true,
                isScroll = true,
                hasNextChapter = true,
                isNextChapterCompleted = true
            )
        )
    }

    @Test
    fun `paged mode can request an unloaded next chapter`() {
        assertTrue(
            canEnterNextTextChapter(
                hasCurrentChapter = true,
                isScroll = false,
                hasNextChapter = false,
                isNextChapterCompleted = false
            )
        )
    }
}
