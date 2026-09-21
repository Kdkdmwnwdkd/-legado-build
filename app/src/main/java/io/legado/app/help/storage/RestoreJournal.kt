package io.legado.app.help.storage

import io.legado.app.constant.AppLog
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.AdvancedTitleDirectoryTree
import io.legado.app.help.config.AdvancedTitlePackageManager
import io.legado.app.help.config.AtomicTextFileStore
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.help.config.NioFileExchange
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.ThemePackageManager
import io.legado.app.help.config.TopBarConfig
import io.legado.app.model.VideoPlay.VIDEO_PREF_NAME
import io.legado.app.model.localBook.epubcore.template.EpubReaderTemplateStore
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import splitties.init.appCtx
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

object RestoreJournal {

    private const val STATUS_APPLYING = "applying"
    private const val STATUS_PENDING = "pending"
    private const val STATUS_CRASHED = "crashed"
    private const val STATUS_ROLLING_BACK = "rolling_back"
    private const val LEGACY_ROLLBACK_OWNER = "legacy-journal-without-generation"
    private const val VALIDATE_TIMEOUT = 10 * 60 * 1000L
    private val stateLock = Any()
    private var preparingRestoreGeneration: String? = null
    private var activeRestoreGeneration: String? = null
    private var rollbackInProgressGeneration: String? = null

    private val rootDir: File
        get() = appCtx.filesDir.getFile("restore_journal")

    private val snapshotDir: File
        get() = rootDir.getFile("snapshot")

    private val stateFile: File
        get() = rootDir.getFile("state.json")

    data class Entry(
        val targetPath: String,
        val snapshotPath: String,
        val directory: Boolean,
        val existed: Boolean
    )

    data class State(
        var status: String,
        val generation: String? = UUID.randomUUID().toString(),
        val startedAt: Long = System.currentTimeMillis(),
        val entries: MutableList<Entry> = arrayListOf()
    )

    fun buildSnapshotTargets(backupPath: String): List<File> {
        val path = File(backupPath)
        val targets = arrayListOf<File>()
        fun addIfBackupExists(fileName: String, targetPath: String) {
            if (File(path, fileName).exists()) {
                targets.add(File(targetPath))
            }
        }
        addIfBackupExists(ThemeConfig.configFileName, ThemeConfig.configFilePath)
        addIfBackupExists(ReadBookConfig.configFileName, ReadBookConfig.configFilePath)
        addIfBackupExists(ReadBookConfig.shareConfigFileName, ReadBookConfig.shareConfigFilePath)
        addIfBackupExists(ReadBookConfig.epubConfigFileName, ReadBookConfig.epubConfigFilePath)
        addIfBackupExists(ReadBookConfig.epubShareConfigFileName, ReadBookConfig.epubShareConfigFilePath)
        addIfBackupExists(EpubReaderTemplateStore.fileName, EpubReaderTemplateStore.filePath)
        if (File(path, DirectLinkUpload.ruleFileName).isFile) {
            DirectLinkUpload.configStorageFile()?.let(targets::add)
        }
        addIfBackupExists("config.xml", sharedPrefsFile("${appCtx.packageName}_preferences").absolutePath)
        addIfBackupExists("videoConfig.xml", sharedPrefsFile(VIDEO_PREF_NAME).absolutePath)
        Restore.backgroundAssetDirNames.forEach { dirName ->
            if (File(path, dirName).isDirectory) {
                targets.add(appCtx.externalFiles.getFile(dirName))
            }
        }
        if (File(path, "themePackages").isDirectory) {
            targets.add(ThemePackageManager.rootDir)
        }
        if (File(path, "navigationBarPackages").isDirectory) {
            targets.add(NavigationBarIconConfig.rootDir)
        }
        if (File(path, "topBarPackages").isDirectory) {
            targets.add(TopBarConfig.rootDir)
        }
        if (File(path, "coverCollections").isDirectory) {
            targets.add(CoverCollectionManager.rootDir)
        }
        if (File(path, Backup.advancedTitlePackagesDirName).isDirectory) {
            targets.add(AdvancedTitlePackageManager.rootDir)
        }
        if (File(path, Backup.bubblePackagesDirName).isDirectory) {
            targets.add(BubblePackageManager.rootDir)
        }
        if (File(path, io.legado.app.help.book.highlight.HighlightRules.BACKUP_DIR).isDirectory) {
            targets.add(io.legado.app.help.book.highlight.HighlightRules.store.directory)
        }
        if (File(path, io.legado.app.help.reader.ReaderAssets.BACKUP_DIR).isDirectory) {
            targets.add(io.legado.app.help.reader.ReaderAssets.store.directory)
        }
        return targets.distinctBy { it.absolutePath }
    }

