package io.legado.app.model.localBook.epubcore.direct

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDirectRangePolicyTest {

    @Test
    fun `initial range does not trigger full extraction`() {
        assertFalse(EpubDirectRangePolicy.shouldPrepareDiskCache("bytes=0-1023", 10_000L))
        assertFalse(EpubDirectRangePolicy.shouldPrepareDiskCache("bytes=0-", 10_000L))
    }

    @Test
    fun `seek and suffix ranges trigger full extraction`() {
        assertTrue(EpubDirectRangePolicy.shouldPrepareDiskCache("bytes=1024-2047", 10_000L))
        assertTrue(EpubDirectRangePolicy.shouldPrepareDiskCache("bytes=-512", 10_000L))
    }

    @Test
    fun `invalid or unknown ranges do not trigger extraction`() {
        assertFalse(EpubDirectRangePolicy.shouldPrepareDiskCache("bytes=20-10", 10_000L))
        assertFalse(EpubDirectRangePolicy.shouldPrepareDiskCache("bytes=0-1,4-5", 10_000L))
        assertFalse(EpubDirectRangePolicy.shouldPrepareDiskCache("items=1-2", 10_000L))
        assertFalse(EpubDirectRangePolicy.shouldPrepareDiskCache("bytes=1-2", null))
    }
}
