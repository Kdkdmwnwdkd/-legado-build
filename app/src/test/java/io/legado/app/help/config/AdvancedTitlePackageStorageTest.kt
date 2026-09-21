package io.legado.app.help.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

class AdvancedTitlePackageStorageTest {

    @Test
    fun stagingDirectoryDoesNotNeedToMatchFinalPackageId() {
        AdvancedTitlePackageStorage.requireDirectoryMatchesId(
            directoryName = ".title_abc.staging-${UUID.randomUUID()}",
            configId = "title_abc",
            requireMatch = false
        )
    }

    @Test
    fun installedDirectoryMustStillMatchPackageId() {
        AdvancedTitlePackageStorage.requireDirectoryMatchesId(
            directoryName = "title_abc",
            configId = "title_abc",
            requireMatch = true
        )

        assertThrows(IllegalArgumentException::class.java) {
            AdvancedTitlePackageStorage.requireDirectoryMatchesId(
                directoryName = "title_other",
                configId = "title_abc",
                requireMatch = true
            )
        }
    }

    @Test
    fun cleanupOnlyDeletesRecognizedStagingDirectories() {
        val root = Files.createTempDirectory("advanced-title-storage").toFile()
        try {
            val staging = root.resolve(".title_abc.staging-${UUID.randomUUID()}").apply {
                mkdirs()
                resolve("title.json").writeText("stale")
            }
            val backup = root.resolve(".title_abc.backup-${UUID.randomUUID()}").apply {
                mkdirs()
                resolve("title.json").writeText("recovery")
            }
            val unrelated = root.resolve(".unrelated").apply { mkdirs() }

            assertEquals(1, AdvancedTitlePackageStorage.cleanupStaleStagingDirectories(root))
            assertFalse(staging.exists())
            assertTrue(backup.exists())
            assertTrue(unrelated.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun targetedCleanupRejectsBackupDirectory() {
        val root = Files.createTempDirectory("advanced-title-storage").toFile()
        try {
            val backup = root.resolve(".title_abc.backup-${UUID.randomUUID()}").apply {
                mkdirs()
            }

            assertFalse(AdvancedTitlePackageStorage.deleteStagingDirectory(root, backup))
            assertTrue(backup.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