    private fun sharedPrefsFile(name: String): File {
        return File(appCtx.applicationInfo.dataDir, "shared_prefs/$name.xml")
    }

    fun begin(targets: List<File>): String {
        val state = State(status = STATUS_APPLYING)
        val generation = requireNotNull(state.generation)
        val parent = requireNotNull(rootDir.parentFile).apply { mkdirs() }.canonicalFile
        val preparingRoot = File(parent, ".restore_journal.preparing-$generation")
        val preparingSnapshots = File(preparingRoot, "snapshot")
        var detached: File? = null
        try {
            synchronized(stateLock) {
                check(preparingRestoreGeneration == null &&
                    activeRestoreGeneration == null &&
                    rollbackInProgressGeneration == null
                ) {
                    "Restore journal is already owned in this process"
                }
                val current = readStateUnlocked()
                require(current == null || current.status == STATUS_PENDING) {
                    "Previous restore journal is not stable: ${current?.status}"
                }
                preparingRestoreGeneration = generation
            }
            check(preparingSnapshots.mkdirs()) { "Unable to create restore journal staging" }
            targets.forEachIndexed { index, target ->
                val preparingSnapshot = File(preparingSnapshots, index.toString())
                val publishedSnapshot = snapshotDir.getFile(index.toString())
                val entry = if (isAdvancedTitlePackagesTarget(target)) {
                    val existed = AdvancedTitlePackageManager.snapshotPackagesTo(preparingSnapshot)
                    Entry(target.absolutePath, publishedSnapshot.absolutePath, true, existed)
                } else if (DirectLinkUpload.isConfigStorageFile(target)) {
                    val existed = DirectLinkUpload.snapshotConfigTo(preparingSnapshot).getOrThrow()
                    Entry(target.absolutePath, publishedSnapshot.absolutePath, false, existed)
                } else if (target.exists()) {
                    if (target.isDirectory) {
                        AdvancedTitleDirectoryTree.copyStrict(target, preparingSnapshot)
                    } else {
                        preparingSnapshot.parentFile?.mkdirs()
                        target.copyTo(preparingSnapshot, overwrite = true)
                    }
                    Entry(
                        target.absolutePath,
                        publishedSnapshot.absolutePath,
                        target.isDirectory,
                        true
                    )
                } else {
                    Entry(
                        target.absolutePath,
                        publishedSnapshot.absolutePath,
                        target.isDirectory,
                        false
                    )
                }
                state.entries.add(entry)
            }
            writeStateFile(File(preparingRoot, "state.json"), state)
            check(restoreJournalPreparingStateIsComplete(preparingRoot, rootDir, state)) {
                "Restore journal staging is incomplete"
            }
            synchronized(stateLock) {
                check(preparingRestoreGeneration == generation &&
                    activeRestoreGeneration == null &&
                    rollbackInProgressGeneration == null
                ) {
                    "Restore journal is already owned in this process"
                }
                val current = readStateUnlocked(recoverInterruptedPublish = false)
                require(current == null || current.status == STATUS_PENDING) {
                    "Previous restore journal is not stable: ${current?.status}"
                }
                detached = detachJournalUnlocked("replaced")
                try {
                    NioFileExchange.move(preparingRoot, rootDir)
                } catch (publishError: Throwable) {
                    detached?.takeIf { it.exists() }?.let { old ->
                        runCatching { NioFileExchange.move(old, rootDir) }
                            .onFailure { publishError.addSuppressed(it) }
                    }
                    throw publishError
                }
                preparingRestoreGeneration = null
                activeRestoreGeneration = generation
            }
            deleteDetachedJournal(detached)
            return generation
        } finally {
            synchronized(stateLock) {
                if (preparingRestoreGeneration == generation) {
                    preparingRestoreGeneration = null
                }
            }
            if (preparingRoot.exists()) deleteDetachedJournal(preparingRoot)
        }
    }

