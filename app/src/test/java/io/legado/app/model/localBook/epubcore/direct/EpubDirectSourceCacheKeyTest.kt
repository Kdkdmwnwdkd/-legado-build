package io.legado.app.model.localBook.epubcore.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EpubDirectSourceCacheKeyTest {

    @Test
    fun logicalFragmentsOfOneXhtmlSharePreparedSource() {
        val first = EpubDirectSourceCacheKey.create(
            href = "OPS/chapter.xhtml",
            mediaType = "application/xhtml+xml",
            title = "Part 1",
            continuationHrefs = emptyList()
        )
        val second = EpubDirectSourceCacheKey.create(
            href = "OPS/chapter.xhtml",
            mediaType = "application/xhtml+xml",
            title = "Part 2",
            continuationHrefs = emptyList()
        )

        assertEquals(first, second)
    }

    @Test
    fun continuationDocumentsAndGeneratedMediaTitlesRemainDistinct() {
        val base = EpubDirectSourceCacheKey.create(
            "OPS/chapter.xhtml",
            "application/xhtml+xml",
            "Chapter",
            emptyList()
        )
        val continued = EpubDirectSourceCacheKey.create(
            "OPS/chapter.xhtml",
            "application/xhtml+xml",
            "Chapter",
            listOf("OPS/chapter-2.xhtml")
        )
        val imageOne = EpubDirectSourceCacheKey.create(
            "OPS/cover.jpg",
            "image/jpeg; charset=binary",
            "Cover",
            emptyList()
        )
        val imageTwo = EpubDirectSourceCacheKey.create(
            "OPS/cover.jpg",
            "image/jpeg",
            "Frontispiece",
            emptyList()
        )

        assertNotEquals(base, continued)
        assertNotEquals(imageOne, imageTwo)
    }
}
