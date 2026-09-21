package io.legado.app.help.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class RestoreJournalStateTest {

    @Test
    fun stateCasRequiresMatchingGenerationAndStatus() {
        val current = RestoreJournal.State(
            status = "pending",
            generation = "new-generation"
        )

        assertTrue(
            restoreJournalStateMatches(
                current,
                "new-generation",
                setOf("pending")
            )
        )
        assertFalse(
            restoreJournalStateMatches(
                current,
                "old-generation",
                setOf("pending")
            )
        )
        assertFalse(
            restoreJournalStateMatches(
                current,
                "new-generation",
                setOf("applying")
            )
        )
    }

    @Test
    fun activeRestoreAndRollbackOwnerGateClaims() {
        assertTrue(canClaimRestoreRollback(null, null, null))
        assertTrue(canClaimRestoreRollback("active", null, "active"))
        assertFalse(canClaimRestoreRollback("active", null, null))
        assertFalse(canClaimRestoreRollback("active", null, "other"))
        assertFalse(canClaimRestoreRollback(null, "rollback-owner", null))
        assertFalse(canClaimRestoreRollback("active", "rollback-owner", "active"))
    }

    @Test
    fun preparingJournalRequiresStateAndEveryExistingSnapshot() {
        val temp = createTempDirectory("restore-journal-state-").toFile()
        try {
            val preparing = File(
                temp,
                ".restore_journal.preparing-generation"
            ).apply { mkdirs() }
            val published = File(temp, "restore_journal")
            val snapshot = File(preparing, "snapshot/0").apply {
                parentFile?.mkdirs()
                writeText("snapshot")
            }
            val state = RestoreJournal.State(
                status = "applying",
                generation = "generation",
                entries = arrayListOf(
                    RestoreJournal.Entry(
                        targetPath = File(temp, "target").absolutePath,
                        snapshotPath = File(published, "snapshot/0").absolutePath,
                        directory = false,
                        existed = true
                    )
                )
            )

            assertFalse(restoreJournalPreparingStateIsComplete(preparing, published, state))
            File(preparing, "state.json").writeText("state")
            assertTrue(restoreJournalPreparingStateIsComplete(preparing, published, state))
            assertTrue(snapshot.delete())
            assertFalse(restoreJournalPreparingStateIsComplete(preparing, published, state))

            assertTrue(snapshot.mkdirs())
            assertFalse(restoreJournalPreparingStateIsComplete(preparing, published, state))
            assertTrue(snapshot.delete())
            snapshot.writeText("snapshot")
            assertFalse(
                restoreJournalPreparingStateIsComplete(
                    preparing,
                    published,
                    state.copy(status = "pending")
                )
            )
            assertFalse(
                restoreJournalPreparingStateIsComplete(
                    preparing,
                    published,
                    state.copy(generation = null)
                )
            )
        } finally {
            temp.deleteRecursively()
        }
    }
}