    fun markPendingValidation(generation: String) {
        try {
            val state = checkNotNull(readState()) { "Restore journal disappeared" }
            require(state.generation == generation) { "Restore journal generation changed" }
            check(updateStatusIfCurrent(state, STATUS_APPLYING, STATUS_PENDING)) {
                "Restore journal changed before pending validation"
            }
        } finally {
            synchronized(stateLock) {
                if (activeRestoreGeneration == generation) activeRestoreGeneration = null
            }
        }
    }

    fun markStableIfPending() {
        val state = readState() ?: return
        if (state.status == STATUS_PENDING) {
            clearIfCurrent(state, setOf(STATUS_PENDING))
        }
    }

    fun markCrash() {
        val state = readState() ?: return
        val now = System.currentTimeMillis()
        if (state.status == STATUS_APPLYING || now - state.startedAt <= VALIDATE_TIMEOUT) {
            updateStatusIfCurrent(state, state.status, STATUS_CRASHED)
        }
    }

    fun recoverIfNeeded(reason: String) {
        val state = readState() ?: return
        when (state.status) {
            STATUS_APPLYING, STATUS_CRASHED, STATUS_ROLLING_BACK -> rollback(state, reason)
            STATUS_PENDING -> {
                if (System.currentTimeMillis() - state.startedAt > VALIDATE_TIMEOUT) {
                    clearIfCurrent(state, setOf(STATUS_PENDING))
                }
            }
        }
    }

    fun rollbackNow(reason: String, activeGeneration: String? = null) {
        try {
            readState()?.let {
                rollback(it, reason, activeGeneration)
            }
        } finally {
            if (activeGeneration != null) {
                synchronized(stateLock) {
                    if (activeRestoreGeneration == activeGeneration) {
                        activeRestoreGeneration = null
                    }
                }
            }
        }
    }

    private fun rollback(
        state: State,
        reason: String,
        activeGeneration: String? = null
    ) {
        val claimed = claimRollback(state, activeGeneration) ?: return
        val rollbackOwner = rollbackOwnerKey(claimed.generation)
        try {
            val failures = arrayListOf<Throwable>()
            claimed.entries.asReversed().forEach { entry ->
                kotlin.runCatching {
                    val target = File(entry.targetPath)
                    if (isAdvancedTitlePackagesTarget(target)) {
                        if (entry.existed) {
                            val snapshot = File(entry.snapshotPath)
                            require(snapshot.isDirectory) {
                                "Advanced title rollback snapshot is missing: ${entry.snapshotPath}"
                            }
                            AdvancedTitlePackageManager.restorePackagesSnapshotFrom(snapshot)
                        } else {
                            AdvancedTitlePackageManager.clearPackagesForRestoreRollback()
                        }
                        return@runCatching
                    }
                    if (DirectLinkUpload.isConfigStorageFile(target)) {
                        DirectLinkUpload.restoreConfigSnapshot(
                            snapshot = File(entry.snapshotPath).takeIf { entry.existed },
                            existed = entry.existed
                        ).getOrThrow()
                        return@runCatching
                    }
                    if (entry.existed) {
                        val snapshot = File(entry.snapshotPath)
                        require(snapshot.exists()) {
                            "恢复备份回滚快照不存在: ${entry.snapshotPath}"
                        }
                        if (entry.directory) {
                            Restore.restorePackageDirectory(snapshot, target)
                        } else {
                            Restore.restoreFile(snapshot, target)
                        }
                    } else if (target.exists()) {
                        FileUtils.delete(target, deleteRootDir = true)
                        check(!target.exists()) {
                            "Unable to remove new restore target: ${target.absolutePath}"
                        }
                    }
                }.onFailure { error ->
                    failures.add(error)
                    AppLog.put(
                        "恢复备份回滚条目失败: ${entry.targetPath}\n" +
                            (error.localizedMessage ?: "未知错误"),
                        error
                    )
                }
            }
            io.legado.app.help.book.highlight.HighlightRules.store.invalidate()
            io.legado.app.help.reader.ReaderAssets.store.invalidate()
            if (failures.isEmpty()) {
                clearIfCurrent(claimed, setOf(STATUS_ROLLING_BACK))
                AppLog.put("恢复备份已回滚: $reason")
            } else {
                kotlin.runCatching {
                    updateStatusIfCurrent(claimed, STATUS_ROLLING_BACK, STATUS_CRASHED)
                }
                    .onFailure { AppLog.put("保留恢复回滚日志失败", it) }
                AppLog.put("恢复备份回滚未完成，将在下次启动重试: $reason")
            }
        } finally {
            synchronized(stateLock) {
                if (rollbackInProgressGeneration == rollbackOwner) {
                    rollbackInProgressGeneration = null
                }
                if (activeGeneration != null && activeRestoreGeneration == activeGeneration) {
                    activeRestoreGeneration = null
                }
            }
        }
    }

