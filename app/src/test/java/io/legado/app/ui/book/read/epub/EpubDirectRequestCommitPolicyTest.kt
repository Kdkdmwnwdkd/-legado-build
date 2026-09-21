package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDirectRequestCommitPolicyTest {

    @Test
    fun `only the current callback owns the active navigation`() {
        assertTrue(
            EpubDirectRequestCommitPolicy.ownsActiveNavigation(
                requestSeq = 12L,
                currentRequestSeq = 12L,
                callbackRequestSeq = 12L
            )
        )
        assertFalse(
            EpubDirectRequestCommitPolicy.ownsActiveNavigation(
                requestSeq = 11L,
                currentRequestSeq = 12L,
                callbackRequestSeq = 12L
            )
        )
        assertFalse(
            EpubDirectRequestCommitPolicy.ownsActiveNavigation(
                requestSeq = 12L,
                currentRequestSeq = 12L,
                callbackRequestSeq = 13L
            )
        )
        assertFalse(
            EpubDirectRequestCommitPolicy.ownsActiveNavigation(
                requestSeq = 12L,
                currentRequestSeq = 12L,
                callbackRequestSeq = 0L
            )
        )
    }

    @Test
    fun `current core request may commit`() {
        assertTrue(
            EpubDirectRequestCommitPolicy.canCommit(
                requestSeq = 12L,
                currentRequestSeq = 12L,
                requestBookUrl = "book",
                currentBookUrl = "book",
                usesCore = true
            )
        )
    }

    @Test
    fun `stale request or replaced book cannot commit`() {
        assertFalse(
            EpubDirectRequestCommitPolicy.canCommit(
                requestSeq = 11L,
                currentRequestSeq = 12L,
                requestBookUrl = "book",
                currentBookUrl = "book",
                usesCore = true
            )
        )
        assertFalse(
            EpubDirectRequestCommitPolicy.canCommit(
                requestSeq = 12L,
                currentRequestSeq = 12L,
                requestBookUrl = "book-a",
                currentBookUrl = "book-b",
                usesCore = true
            )
        )
    }

    @Test
    fun `request cannot commit after leaving core`() {
        assertFalse(
            EpubDirectRequestCommitPolicy.canCommit(
                requestSeq = 12L,
                currentRequestSeq = 12L,
                requestBookUrl = "book",
                currentBookUrl = "book",
                usesCore = false
            )
        )
    }
}
