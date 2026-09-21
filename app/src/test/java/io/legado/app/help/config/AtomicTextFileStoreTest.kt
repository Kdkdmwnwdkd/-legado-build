package io.legado.app.help.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AtomicTextFileStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writesVerifiedContent() {
        val target = temporaryFolder.newFile("theme.json").apply { writeText("old") }

        AtomicTextFileStore(target).writeVerified("new") { it == "new" }

        assertEquals("new", target.readText())
        assertFalse(backupOf(target).exists())
        assertFalse(stagingOf(target).exists())
    }

    @Test
    fun verificationFailureKeepsOldContent() {
        val target = temporaryFolder.newFile("theme.json").apply { writeText("old") }

        assertThrows(IllegalStateException::class.java) {
            AtomicTextFileStore(target).writeVerified("invalid") { false }
        }

        assertEquals("old", target.readText())
        assertFalse(backupOf(target).exists())
        assertFalse(stagingOf(target).exists())
    }

    @Test
    fun installFailureRestoresOldContent() {
        val target = temporaryFolder.newFile("theme.json").apply { writeText("old") }
        val exchange = FailingExchange(failMoveToTargetCount = 1)

        assertThrows(IOException::class.java) {
            AtomicTextFileStore(target, exchange).writeVerified("new") { true }
        }

        assertEquals("old", target.readText())
        assertFalse(backupOf(target).exists())
    }

    @Test
    fun restoreFailurePreservesBackup() {
        val target = temporaryFolder.newFile("theme.json").apply { writeText("old") }
        val exchange = FailingExchange(failMoveToTargetCount = 2)

        val error = assertThrows(AtomicFileRestoreException::class.java) {
            AtomicTextFileStore(target, exchange).writeVerified("new") { true }
        }

        assertEquals(backupOf(target).absolutePath, error.backupFile.absolutePath)
        assertTrue(backupOf(target).exists())
        assertEquals("old", backupOf(target).readText())
        assertFalse(target.exists())
    }

    @Test
    fun backupCleanupFailureDoesNotRollBackCommittedContent() {
        val target = temporaryFolder.newFile("theme.json").apply { writeText("old") }
        val exchange = FailingExchange(failBackupDelete = true)

        AtomicTextFileStore(target, exchange).writeVerified("new") { true }

        assertEquals("new", target.readText())
        assertTrue(backupOf(target).exists())
    }

    @Test
    fun deleteRemovesTargetAndAtomicResidue() {
        val target = temporaryFolder.newFile("theme.json").apply { writeText("current") }
        backupOf(target).writeText("old")
        stagingOf(target).writeText("new")

        val removedSize = AtomicTextFileStore(target).delete()

        assertEquals("current".toByteArray().size.toLong(), removedSize)
        assertFalse(target.exists())
        assertFalse(backupOf(target).exists())
        assertFalse(stagingOf(target).exists())
    }

    @Test
    fun deleteWithOnlyBackupCannotResurrectConfiguration() {
        val target = File(temporaryFolder.root, "theme.json")
        backupOf(target).writeText("old")

        val store = AtomicTextFileStore(target)
        val removedSize = store.delete()
        store.recoverInterruptedCommit()

        assertEquals("old".toByteArray().size.toLong(), removedSize)
        assertFalse(target.exists())
        assertFalse(backupOf(target).exists())
        assertFalse(stagingOf(target).exists())
    }

    @Test
    fun backupDeleteFailureKeepsCommittedTarget() {
        val target = temporaryFolder.newFile("theme.json").apply { writeText("current") }
        backupOf(target).writeText("old")
        val exchange = FailingExchange(failBackupDelete = true)

        assertThrows(IOException::class.java) {
            AtomicTextFileStore(target, exchange).delete()
        }

        assertEquals("current", target.readText())
        assertTrue(backupOf(target).exists())
    }

    @Test
    fun separateInstancesSerializeWritesToSameTarget() {
        val target = temporaryFolder.newFile("theme.json").apply { writeText("old") }
        val firstInstallStarted = CountDownLatch(1)
        val allowFirstInstall = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val exchange = BlockingExchange(firstInstallStarted, allowFirstInstall)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())

        val first = Thread {
            runCatching {
                AtomicTextFileStore(target, exchange).writeVerified("first") { true }
            }.exceptionOrNull()?.let(failures::add)
        }
        val second = Thread {
            secondStarted.countDown()
            runCatching {
                AtomicTextFileStore(target, exchange).writeVerified("second") { true }
            }.exceptionOrNull()?.let(failures::add)
        }

        first.start()
        assertTrue(firstInstallStarted.await(5, TimeUnit.SECONDS))
        second.start()
        assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
        allowFirstInstall.countDown()
        first.join(5_000)
        second.join(5_000)

        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertTrue(failures.toString(), failures.isEmpty())
        assertEquals("second", target.readText())
    }

    private fun backupOf(target: File) = File(target.parentFile, ".${target.name}.backup")

    private fun stagingOf(target: File) = File(target.parentFile, ".${target.name}.staging")

    private class FailingExchange(
        private var failMoveToTargetCount: Int = 0,
        private val failBackupDelete: Boolean = false
    ) : FileExchange {
        override fun move(source: File, target: File) {
            if (target.name == "theme.json" && failMoveToTargetCount > 0) {
                failMoveToTargetCount--
                throw IOException("injected move failure")
            }
            NioFileExchange.move(source, target)
        }

        override fun delete(file: File) {
            if (failBackupDelete && file.name.endsWith(".backup")) {
                throw IOException("injected delete failure")
            }
            NioFileExchange.delete(file)
        }
    }

    private class BlockingExchange(
        private val firstInstallStarted: CountDownLatch,
        private val allowFirstInstall: CountDownLatch
    ) : FileExchange {
        private val blocked = AtomicBoolean(false)

        override fun move(source: File, target: File) {
            if (source.name.endsWith(".staging") &&
                target.name == "theme.json" &&
                blocked.compareAndSet(false, true)
            ) {
                firstInstallStarted.countDown()
                check(allowFirstInstall.await(5, TimeUnit.SECONDS))
            }
            NioFileExchange.move(source, target)
        }

        override fun delete(file: File) {
            NioFileExchange.delete(file)
        }
    }
}