    private fun isAdvancedTitlePackagesTarget(target: File): Boolean {
        return runCatching {
            target.canonicalFile == AdvancedTitlePackageManager.rootDir.canonicalFile
        }.getOrDefault(false)
    }

    private fun readState(): State? = synchronized(stateLock) {
        runCatching { readStateUnlocked() }
            .onFailure {
                AppLog.put("Restore journal state is unreadable; journal retained", it)
            }
            .getOrNull()
    }

    private fun readStateUnlocked(recoverInterruptedPublish: Boolean = true): State? {
        if (recoverInterruptedPublish) recoverInterruptedPublishUnlocked()
        val store = AtomicTextFileStore(stateFile)
        store.recoverInterruptedCommit()
        if (!stateFile.isFile) return null
        return GSON.fromJsonObject<State>(stateFile.readText()).getOrElse { error ->
            throw java.io.IOException("Unable to parse restore journal state", error)
        }
    }

    private fun writeStateUnlocked(state: State) {
        writeStateFile(stateFile, state)
    }

    private fun writeStateFile(file: File, state: State) {
        val json = GSON.toJson(state)
        AtomicTextFileStore(file).writeVerified(json) { staged ->
            GSON.fromJsonObject<State>(staged).getOrNull() == state
        }
    }

    private fun updateStatusIfCurrent(
        observed: State,
        expectedStatus: String,
        newStatus: String
    ): Boolean {
        return synchronized(stateLock) {
            val current = readStateUnlocked()
            if (!restoreJournalStateMatches(
                    current,
                    observed.generation,
                    setOf(expectedStatus)
                )
            ) {
                return@synchronized false
            }
            current!!.status = newStatus
            writeStateUnlocked(current)
            true
        }
    }

    private fun claimRollback(
        observed: State,
        activeGeneration: String?
    ): State? {
        return synchronized(stateLock) {
            val activeOwner = activeRestoreGeneration
            if (!canClaimRestoreRollback(
                    activeOwner,
                    rollbackInProgressGeneration,
                    activeGeneration
                )
            ) {
                return@synchronized null
            }
            val current = readStateUnlocked()
            val expectedGeneration = activeGeneration ?: observed.generation
            if (!restoreJournalStateMatches(
                    current,
                    expectedGeneration,
                    setOf(STATUS_APPLYING, STATUS_CRASHED, STATUS_ROLLING_BACK)
                )
            ) {
                return@synchronized null
            }
            val generation = current!!.generation
            rollbackInProgressGeneration = rollbackOwnerKey(generation)
            if (activeOwner == activeGeneration) activeRestoreGeneration = null
            try {
                if (current.status != STATUS_ROLLING_BACK) {
                    current.status = STATUS_ROLLING_BACK
                    writeStateUnlocked(current)
                }
            } catch (error: Throwable) {
                rollbackInProgressGeneration = null
                if (activeOwner != null) activeRestoreGeneration = activeOwner
                throw error
            }
            current
        }
    }

