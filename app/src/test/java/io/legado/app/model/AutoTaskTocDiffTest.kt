package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTaskTocDiffTest {

    private fun chapter(
        url: String,
        title: String = url,
        baseUrl: String = "https://example.com/toc/"
    ) = AutoTaskChapterSnapshot(url = url, title = title, baseUrl = baseUrl)

    @Test
    fun appendsOnlyUnseenChapters() {
        val diff = AutoTaskTocDiff.between(
            listOf(chapter("a"), chapter("b")),
            listOf(chapter("a"), chapter("b"), chapter("c"))
        )

        assertEquals(listOf("url:https://example.com/toc/c"), diff.added.map { it.key })
        assertEquals(1, diff.newCount)
        assertEquals(listOf("c"), diff.appended.map { it.title })
        assertTrue(diff.moved.isEmpty())
    }

    @Test
    fun insertionIsNotCountedAsEveryFollowingChapterBeingNew() {
        val diff = AutoTaskTocDiff.between(
            listOf(chapter("a"), chapter("c")),
            listOf(chapter("a"), chapter("b"), chapter("c"))
        )

        assertEquals(listOf("b"), diff.added.map { it.title })
        assertEquals(listOf("b"), diff.inserted.map { it.title })
        assertEquals(1, diff.moved.size)
    }

    @Test
    fun detectsReorderWithoutReportingNewChapters() {
        val diff = AutoTaskTocDiff.between(
            listOf(chapter("a"), chapter("b"), chapter("c")),
            listOf(chapter("c"), chapter("a"), chapter("b"))
        )

        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
        assertEquals(3, diff.moved.size)
    }

    @Test
    fun detectsDeletionAndDuplicateIdentities() {
        val diff = AutoTaskTocDiff.between(
            listOf(chapter("a"), chapter("b"), chapter("b"), chapter("c")),
            listOf(chapter("a"), chapter("b"), chapter("c"))
        )

        assertEquals(listOf("b"), diff.removed.map { it.title })
        assertTrue(diff.duplicateKeys.contains("url:https://example.com/toc/b"))
        assertTrue(diff.hasDuplicateChapters)
    }

    @Test
    fun usesUniqueTitleWhenAStableUrlChanges() {
        val diff = AutoTaskTocDiff.between(
            listOf(chapter("old", title = "Chapter One")),
            listOf(chapter("new", title = "Chapter One"))
        )

        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
        assertTrue(diff.moved.isEmpty())
    }

    @Test
    fun volumeIdentityDoesNotDependOnGeneratedUrl() {
        val diff = AutoTaskTocDiff.between(
            listOf(
                AutoTaskChapterSnapshot(
                    url = "Volume0",
                    title = "Volume One",
                    isVolume = true
                )
            ),
            listOf(
                AutoTaskChapterSnapshot(
                    url = "Volume-0-generated",
                    title = "Volume One",
                    isVolume = true
                )
            )
        )

        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
    }

    @Test
    fun canonicalizesRelativeUrlsAgainstBase() {
        val diff = AutoTaskTocDiff.between(
            listOf(chapter("../chapter-1", baseUrl = "https://example.com/book/toc/")),
            listOf(chapter("https://EXAMPLE.com/book/chapter-1"))
        )

        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
    }
}
