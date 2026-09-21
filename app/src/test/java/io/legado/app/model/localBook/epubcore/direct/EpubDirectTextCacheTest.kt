package io.legado.app.model.localBook.epubcore.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDirectTextCacheTest {

    @Test
    fun `evicts least recently used text within total budget`() {
        val cache = EpubDirectTextCache(maxEntries = 3, maxEntryChars = 6, maxTotalChars = 8)
        cache.put("first", "1111")
        cache.put("second", "2222")
        assertEquals("1111", cache["first"])

        cache.put("third", "3333")

        assertEquals("1111", cache["first"])
        assertNull(cache["second"])
        assertEquals("3333", cache["third"])
    }

    @Test
    fun `does not cache an oversized chapter`() {
        val cache = EpubDirectTextCache(maxEntries = 2, maxEntryChars = 4, maxTotalChars = 8)

        assertFalse(cache.put("large", "12345"))
        assertNull(cache["large"])
        assertTrue(cache.put("small", "1234"))
    }
}
