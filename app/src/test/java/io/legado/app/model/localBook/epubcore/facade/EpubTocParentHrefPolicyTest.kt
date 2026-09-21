package io.legado.app.model.localBook.epubcore.facade

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubTocParentHrefPolicyTest {

    @Test
    fun `same document parent with a child fragment becomes structural`() {
        assertTrue(
            EpubTocParentHrefPolicy.shouldBecomeStructural(
                isVolume = true,
                parentUrl = "OPS/chapter.xhtml#part",
                nextChapterUrl = "OPS/chapter.xhtml#section-1"
            )
        )
        assertTrue(
            EpubTocParentHrefPolicy.shouldBecomeStructural(
                isVolume = true,
                parentUrl = "OPS/chapter.xhtml",
                nextChapterUrl = "OPS/chapter.xhtml#section-1"
            )
        )
    }

    @Test
    fun `exact duplicate parent remains structural`() {
        assertTrue(
            EpubTocParentHrefPolicy.shouldBecomeStructural(
                isVolume = true,
                parentUrl = "OPS/chapter.xhtml#section-1",
                nextChapterUrl = "OPS/chapter.xhtml#section-1"
            )
        )
    }

    @Test
    fun `ordinary chapter and existing structural item are unchanged`() {
        assertFalse(
            EpubTocParentHrefPolicy.shouldBecomeStructural(
                isVolume = false,
                parentUrl = "OPS/chapter.xhtml",
                nextChapterUrl = "OPS/chapter.xhtml"
            )
        )
        assertFalse(
            EpubTocParentHrefPolicy.shouldBecomeStructural(
                isVolume = true,
                parentUrl = "skip:1:Part",
                nextChapterUrl = "OPS/chapter.xhtml"
            )
        )
    }

    @Test
    fun `parent and child in different documents remain distinct`() {
        assertFalse(
            EpubTocParentHrefPolicy.shouldBecomeStructural(
                isVolume = true,
                parentUrl = "OPS/part.xhtml#opening",
                nextChapterUrl = "OPS/chapter-1.xhtml#section-1"
            )
        )
    }
}
