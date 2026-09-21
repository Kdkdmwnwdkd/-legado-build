package io.legado.app.ui.main.explore

import io.legado.app.data.entities.SearchBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryCachePolicyTest {

    @Test
    fun countsUtf8WithoutAllocatingEncodedCopy() {
        assertEquals(5L, DiscoveryCachePolicy.utf8ByteCount("hello"))
        assertEquals(6L, DiscoveryCachePolicy.utf8ByteCount("书源"))
        assertEquals(4L, DiscoveryCachePolicy.utf8ByteCount("😀"))
        assertEquals(8L, DiscoveryCachePolicy.utf8ByteCount("A书😀"))
    }

    @Test
    fun rejectsMissingOrOversizedStoredRows() {
        assertFalse(DiscoveryCachePolicy.canRead(null))
        assertTrue(DiscoveryCachePolicy.canRead(DiscoveryCachePolicy.MAX_SQLITE_VALUE_BYTES))
        assertFalse(DiscoveryCachePolicy.canRead(DiscoveryCachePolicy.MAX_SQLITE_VALUE_BYTES + 1))
    }

    @Test
    fun stopsOversizedJsonBeforeItCanReachCursorWindow() {
        val atLimit = "a".repeat(DiscoveryCachePolicy.MAX_SQLITE_VALUE_BYTES.toInt())
        assertTrue(DiscoveryCachePolicy.canStore(atLimit))
        assertFalse(DiscoveryCachePolicy.canStore("$atLimit+"))
    }

    @Test
    fun boundedSerializationStopsBeforeBuildingOversizedJson() {
        assertNotNull(DiscoveryCachePolicy.toBoundedJson(mapOf("value" to "small")))
        assertNull(
            DiscoveryCachePolicy.toBoundedJson(
                mapOf("value" to "a".repeat(DiscoveryCachePolicy.MAX_SQLITE_VALUE_BYTES.toInt()))
            )
        )
    }

    @Test
    fun compactionDropsRuntimeHtmlAndBoundsDisplayText() {
        val source = SearchBook(
            bookUrl = "https://example.com/book",
            origin = "https://example.com/source",
            name = "n".repeat(1_000),
            intro = "i".repeat(10_000)
        ).apply {
            infoHtml = "x".repeat(20_000)
            tocHtml = "y".repeat(20_000)
        }

        val compact = requireNotNull(DiscoveryCachePolicy.compact(source))

        assertEquals(512, compact.name.length)
        assertEquals(4_096, compact.intro?.length)
        assertNull(compact.infoHtml)
        assertNull(compact.tocHtml)
        assertEquals(source.bookUrl, compact.bookUrl)
        assertEquals(source.origin, compact.origin)
    }

    @Test
    fun compactionRejectsUnboundedSourceControlledFields() {
        val oversizedVariable = SearchBook(
            bookUrl = "https://example.com/book",
            origin = "https://example.com/source",
            variable = "v".repeat(20_000)
        )

        assertNull(DiscoveryCachePolicy.compact(oversizedVariable))
    }

    @Test
    fun compactionRejectsOversizedTocUrlInsteadOfClearingRequiredState() {
        val book = SearchBook(
            bookUrl = "https://example.com/book",
            origin = "https://example.com/source",
            tocUrl = "t".repeat(40_000)
        )

        assertNull(DiscoveryCachePolicy.compact(book))
    }
}