    private fun clearIfCurrent(observed: State, expectedStatuses: Set<String>): Boolean {
        val detached = synchronized(stateLock) {
            val current = readStateUnlocked()
            if (!restoreJournalStateMatches(current, observed.generation, expectedStatuses)) {
                return@synchronized null
            }
            detachJournalUnlocked()
        } ?: return false
        deleteDetachedJournal(detached)
        return true
    }

    private fun detachJournalUnlocked(kind: String = "deleted"): File? {
        if (!rootDir.exists()) return null
        val parent = requireNotNull(rootDir.parentFile).apply { mkdirs() }.canonicalFile
        val source = rootDir.canonicalFile
        require(source.parentFile == parent) { "Restore journal escaped its parent" }
        val detached = File(parent, ".restore_journal.$kind-${UUID.randomUUID()}")
        NioFileExchange.move(source, detached)
        return detached
    }

    private fun recoverInterruptedPublishUnlocked() {
        if (preparingRestoreGeneration != null) return
        if (rootDir.exists()) return
        val parent = rootDir.parentFile?.takeIf { it.isDirectory } ?: return
        val preparing = parent.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && it.name.startsWith(".restore_journal.preparing-") }
            .sortedByDescending { it.lastModified() }
            .firstOrNull { candidate ->
                val candidateState = runCatching {
                    val file = File(candidate, "state.json")
                    AtomicTextFileStore(file).recoverInterruptedCommit()
                    GSON.fromJsonObject<State>(file.readText()).getOrThrow()
                }.getOrNull()
                candidateState != null &&
                    restoreJournalPreparingStateIsComplete(candidate, rootDir, candidateState)
            }
        if (preparing != null) {
            NioFileExchange.move(preparing, rootDir)
        }
    }

    private fun rollbackOwnerKey(generation: String?): String {
        return generation ?: LEGACY_ROLLBACK_OWNER
    }

    private fun deleteDetachedJournal(detached: File?) {
        if (detached == null) return
        val error = runCatching {
            check(detached.deleteRecursively() || !detached.exists()) {
                "Unable to delete ${detached.absolutePath}"
            }
        }.exceptionOrNull()
        if (error != null) {
            runCatching { AppLog.put("Unable to delete detached restore journal", error) }
        }
    }

}

internal fun restoreJournalStateMatches(
    current: RestoreJournal.State?,
    expectedGeneration: String?,
    expectedStatuses: Set<String>
): Boolean {
    return current != null &&
        current.generation == expectedGeneration &&
        current.status in expectedStatuses
}

internal fun canClaimRestoreRollback(
    activeRestoreGeneration: String?,
    rollbackInProgressGeneration: String?,
    requestedActiveGeneration: String?
): Boolean {
    return rollbackInProgressGeneration == null &&
        (activeRestoreGeneration == null ||
            activeRestoreGeneration == requestedActiveGeneration)
}

internal fun restoreJournalPreparingStateIsComplete(
    preparingRoot: File,
    publishedRoot: File,
    state: RestoreJournal.State
): Boolean {
    val generation = state.generation ?: return false
    val preparingSnapshots = File(preparingRoot, "snapshot").absoluteFile
    val publishedSnapshots = File(publishedRoot, "snapshot").absoluteFile
    return state.status == "applying" &&
        preparingRoot.name == ".restore_journal.preparing-$generation" &&
        File(preparingRoot, "state.json").isFile &&
        preparingSnapshots.isDirectory &&
        state.entries.withIndex().all { (index, entry) ->
            val expectedPublished = File(publishedSnapshots, index.toString()).absoluteFile
            val preparingSnapshot = File(preparingSnapshots, index.toString())
            val snapshotPath = preparingSnapshot.toPath()
            val snapshotExists = Files.exists(snapshotPath, LinkOption.NOFOLLOW_LINKS)
            val snapshotHasExpectedType = when {
                !entry.existed -> !snapshotExists
                Files.isSymbolicLink(snapshotPath) -> false
                entry.directory -> preparingSnapshot.isDirectory
                else -> preparingSnapshot.isFile
            }
            File(entry.snapshotPath).absoluteFile == expectedPublished &&
                snapshotHasExpectedType
        }
}
