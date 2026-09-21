package io.legado.app.help.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
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

    @Test
    fun coldCacheReaderWaitsForDirectoryReplacement() {
        assertReaderWaitsForDirectoryReplacement(warmCache = false)
    }

    @Test
    fun warmCacheReaderWaitsForDirectoryReplacement() {
        assertReaderWaitsForDirectoryReplacement(warmCache = true)
    }

    @Test
    fun invalidationReloadsReplacementWithSameFileStamp() {
        val root = createTempDirectory("advanced-title-cache-").toFile()
        try {
            val file = File(root, "title.json").apply { writeText("old") }
            val fixedStamp = 1_700_000_000_000L
            assertTrue(file.setLastModified(fixedStamp))
            val actualStamp = file.lastModified()
            val cache = AdvancedTitleTemplateCache(Any()) { it.readText() }

            assertEquals("old", cache.read("active", file))
            file.writeText("new")
            assertTrue(file.setLastModified(actualStamp))
            assertEquals(actualStamp, file.lastModified())
            assertEquals("old", cache.read("active", file))

            cache.invalidate()

            assertEquals("new", cache.read("active", file))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun packageReadGateWaitsForDirectoryReplacement() {
        val root = createTempDirectory("advanced-title-read-gate-").toFile()
        val replacementGapReached = CountDownLatch(1)
        val allowReplacement = CountDownLatch(1)
        val readerStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val target = directory(root, "target", "old")
            val staging = directory(root, "staging", "new")
            val backup = File(root, "backup")
            val mutationLock = Any()
            val readGate = AdvancedTitlePackageReadGate(mutationLock)
            val exchange = BlockingAfterFirstMoveExchange(
                replacementGapReached = replacementGapReached,
                allowReplacement = allowReplacement
            )
            val writer = executor.submit {
                synchronized(mutationLock) {
                    BubbleDirectoryTransaction(exchange).install(
                        target,
                        staging,
                        backup
                    ) { Unit }
                }
            }

            assertTrue(replacementGapReached.await(5, TimeUnit.SECONDS))
            val reader = executor.submit<String> {
                readerStarted.countDown()
                readGate.read { File(target, "value.txt").readText() }
            }
            assertTrue(readerStarted.await(5, TimeUnit.SECONDS))
            try {
                reader.get(200, TimeUnit.MILLISECONDS)
                throw AssertionError("package reader observed the directory replacement gap")
            } catch (_: TimeoutException) {
                // Public package reads must share the directory mutation lock.
            } finally {
                allowReplacement.countDown()
            }

            writer.get(5, TimeUnit.SECONDS)
            assertEquals("new", reader.get(5, TimeUnit.SECONDS))
        } finally {
            allowReplacement.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            root.deleteRecursively()
        }
    }

    @Test
    fun failedRestoreKeepsExistingDirectoryAndWarmCache() {
        val root = createTempDirectory("advanced-title-restore-failure-").toFile()
        try {
            val source = packageDirectory(root, "source", "new")
            val target = packageDirectory(root, "target", "old")
            val titleFile = File(target, "title.json")
            val mutationLock = Any()
            val cache = AdvancedTitleTemplateCache(mutationLock) { it.readText() }
            var invalidations = 0
            assertEquals("old", cache.read("active", titleFile))
            val restorer = AdvancedTitleDirectoryRestorer(
                mutationLock = mutationLock,
                expectedTargetParent = root,
                verifyInstalledRoot = { check(it.isDirectory) },
                invalidateCache = {
                    invalidations++
                    cache.invalidate()
                },
                transactionFactory = {
                    BubbleDirectoryTransaction(FailingMoveExchange(failMoveNumber = 2))
                }
            )

            expectIOException { restorer.restore(source, target) }

            assertEquals(0, invalidations)
            assertEquals("old", titleFile.readText())
            assertEquals("old", cache.read("active", titleFile))
            assertEquals("new", File(source, "title.json").readText())
            assertFalse(hasRestoreArtifact(root, target.name))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptedRestoreSourceRollsBackWithoutInvalidatingWarmCache() {
        val root = createTempDirectory("advanced-title-corrupt-restore-").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            packageDirectory(source, "broken", "new")
            val target = File(root, "target").apply { mkdirs() }
            val current = packageDirectory(target, "current", "old")
            val titleFile = File(current, "title.json")
            val mutationLock = Any()
            val cache = AdvancedTitleTemplateCache(mutationLock) { it.readText() }
            var invalidations = 0
            assertEquals("old", cache.read("active", titleFile))
            val rootVerifier = AdvancedTitlePackagesRootVerifier(maxPackages = 64) { packageDir ->
                require(File(packageDir, "verified.marker").isFile) {
                    "injected corrupt package"
                }
            }
            val restorer = AdvancedTitleDirectoryRestorer(
                mutationLock = mutationLock,
                expectedTargetParent = root,
                verifyInstalledRoot = rootVerifier::verify,
                invalidateCache = {
                    invalidations++
                    cache.invalidate()
                }
            )

            expectIllegalArgumentException { restorer.restore(source, target) }

            assertEquals(0, invalidations)
            assertEquals("old", titleFile.readText())
            assertEquals("old", cache.read("active", titleFile))
            assertEquals("new", File(source, "broken/title.json").readText())
            assertFalse(hasRestoreArtifact(root, target.name))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedTransactionRecoveryKeepsItsOnlyBackup() {
        val root = createTempDirectory("advanced-title-restore-backup-").toFile()
        try {
            val source = packageDirectory(root, "source", "new")
            val target = packageDirectory(root, "target", "old")
            var invalidations = 0
            val restorer = AdvancedTitleDirectoryRestorer(
                mutationLock = Any(),
                expectedTargetParent = root,
                verifyInstalledRoot = { check(it.isDirectory) },
                invalidateCache = { invalidations++ },
                transactionFactory = {
                    BubbleDirectoryTransaction(FailingMovesExchange(setOf(2, 3)))
                }
            )

            val error = expectIOExceptionResult { restorer.restore(source, target) }

            assertTrue(error is BubbleDirectoryRestoreException)
            val backup = (error as BubbleDirectoryRestoreException).backupDir
            assertTrue(backup.isDirectory)
            assertEquals("old", File(backup, "title.json").readText())
            assertFalse(target.exists())
            assertEquals(0, invalidations)
            assertTrue(hasRestoreArtifact(root, target.name))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun successfulRestoreRemovesOnlyMatchingStaleRootArtifacts() {
        val root = createTempDirectory("advanced-title-stale-restore-").toFile()
        try {
            val source = packageDirectory(root, "source", "new")
            val target = packageDirectory(root, "target", "old")
            val staleStaging = directory(
                root,
                ".${target.name}.staging-${java.util.UUID.randomUUID()}",
                "stale"
            )
            val staleBackup = directory(
                root,
                ".${target.name}.backup-${java.util.UUID.randomUUID()}",
                "stale"
            )
            val unrelated = directory(
                root,
                ".another.backup-${java.util.UUID.randomUUID()}",
                "keep"
            )
            val restorer = AdvancedTitleDirectoryRestorer(
                mutationLock = Any(),
                expectedTargetParent = root,
                verifyInstalledRoot = { check(it.isDirectory) },
                invalidateCache = {}
            )

            restorer.restore(source, target)

            assertEquals("new", File(target, "title.json").readText())
            assertFalse(staleStaging.exists())
            assertFalse(staleBackup.exists())
            assertTrue(unrelated.isDirectory)
            assertFalse(hasRestoreArtifact(root, target.name))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun partialCleanupFailureAfterCommitDoesNotRemoveValidatedTarget() {
        val root = createTempDirectory("advanced-title-partial-cleanup-").toFile()
        try {
            val source = packageDirectory(root, "source", "new")
            val target = File(root, "target")
            val staleBackup = directory(
                root,
                ".${target.name}.backup-${java.util.UUID.randomUUID()}",
                "only-old-copy"
            )
            var invalidations = 0
            var cleanupReports = 0
            val restorer = AdvancedTitleDirectoryRestorer(
                mutationLock = Any(),
                expectedTargetParent = root,
                verifyInstalledRoot = { check(it.isDirectory) },
                invalidateCache = { invalidations++ },
                cleanupArtifacts = {
                    assertTrue(staleBackup.deleteRecursively())
                    false
                },
                reportCleanupFailure = { cleanupReports++ }
            )

            restorer.restore(source, target)

            assertEquals("new", File(target, "title.json").readText())
            assertFalse(staleBackup.exists())
            assertEquals(1, invalidations)
            assertEquals(1, cleanupReports)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun staleArtifactCleanupDoesNotFollowSymbolicLinksToTarget() {
        val root = createTempDirectory("advanced-title-cleanup-symlink-").toFile()
        val target = directory(root, "target", "valid")
        val link = File(
            root,
            ".${target.name}.backup-${java.util.UUID.randomUUID()}"
        )
        try {
            val linked = runCatching {
                java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
            }.isSuccess
            if (!linked) return

            assertFalse(AdvancedTitleDirectoryRestorer.cleanupRestoreArtifacts(target))
            assertEquals("valid", File(target, "value.txt").readText())
            assertTrue(java.nio.file.Files.isSymbolicLink(link.toPath()))
        } finally {
            runCatching { java.nio.file.Files.deleteIfExists(link.toPath()) }
            root.deleteRecursively()
        }
    }

    @Test
    fun rootVerifierRejectsVisibleFilesAndPackageOverflow() {
        val root = createTempDirectory("advanced-title-root-verifier-").toFile()
        try {
            File(root, ".ignored").writeText("ignored")
            AdvancedTitlePackagesRootVerifier(maxPackages = 0) {
                throw AssertionError("hidden entries must not be verified as packages")
            }.verify(root)

            File(root, "visible.json").writeText("invalid")
            expectIllegalArgumentException {
                AdvancedTitlePackagesRootVerifier(maxPackages = 1) {}.verify(root)
            }

            File(root, "visible.json").delete()
            File(root, "first").mkdirs()
            File(root, "second").mkdirs()
            var verified = 0
            expectIllegalArgumentException {
                AdvancedTitlePackagesRootVerifier(maxPackages = 1) { verified++ }.verify(root)
            }
            assertEquals(0, verified)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun strictSnapshotCopyIncludesNestedAndEmptyDirectories() {
        val root = createTempDirectory("advanced-title-snapshot-copy-").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "package/title.json").apply {
                parentFile?.mkdirs()
                writeText("payload")
            }
            File(source, "package/images/empty").mkdirs()
            val snapshot = File(root, "snapshot")

            AdvancedTitleDirectoryTree.copyStrict(source, snapshot)

            assertEquals("payload", File(snapshot, "package/title.json").readText())
            assertTrue(File(snapshot, "package/images/empty").isDirectory)
            expectIllegalArgumentException {
                AdvancedTitleDirectoryTree.copyStrict(source, File(source, "nested-snapshot"))
            }
            File(snapshot, "package/title.json").writeText("tamper!")
            expectIllegalStateException {
                AdvancedTitleDirectoryTree.verifyEquivalent(source, snapshot)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun semanticallyInvalidOldRootCanBeSnapshottedAndRolledBack() {
        val root = createTempDirectory("advanced-title-invalid-snapshot-").toFile()
        try {
            val target = File(root, "target").apply { mkdirs() }
            File(target, "visible-junk.txt").writeText("old-junk")
            repeat(65) { index -> File(target, "invalid_$index").mkdirs() }
            val snapshot = File(root, "snapshot")
            AdvancedTitleDirectoryTree.copyStrict(target, snapshot)

            assertTrue(target.deleteRecursively())
            packageDirectory(root, "target", "new-state")
            var invalidations = 0
            val restorer = AdvancedTitleDirectoryRestorer(
                mutationLock = Any(),
                expectedTargetParent = root,
                verifyInstalledRoot = { installed ->
                    AdvancedTitleDirectoryTree.verifyEquivalent(snapshot, installed)
                },
                invalidateCache = { invalidations++ }
            )

            restorer.restore(snapshot, target)

            assertEquals("old-junk", File(target, "visible-junk.txt").readText())
            assertEquals(65, target.listFiles().orEmpty().count { it.isDirectory })
            assertEquals(1, invalidations)
            assertFalse(hasRestoreArtifact(root, target.name))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertReaderWaitsForDirectoryReplacement(warmCache: Boolean) {
        val root = createTempDirectory("advanced-title-concurrent-").toFile()
        val replacementGapReached = CountDownLatch(1)
        val allowReplacement = CountDownLatch(1)
        val readerStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val target = packageDirectory(root, "target", "old")
            val source = packageDirectory(root, "source", "new")
            val titleFile = File(target, "title.json")
            val mutationLock = Any()
            val cache = AdvancedTitleTemplateCache(mutationLock) { it.readText() }
            val invalidations = AtomicInteger()
            if (warmCache) assertEquals("old", cache.read("active", titleFile))
            val exchange = BlockingAfterFirstMoveExchange(
                replacementGapReached = replacementGapReached,
                allowReplacement = allowReplacement
            )
            val restorer = AdvancedTitleDirectoryRestorer(
                mutationLock = mutationLock,
                expectedTargetParent = root,
                verifyInstalledRoot = { check(it.isDirectory) },
                invalidateCache = {
                    check(Thread.holdsLock(mutationLock))
                    invalidations.incrementAndGet()
                    cache.invalidate()
                },
                transactionFactory = { BubbleDirectoryTransaction(exchange) }
            )
            val writer = executor.submit { restorer.restore(source, target) }

            assertTrue(replacementGapReached.await(5, TimeUnit.SECONDS))
            val reader = executor.submit<String> {
                readerStarted.countDown()
                cache.read("active", titleFile) ?: "builtin"
            }
            assertTrue(readerStarted.await(5, TimeUnit.SECONDS))
            try {
                reader.get(200, TimeUnit.MILLISECONDS)
                throw AssertionError("reader observed the directory replacement gap")
            } catch (_: TimeoutException) {
                // The reader must wait for the writer to install and publish the new package.
            } finally {
                allowReplacement.countDown()
            }

            writer.get(5, TimeUnit.SECONDS)
            assertEquals("new", reader.get(5, TimeUnit.SECONDS))
            assertEquals(1, invalidations.get())
            assertFalse(hasRestoreArtifact(root, target.name))
        } finally {
            allowReplacement.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            root.deleteRecursively()
        }
    }

    private fun hasRestoreArtifact(root: File, targetName: String): Boolean {
        return root.listFiles().orEmpty().any {
            it.name.startsWith(".$targetName.staging-") ||
                it.name.startsWith(".$targetName.backup-")
        }
    }

    private fun directory(root: File, name: String, value: String): File =
        File(root, name).apply {
            mkdirs()
            File(this, "value.txt").writeText(value)
        }

    private fun packageDirectory(root: File, name: String, value: String): File =
        File(root, name).apply {
            mkdirs()
            File(this, "title.json").writeText(value)
        }

    private fun expectIOException(block: () -> Unit) {
        expectIOExceptionResult(block)
    }

    private fun expectIOExceptionResult(block: () -> Unit): IOException {
        try {
            block()
            throw AssertionError("expected IOException")
        } catch (error: IOException) {
            return error
        }
    }

    private fun expectIllegalArgumentException(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun expectIllegalStateException(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalStateException")
        } catch (_: IllegalStateException) {
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

    private class FailingMovesExchange(
        private val failedMoves: Set<Int>
    ) : FileExchange {
        private var moveCount = 0

        override fun move(source: File, target: File) {
            moveCount++
            if (moveCount in failedMoves) throw IOException("injected move failure")
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

    private class BlockingAfterFirstMoveExchange(
        private val replacementGapReached: CountDownLatch,
        private val allowReplacement: CountDownLatch
    ) : FileExchange {
        private var moveCount = 0

        override fun move(source: File, target: File) {
            moveCount++
            NioFileExchange.move(source, target)
            if (moveCount == 1) {
                replacementGapReached.countDown()
                check(allowReplacement.await(5, TimeUnit.SECONDS))
            }
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
