package io.legado.app.help.config

import org.junit.Assert.assertArrayEquals
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

class AtomicByteFileStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun installFailureRestoresOldBinaryContent() {
        val oldBytes = byteArrayOf(0, 1, 0x80.toByte(), 0xff.toByte())
        val newBytes = byteArrayOf(9, 8, 7, 6)
        val target = temporaryFolder.newFile("asset.bin").apply { writeBytes(oldBytes) }
        val exchange = FailingExchange(target.name, failMoveToTargetCount = 1)

        assertThrows(IOException::class.java) {
            AtomicByteFileStore(target, exchange).writeVerified(newBytes) { true }
        }

        assertArrayEquals(oldBytes, target.readBytes())
        assertFalse(backupOf(target).exists())
        assertFalse(stagingOf(target).exists())
    }

    @Test
    fun restoreFailurePreservesBinaryBackup() {
        val oldBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 0xff.toByte())
        val target = temporaryFolder.newFile("asset.bin").apply { writeBytes(oldBytes) }
        val exchange = FailingExchange(target.name, failMoveToTargetCount = 2)

        val error = assertThrows(AtomicFileRestoreException::class.java) {
            AtomicByteFileStore(target, exchange).writeVerified(byteArrayOf(4, 3, 2, 1)) { true }
        }

        assertEquals(backupOf(target).absolutePath, error.backupFile.absolutePath)
        assertTrue(backupOf(target).exists())
        assertArrayEquals(oldBytes, backupOf(target).readBytes())
        assertFalse(target.exists())
        assertFalse(stagingOf(target).exists())
    }

    @Test
    fun verificationFailureKeepsOldBinaryContent() {
        val oldBytes = byteArrayOf(0, 0xff.toByte(), 0, 0x7f)
        val rejectedBytes = byteArrayOf(5, 4, 3, 2, 1)
        val target = temporaryFolder.newFile("asset.bin").apply { writeBytes(oldBytes) }

        assertThrows(IllegalStateException::class.java) {
            AtomicByteFileStore(target).writeVerified(rejectedBytes) { staging ->
                assertArrayEquals(rejectedBytes, staging.readBytes())
                false
            }
        }

        assertArrayEquals(oldBytes, target.readBytes())
        assertFalse(backupOf(target).exists())
        assertFalse(stagingOf(target).exists())
    }

    @Test
    fun interruptedCommitWithOnlyBackupRestoresBinaryTarget() {
        val oldBytes = byteArrayOf(0x13, 0x37, 0, 0xff.toByte())
        val target = File(temporaryFolder.root, "asset.bin")
        backupOf(target).writeBytes(oldBytes)

        AtomicByteFileStore(target).recoverInterruptedCommit()

        assertArrayEquals(oldBytes, target.readBytes())
        assertFalse(backupOf(target).exists())
        assertFalse(stagingOf(target).exists())
    }

    @Test
    fun separateInstancesSerializeWritesToSameBinaryTarget() {
        val target = temporaryFolder.newFile("asset.bin").apply { writeBytes(byteArrayOf(0)) }
        val firstBytes = byteArrayOf(1, 0xff.toByte(), 2)
        val secondBytes = byteArrayOf(3, 0, 4)
        val firstInstallStarted = CountDownLatch(1)
        val allowFirstInstall = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val exchange = BlockingExchange(
            targetName = target.name,
            firstInstallStarted = firstInstallStarted,
            allowFirstInstall = allowFirstInstall
        )
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())

        val first = Thread {
            runCatching {
                AtomicByteFileStore(target, exchange).writeVerified(firstBytes) { true }
            }.exceptionOrNull()?.let(failures::add)
        }
        val second = Thread {
            secondStarted.countDown()
            runCatching {
                AtomicByteFileStore(target, exchange).writeVerified(secondBytes) { true }
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
        assertArrayEquals(secondBytes, target.readBytes())
        assertFalse(backupOf(target).exists())
        assertFalse(stagingOf(target).exists())
    }

    private fun backupOf(target: File) = File(target.parentFile, ".${target.name}.backup")

    private fun stagingOf(target: File) = File(target.parentFile, ".${target.name}.staging")

    private class FailingExchange(
        private val targetName: String,
        private var failMoveToTargetCount: Int
    ) : FileExchange {
        override fun move(source: File, target: File) {
            if (target.name == targetName && failMoveToTargetCount > 0) {
                failMoveToTargetCount--
                throw IOException("injected move failure")
            }
            NioFileExchange.move(source, target)
        }

        override fun delete(file: File) {
            NioFileExchange.delete(file)
        }
    }

    private class BlockingExchange(
        private val targetName: String,
        private val firstInstallStarted: CountDownLatch,
        private val allowFirstInstall: CountDownLatch
    ) : FileExchange {
        private val blocked = AtomicBoolean(false)

        override fun move(source: File, target: File) {
            if (source.name.endsWith(".staging") &&
                target.name == targetName &&
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
