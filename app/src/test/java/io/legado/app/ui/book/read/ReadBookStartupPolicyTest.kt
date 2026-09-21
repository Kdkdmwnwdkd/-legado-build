package io.legado.app.ui.book.read

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadBookStartupPolicyTest {

    @Test
    fun `ordinary direct bypasses canvas startup independently of epub preference`() {
        listOf(true, false).forEach { epubPreference ->
            val decision = ReadBookStartupPolicy.decide(isEpub = false, useEpubCore = epubPreference, directText = true)
            assertEquals(ReadBookStartupPolicy.ContentLoader.DIRECT_EPUB, decision.contentLoader)
            assertFalse(decision.notifyContentDuringReset)
        }
    }

    @Test
    fun `direct epub bypasses standard content loading and defers reset notification`() {
        val decision = ReadBookStartupPolicy.decide(isEpub = true, useEpubCore = true)

        assertEquals(ReadBookStartupPolicy.ContentLoader.DIRECT_EPUB, decision.contentLoader)
        assertFalse(decision.notifyContentDuringReset)
    }

    @Test
    fun `explicit text epub keeps standard content loading`() {
        val decision = ReadBookStartupPolicy.decide(isEpub = true, useEpubCore = false)

        assertEquals(ReadBookStartupPolicy.ContentLoader.STANDARD, decision.contentLoader)
        assertTrue(decision.notifyContentDuringReset)
    }

    @Test
    fun `non epub books keep standard content loading`() {
        val decision = ReadBookStartupPolicy.decide(isEpub = false, useEpubCore = true)

        assertEquals(ReadBookStartupPolicy.ContentLoader.STANDARD, decision.contentLoader)
        assertTrue(decision.notifyContentDuringReset)
    }

    @Test
    fun `direct preparation waits until reader status is cleared`() {
        assertFalse(ReadBookStartupPolicy.shouldPrepareDirectContent("打开本地书籍出错"))
        assertTrue(ReadBookStartupPolicy.shouldPrepareDirectContent(null))
    }
}
