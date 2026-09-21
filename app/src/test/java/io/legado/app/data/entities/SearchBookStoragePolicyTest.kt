package io.legado.app.data.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchBookStoragePolicyTest {

    @Test
    fun countsUtf8BytesWithoutCreatingEncodedCopies() {
        assertEquals(5L, SearchBookStoragePolicy.utf8ByteCount("hello"))
        assertEquals(6L, SearchBookStoragePolicy.utf8ByteCount("\u4E66\u6E90"))
        assertEquals(4L, SearchBookStoragePolicy.utf8ByteCount("\uD83D\uDE03"))
        assertEquals(8L, SearchBookStoragePolicy.utf8ByteCount("A\u4E66\uD83D\uDE00"))
    }

    @Test
    fun configuredFieldBudgetsStayBelowRowBudget() {
        assertTrue(
            SearchBookStoragePolicy.MAX_CONFIGURED_TEXT_BYTES <=
                SearchBookStoragePolicy.MAX_STORED_ROW_BYTES
        )
    }

    @Test
    fun sanitizedRowStaysWithinTheConfiguredByteBudget() {
        val stored = requireNotNull(
            SearchBook(
                bookUrl = "u".repeat(SearchBookStoragePolicy.MAX_BOOK_URL_BYTES),
                origin = "o".repeat(SearchBookStoragePolicy.MAX_ORIGIN_BYTES),
                originName = "s".repeat(SearchBookStoragePolicy.MAX_ORIGIN_NAME_BYTES),
                name = "n".repeat(SearchBookStoragePolicy.MAX_NAME_BYTES),
                author = "a".repeat(SearchBookStoragePolicy.MAX_AUTHOR_BYTES),
                kind = "k".repeat(SearchBookStoragePolicy.MAX_KIND_BYTES),
                coverUrl = "c".repeat(SearchBookStoragePolicy.MAX_COVER_URL_BYTES),
                intro = "i".repeat(SearchBookStoragePolicy.MAX_INTRO_BYTES),
                wordCount = "w".repeat(SearchBookStoragePolicy.MAX_WORD_COUNT_BYTES),
                latestChapterTitle = "l".repeat(SearchBookStoragePolicy.MAX_CHAPTER_TITLE_BYTES),
                tocUrl = "t".repeat(SearchBookStoragePolicy.MAX_TOC_URL_BYTES),
                variable = "v".repeat(SearchBookStoragePolicy.MAX_VARIABLE_BYTES),
                chapterWordCountText = "x".repeat(
                    SearchBookStoragePolicy.MAX_CHAPTER_WORD_COUNT_TEXT_BYTES
                )
            ).sanitizedForStorage()
        )

        assertTrue(
            SearchBookStoragePolicy.storedUtf8ByteCount(stored) <=
                SearchBookStoragePolicy.MAX_STORED_ROW_BYTES
        )
    }

    @Test
    fun sanitizingUsesAStorageCopyAndDropsRuntimeHtml() {
        val source = SearchBook(
            bookUrl = "https://example.com/book",
            origin = "https://example.com/source",
            intro = "i".repeat(SearchBookStoragePolicy.MAX_INTRO_BYTES + 1)
        ).apply {
            infoHtml = "runtime-info"
            tocHtml = "runtime-toc"
        }

        val stored = requireNotNull(source.sanitizedForStorage())

        assertNotSame(source, stored)
        assertEquals(SearchBookStoragePolicy.MAX_INTRO_BYTES + 1, source.intro?.length)
        assertEquals(SearchBookStoragePolicy.MAX_INTRO_BYTES, stored.intro?.length)
        assertNull(stored.infoHtml)
        assertNull(stored.tocHtml)
    }

    @Test
    fun truncationUsesUtf8BytesAndNeverCutsAnEmojiPair() {
        val emoji = "\uD83D\uDE03"
        val originNamePrefix = "a".repeat(SearchBookStoragePolicy.MAX_ORIGIN_NAME_BYTES - 4)
        val introPrefix = "a".repeat(SearchBookStoragePolicy.MAX_INTRO_BYTES - 2)
        val stored = requireNotNull(
            SearchBook(
                originName = originNamePrefix + emoji + "b",
                intro = introPrefix + emoji
            ).sanitizedForStorage()
        )

        assertEquals(originNamePrefix + emoji, stored.originName)
        assertEquals(introPrefix, stored.intro)
        assertFalse(stored.originName.lastOrNull()?.isHighSurrogate() == true)
        assertFalse(stored.intro.orEmpty().lastOrNull()?.isHighSurrogate() == true)
    }

    @Test
    fun taggedIntroRemainsClosedAfterUtf8Truncation() {
        listOf(
            "<useweb>" to "</useweb>",
            "<usehtml>" to "</usehtml>",
            "<md>" to "</md>"
        ).forEach { (startTag, endTag) ->
            val stored = requireNotNull(
                SearchBook(
                    intro = startTag.uppercase() +
                        "\u4E66".repeat(SearchBookStoragePolicy.MAX_INTRO_BYTES)
                ).sanitizedForStorage()
            )
            val intro = requireNotNull(stored.intro)
            assertTrue(intro.startsWith(startTag))
            assertTrue(intro.endsWith(endTag))
            assertTrue(
                SearchBookStoragePolicy.utf8ByteCount(intro) <=
                    SearchBookStoragePolicy.MAX_INTRO_BYTES
            )
        }
    }

    @Test
    fun rejectsOversizedIdentityAndJsonFields() {
        assertNull(
            SearchBook(
                bookUrl = "u".repeat(SearchBookStoragePolicy.MAX_BOOK_URL_BYTES + 1)
            ).sanitizedForStorage()
        )
        assertNull(
            SearchBook(
                tocUrl = "t".repeat(SearchBookStoragePolicy.MAX_TOC_URL_BYTES + 1)
            ).sanitizedForStorage()
        )
        assertNull(
            SearchBook(
                variable = "v".repeat(SearchBookStoragePolicy.MAX_VARIABLE_BYTES + 1)
            ).sanitizedForStorage()
        )
        assertNull(
            SearchBook(
                origin = "o".repeat(SearchBookStoragePolicy.MAX_ORIGIN_BYTES + 1)
            ).sanitizedForStorage()
        )
        assertNull(
            SearchBook(
                name = "n".repeat(SearchBookStoragePolicy.MAX_NAME_BYTES + 1)
            ).sanitizedForStorage()
        )
        assertNull(
            SearchBook(
                author = "a".repeat(SearchBookStoragePolicy.MAX_AUTHOR_BYTES + 1)
            ).sanitizedForStorage()
        )
    }

    @Test
    fun identityLimitsUseUtf8BytesInsteadOfUtf16Characters() {
        val byteLimit = SearchBookStoragePolicy.MAX_BOOK_URL_BYTES
        val exactChineseBoundary = "\u4E66".repeat(byteLimit / 3) +
            "a".repeat(byteLimit % 3)
        val exactEmojiBoundary = "\uD83D\uDE03".repeat(byteLimit / 4)

        assertEquals(
            exactChineseBoundary,
            SearchBookStoragePolicy.cleanupBookUrl(exactChineseBoundary)
        )
        assertNull(SearchBookStoragePolicy.cleanupBookUrl(exactChineseBoundary + "\u4E66"))
        assertEquals(
            exactEmojiBoundary,
            SearchBookStoragePolicy.cleanupBookUrl(exactEmojiBoundary)
        )
        assertNull(SearchBookStoragePolicy.cleanupBookUrl(exactEmojiBoundary + "a"))
    }

    @Test
    fun oversizedOptionalUrlIsDroppedInsteadOfTruncated() {
        val stored = requireNotNull(
            SearchBook(
                coverUrl = "c".repeat(SearchBookStoragePolicy.MAX_COVER_URL_BYTES + 1)
            ).sanitizedForStorage()
        )

        assertNull(stored.coverUrl)
    }

    @Test
    fun cleanupKeyAllowsLegacyEmptyAndRejectsOversizedBookUrls() {
        val maxSafeUrl = "u".repeat(SearchBookStoragePolicy.MAX_BOOK_URL_BYTES)

        assertEquals(maxSafeUrl, SearchBookStoragePolicy.cleanupBookUrl(maxSafeUrl))
        assertNull(SearchBookStoragePolicy.cleanupBookUrl(maxSafeUrl + "u"))
        assertEquals("", SearchBookStoragePolicy.cleanupBookUrl(""))
    }
}
