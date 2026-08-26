package io.legado.app.help.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory

class BubbleDirectoryTransactionTest {

    @Test
    fun replacesExistingDirectoryAndRemovesBackup() {
        val root = createTempDirectory("bubble-transaction-").toFile()
        try {
            val target = directory(root, "target", "old")
            val staging = directory(root, "staging", "new")
            val backup = File(root, "backup")

            val result = BubbleDirectoryTransaction().install(target, staging, backup) { "ok" }

            assertEquals("ok", result)
            assertEquals("new", File(target, "value.txt").readText())
            assertFalse(backup.exists())
            assertFalse(staging.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun restoresExistingDirectoryWhenInstalledPackageFailsVerification() {
        val root = createTempDirectory("bubble-transaction-").toFile()
        try {
            val target = directory(root, "target", "old")
            val staging = directory(root, "staging", "new")
            val backup = File(root, "backup")

            expectIOException {
                BubbleDirectoryTransaction().install(target, staging, backup) {
                    throw IOException("verification failed")
                }
            }

            assertEquals("old", File(target, "value.txt").readText())
            assertFalse(backup.exists())
            assertFalse(staging.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun restoresExistingDirectoryWhenStagingMoveFails() {
        val root = createTempDirectory("bubble-transaction-").toFile()
        try {
            val target = directory(root, "target", "old")
            val staging = directory(root, "staging", "new")
            val backup = File(root, "backup")
            val exchange = FailingMoveExchange(failMoveNumber = 2)

            expectIOException {
                BubbleDirectoryTransaction(exchange).install(target, staging, backup) { Unit }
            }

            assertEquals("old", File(target, "value.txt").readText())
            assertFalse(backup.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun removesNewTargetWhenVerificationFailsWithoutBackup() {
        val root = createTempDirectory("bubble-transaction-").toFile()
        try {
            val target = File(root, "target")
            val staging = directory(root, "staging", "new")

            expectIOException {
                BubbleDirectoryTransaction().install(target, staging, File(root, "backup")) {
                    throw IOException("verification failed")
                }
            }

            assertFalse(target.exists())
            assertTrue(root.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun directory(root: File, name: String, value: String): File =
        File(root, name).apply {
            mkdirs()
            File(this, "value.txt").writeText(value)
        }

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
        }
    }

    private class FailingMoveExchange(
        private val failMoveNumber: Int
    ) : FileExchange {
        private var moveCount = 0

        override fun move(source: File, target: File) {
            moveCount++
            if (moveCount == failMoveNumber) throw IOException("injected move failure")
            NioFileExchange.move(source, target)
        }

        override fun delete(file: File) {
            if (file.isDirectory) {
                if (!file.deleteRecursively()) throw IOException("delete failed")
            } else {
                NioFileExchange.delete(file)
            }
        }
    }
}
