package io.legado.app.help.book

import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphRuleProcessorCacheTest {

    @Test
    fun cacheEstimateTracksChapterTextAndIndexes() {
        val small = BookContent(false, listOf("short"), null)
        val large = BookContent(false, List(100) { "x".repeat(1000) }, null)

        assertTrue(
            ParagraphRuleProcessor.estimateProcessCacheBytes(large) >
                ParagraphRuleProcessor.estimateProcessCacheBytes(small)
        )
    }
}
