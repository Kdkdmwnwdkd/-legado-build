package io.legado.app.help.config

import androidx.annotation.Keep
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

object AdvancedTitlePackageManager {

    const val BUILTIN_ID = "builtin_default"
    const val MAX_EDITABLE_JSON_BYTES = 2L * 1024L * 1024L
    // Import/apply ceiling. In-app JSON editor stays at 2 MiB (Intent/OOM safety).
    const val MAX_JSON_BYTES = 8L * 1024L * 1024L
    private const val MAX_PACKAGES = 64
    private const val MANIFEST_FILE = "package.json"
    private const val LOTTIE_FILE = "title.json"

    @Keep
    data class Config(
        val id: String,
        val name: String,
        val updatedAt: Long = System.currentTimeMillis(),
        val splitMode: Int? = null,
        val delimiter: String? = null,
        val regex: String? = null,
        val heightFactor: Int? = null
    ) {
        fun splitRuleOrNull(): AdvancedTitleConfig.SplitRule? {
            if (splitMode == null && delimiter == null && regex == null) return null
            return AdvancedTitleConfig.SplitRule(
                mode = if (splitMode == AdvancedTitleConfig.SPLIT_REGEX) {
                    AdvancedTitleConfig.SPLIT_REGEX
                } else {
                    AdvancedTitleConfig.SPLIT_DELIMITER
                },
                delimiter = delimiter ?: " ",
                regex = regex ?: AdvancedTitleConfig.DEFAULT_REGEX
            )
        }

        fun normalizedHeightFactorOrNull(): Int? = heightFactor?.coerceIn(30, 120)
    }

    data class Entry(
        val config: Config,
        val directory: File? = null,
        val isBuiltin: Boolean = false,
        /**
         * A broken local package must remain manageable so the user can delete or repair it
         * from inside the app. It must never be applied automatically.
         */
        val isUsable: Boolean = true
    ) {
        val id: String get() = config.id
        val name: String get() = config.name
        val updatedAt: Long get() = config.updatedAt
    }

    val rootDir: File
        get() = appCtx.externalFiles.getFile("advancedTitlePackages")

    @Volatile
    private var builtinJsonCache: String? = null
    private var legacyOpenTemplateCache: String? = null
    private var legacyOpenTemplateChecked = false
    private val mutationLock = Any()
    private val packageReadGate = AdvancedTitlePackageReadGate(mutationLock)
    private val templateCache = AdvancedTitleTemplateCache(mutationLock, ::readJsonFile)

    fun builtinEntry(): Entry = Entry(
        config = Config(
            id = BUILTIN_ID,
            name = appCtx.getString(R.string.advanced_title_builtin),
            updatedAt = 0L,
            splitMode = AdvancedTitleConfig.SPLIT_DELIMITER,
            delimiter = " ",
            regex = AdvancedTitleConfig.DEFAULT_REGEX,
            heightFactor = AdvancedTitleConfig.DEFAULT_HEIGHT_FACTOR
        ),
        isBuiltin = true
    )

    fun activeId(): String = appCtx.getPrefString(PreferKey.advancedTitlePackage)
        ?.takeIf(::isValidId)
        ?: BUILTIN_ID

    suspend fun loadEntries(): List<Entry> = withContext(IO) {
        synchronized(mutationLock) {
            rootDir.mkdirs()
            AdvancedTitlePackageStorage.cleanupStaleStagingDirectories(rootDir)
            migrateLegacyIfNeeded()
            var local = loadLocalEntries()
            val validIds = local.asSequence()
                .filter { it.isUsable }
                .map { it.id }
                .toSet() + BUILTIN_ID
            if (activeId() !in validIds) {
                val recovery = legacyTemplate()
                    ?.takeIf { runCatching { validateJson(it) }.isSuccess }
                    ?.let { addOrUpdate(appCtx.getString(R.string.advanced_title_migrated), it) }
                appCtx.putPrefString(
                    PreferKey.advancedTitlePackage,
                    recovery?.id ?: BUILTIN_ID
                )
                if (recovery != null) local = loadLocalEntries()
                invalidate()
            }
            listOf(builtinEntry()) + local.sortedWith(
                compareByDescending<Entry> { it.updatedAt }.thenBy { it.name }
            )
        }
    }

    fun currentTemplate(): String? = synchronized(mutationLock) {
        val explicitId = appCtx.getPrefString(PreferKey.advancedTitlePackage)
            ?.takeIf(::isValidId)
        if (explicitId == null) {
            legacyTemplateForOpen() ?: builtinJson()
        } else if (explicitId == BUILTIN_ID) {
            builtinJson()
        } else {
            val file = lottieFile(localDir(explicitId))
            // Selected packages stay on disk; legacy prefs are only considered when no package
            // selection exists and pass the bounded compatibility check above.
            templateCache.read(explicitId, file)
                ?.takeIf(AdvancedTitleConfig::hasRenderableLayers)
                ?: builtinJson()
        }
    }

    fun readTemplate(entry: Entry): String = packageReadGate.read {
        require(entry.isUsable) { appCtx.getString(R.string.advanced_title_invalid_json) }
        readTemplateLocked(entry)
    }

    private fun readTemplateLocked(entry: Entry): String {
        return if (entry.isBuiltin) {
            builtinJson()
        } else {
            val directory = requireNotNull(entry.directory) { "Missing advanced title directory" }
            readJsonFile(lottieFile(directory))
        }
    }

    fun readTemplate(id: String): String = packageReadGate.read {
        if (id == BUILTIN_ID) return@read builtinJson()
        require(isValidId(id)) { "Invalid advanced title id" }
        val parent = rootDir.apply { mkdirs() }.canonicalFile
        val directory = File(parent, id).canonicalFile
        require(directory.parentFile == parent) { "Advanced title directory escaped its root" }
        val config = verifyInstalledDirectory(directory, expectedId = id)
        readTemplateLocked(Entry(config, directory))
    }

    fun templateSize(entry: Entry): Long = packageReadGate.read {
        templateSizeLocked(entry)
    }

    private fun templateSizeLocked(entry: Entry): Long {
        if (entry.isBuiltin) return 0L
        val directory = entry.directory ?: return 0L
        return lottieFile(directory).takeIf { it.isFile }?.length() ?: 0L
    }

    fun isEditable(entry: Entry): Boolean = packageReadGate.read {
        entry.isUsable && !entry.isBuiltin && templateSizeLocked(entry) in 1..MAX_EDITABLE_JSON_BYTES
    }

    fun addOrUpdate(
        name: String,
        json: String,
        oldEntry: Entry? = null,
        splitRule: AdvancedTitleConfig.SplitRule? = oldEntry?.config?.splitRuleOrNull()
            ?: AdvancedTitleConfig.globalRule,
        heightFactor: Int? = oldEntry?.config?.normalizedHeightFactorOrNull()
            ?: AdvancedTitleConfig.heightFactor
    ): Entry =
        synchronized(mutationLock) {
        val normalizedName = normalizeName(name)
        validateJson(json)
        val editableOld = oldEntry?.takeUnless { it.isBuiltin }
        if (editableOld == null) {
            val packageCount = rootDir.listFiles().orEmpty().count {
                it.isDirectory && !it.name.startsWith('.')
            }
            require(packageCount < MAX_PACKAGES) {
                appCtx.getString(R.string.advanced_title_package_limit)
            }
        }
        val id = editableOld?.id ?: "title_${UUID.randomUUID().toString().replace("-", "")}".take(38)
        require(isValidId(id)) { "Invalid advanced title id" }
        val parent = rootDir.apply { mkdirs() }.canonicalFile
        val target = File(parent, id).canonicalFile
        require(target.parentFile == parent) { "Advanced title directory escaped its root" }
        val staging = File(parent, ".$id.staging-${UUID.randomUUID()}")
        val backup = File(parent, ".$id.backup-${UUID.randomUUID()}")
        val config = Config(
            id = id,
            name = normalizedName,
            updatedAt = System.currentTimeMillis(),
            splitMode = splitRule?.mode,
            delimiter = splitRule?.delimiter,
            regex = splitRule?.regex,
            heightFactor = heightFactor?.coerceIn(30, 120)
        )
        try {
            staging.mkdirs()
            File(staging, MANIFEST_FILE).writeText(GSON.toJson(config))
            lottieFile(staging).writeText(json)
            verifyInstalledDirectory(
                directory = staging,
                expectedId = id,
                requireDirectoryIdMatch = false
            )
            val installed = BubbleDirectoryTransaction().install(
                target,
                staging,
                backup
            ) { installedDir ->
                val verified = verifyInstalledDirectory(installedDir, expectedId = id)
                Entry(verified, installedDir)
            }
            invalidate()
            installed
        } finally {
            AdvancedTitlePackageStorage.deleteStagingDirectory(parent, staging)
        }
    }

    fun apply(entry: Entry) = synchronized(mutationLock) {
        require(entry.isUsable) { appCtx.getString(R.string.advanced_title_invalid_json) }
        val json = readTemplate(entry)
        validateJson(json)
        appCtx.putPrefString(PreferKey.advancedTitlePackage, entry.id)
        // Never store full Lottie JSON in SharedPreferences (backup/open-book poison).
        AdvancedTitleConfig.lottieJson = null
        AdvancedTitleConfig.lottiePath = null
        entry.config.splitRuleOrNull()?.let { AdvancedTitleConfig.globalRule = it }
        entry.config.normalizedHeightFactorOrNull()?.let { AdvancedTitleConfig.heightFactor = it }
        invalidate()
    }

    fun delete(entry: Entry) {
        synchronized(mutationLock) {
            if (entry.isBuiltin || entry.id == BUILTIN_ID) return@synchronized
            val parent = rootDir.canonicalFile
            val target = (entry.directory ?: localDir(entry.id)).canonicalFile
            require(target.parentFile == parent) { "Advanced title directory escaped its root" }
            if (target.exists() && !target.deleteRecursively() && target.exists()) {
                throw IOException("Unable to delete advanced title")
            }
            if (activeId() == entry.id) {
                appCtx.putPrefString(PreferKey.advancedTitlePackage, BUILTIN_ID)
                AdvancedTitleConfig.lottieJson = null
                AdvancedTitleConfig.lottiePath = null
                val builtin = builtinEntry().config
                builtin.splitRuleOrNull()?.let { AdvancedTitleConfig.globalRule = it }
                builtin.normalizedHeightFactorOrNull()?.let {
                    AdvancedTitleConfig.heightFactor = it
                }
            }
            invalidate()
        }
    }

    fun validateJson(json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty()) { appCtx.getString(R.string.advanced_title_invalid_json) }
        require(bytes.size <= MAX_JSON_BYTES) { appCtx.getString(R.string.advanced_title_too_large) }
        require(AdvancedTitleConfig.isValidLottieJson(json)) {
            appCtx.getString(R.string.advanced_title_invalid_json)
        }
    }

    fun validateEditableJson(json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty()) { appCtx.getString(R.string.advanced_title_invalid_json) }
        require(bytes.size <= MAX_EDITABLE_JSON_BYTES) {
            appCtx.getString(R.string.large_config_read_only)
        }
        require(AdvancedTitleConfig.isValidLottieJson(json)) {
            appCtx.getString(R.string.advanced_title_invalid_json)
        }
    }

    fun utf8SizeUpTo(value: String, limit: Long): Long {
        var size = 0L
        var index = 0
        while (index < value.length) {
            val char = value[index]
            size += when {
                char.code <= 0x7f -> 1L
                char.code <= 0x7ff -> 2L
                Character.isHighSurrogate(char) &&
                    index + 1 < value.length &&
                    Character.isLowSurrogate(value[index + 1]) -> {
                    index++
                    4L
                }
                else -> 3L
            }
            if (size > limit) return limit + 1L
            index++
        }
        return size
    }

    fun invalidate() {
        templateCache.invalidate()
        synchronized(mutationLock) {
            legacyOpenTemplateCache = null
            legacyOpenTemplateChecked = false
        }
    }

    internal fun restorePackagesFrom(sourceDir: File) {
        packageRestorer().restore(sourceDir, rootDir)
    }

    internal fun exportPackagesTo(exportDir: File): Boolean = synchronized(mutationLock) {
        val source = AdvancedTitleDirectoryTree.resolveDirectChild(
            rootDir,
            expectedPackagesParent()
        )
        AdvancedTitleDirectoryTree.copyVisibleDirectoriesStrict(source, exportDir)
    }

    internal fun snapshotPackagesTo(snapshotDir: File): Boolean = synchronized(mutationLock) {
        val source = AdvancedTitleDirectoryTree.resolveDirectChild(
            rootDir,
            expectedPackagesParent()
        )
        val snapshot = snapshotDir.canonicalFile
        AdvancedTitleDirectoryTree.requireDisjoint(source, snapshot)
        require(!snapshot.exists()) { "Advanced title snapshot target already exists" }
        if (!source.exists()) return@synchronized false
        require(source.isDirectory) { "Advanced title package root is invalid" }
        try {
            AdvancedTitleDirectoryTree.copyStrict(source, snapshot)
            true
        } catch (error: Exception) {
            snapshot.deleteRecursively()
            throw error
        }
    }

    internal fun restorePackagesSnapshotFrom(snapshotDir: File) = synchronized(mutationLock) {
        val snapshot = snapshotDir.canonicalFile
        AdvancedTitleDirectoryRestorer(
            mutationLock = mutationLock,
            expectedTargetParent = expectedPackagesParent(),
            verifyInstalledRoot = { installed ->
                AdvancedTitleDirectoryTree.verifyEquivalent(snapshot, installed)
            },
            invalidateCache = templateCache::invalidate
        ).restore(snapshot, rootDir)
    }

    internal fun clearPackagesForRestoreRollback() = synchronized(mutationLock) {
        val parent = expectedPackagesParent()
        val target = AdvancedTitleDirectoryTree.resolveDirectChild(rootDir, parent)
        val discarded = File(
            parent,
            ".${target.name}.staging-${UUID.randomUUID()}"
        ).canonicalFile
        require(discarded.parentFile == parent) {
            "Advanced title rollback staging escaped its parent"
        }
        if (target.exists()) {
            NioFileExchange.move(target, discarded)
        }
        templateCache.invalidate()
        check(AdvancedTitleDirectoryRestorer.cleanupRestoreArtifacts(target)) {
            "Unable to clean advanced title restore artifacts"
        }
    }

    private fun loadLocalEntries(): List<Entry> {
        val parent = rootDir.apply { mkdirs() }.canonicalFile
        return parent.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && !it.name.startsWith('.') }
            .take(MAX_PACKAGES * 2)
            .mapNotNull { directory -> localEntry(parent, directory) }
            .take(MAX_PACKAGES)
            .toList()
    }

    private fun localEntry(parent: File, directory: File): Entry? {
        val canonical = runCatching { directory.canonicalFile }.getOrNull() ?: return null
        if (canonical.parentFile != parent) return null
        runCatching { verifyInstalledDirectory(canonical) }
            .getOrNull()
            ?.let { return Entry(it, canonical) }

        // Do not hide damaged packages. Hiding them leaves scoped-storage users with no way to
        // remove the package that made the reader or a previous manager page fail.
        val storedConfig = runCatching { readInstalledConfig(canonical) }.getOrNull()
        return Entry(
            config = invalidConfig(canonical, storedConfig),
            directory = canonical,
            isUsable = false
        )
    }

    private fun verifyInstalledDirectory(
        directory: File,
        expectedId: String? = null,
        requireDirectoryIdMatch: Boolean = true
    ): Config {
        val config = readInstalledConfig(directory, expectedId, requireDirectoryIdMatch)
        val json = readJsonFile(lottieFile(directory))
        require(AdvancedTitleConfig.hasRenderableLayers(json)) {
            appCtx.getString(R.string.advanced_title_invalid_json)
        }
        require(AdvancedTitleConfig.isValidLottieJson(json)) {
            appCtx.getString(R.string.advanced_title_invalid_json)
        }
        return config
    }

    private fun readInstalledConfig(
        directory: File,
        expectedId: String? = null,
        requireDirectoryIdMatch: Boolean = true
    ): Config {
        val manifest = File(directory, MANIFEST_FILE)
        require(manifest.isFile && manifest.length() in 1..64L * 1024L) {
            "Advanced title manifest is invalid"
        }
        val config = GSON.fromJsonObject<Config>(manifest.readText()).getOrThrow()
        require(isValidId(config.id) && config.id != BUILTIN_ID) {
            "Advanced title id is invalid"
        }
        require(expectedId == null || config.id == expectedId) { "Advanced title id changed" }
        AdvancedTitlePackageStorage.requireDirectoryMatchesId(
            directoryName = directory.name,
            configId = config.id,
            requireMatch = requireDirectoryIdMatch
        )
        require(config.name.isNotBlank() && config.name.length <= 100) { "Advanced title name is invalid" }
        return config.copy(name = config.name.trim())
    }

    private fun invalidConfig(directory: File, storedConfig: Config?): Config {
        val displayName = (storedConfig?.name ?: directory.name).trim()
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .take(100)
            .ifBlank { appCtx.getString(R.string.advanced_title_unnamed) }
        return (storedConfig ?: Config(
            id = invalidEntryId(directory),
            name = displayName,
            updatedAt = directory.lastModified()
        )).copy(
            id = invalidEntryId(directory),
            name = displayName
        )
    }

    private fun invalidEntryId(directory: File): String = "invalid_${directory.name}"

    private fun verifyRestoredPackagesRoot(directory: File) {
        AdvancedTitlePackagesRootVerifier(
            maxPackages = MAX_PACKAGES,
            verifyPackage = { verifyInstalledDirectory(it) }
        ).verify(directory)
    }

    private fun packageRestorer(): AdvancedTitleDirectoryRestorer {
        return AdvancedTitleDirectoryRestorer(
            mutationLock = mutationLock,
            expectedTargetParent = expectedPackagesParent(),
            verifyInstalledRoot = ::verifyRestoredPackagesRoot,
            invalidateCache = templateCache::invalidate
        )
    }

    private fun expectedPackagesParent(): File {
        return requireNotNull(rootDir.absoluteFile.parentFile) {
            "Advanced title package root has no parent"
        }.apply { mkdirs() }.canonicalFile
    }

    private fun migrateLegacyIfNeeded() {
        if (!appCtx.getPrefString(PreferKey.advancedTitlePackage).isNullOrBlank()) return
        val legacy = legacyTemplate()
            ?.takeIf { runCatching { validateJson(it) }.isSuccess }
        if (legacy == null) {
            appCtx.putPrefString(PreferKey.advancedTitlePackage, BUILTIN_ID)
            return
        }
        val builtin = builtinJson()
        val activeJson: String
        if (legacy == builtin) {
            appCtx.putPrefString(PreferKey.advancedTitlePackage, BUILTIN_ID)
            activeJson = builtin
        } else {
            val migrated = addOrUpdate(appCtx.getString(R.string.advanced_title_migrated), legacy)
            appCtx.putPrefString(PreferKey.advancedTitlePackage, migrated.id)
            activeJson = legacy
        }
        AdvancedTitleConfig.lottieJson = null
        AdvancedTitleConfig.lottiePath = null
        invalidate()
    }

    private fun legacyTemplate(): String? {
        // Migrate/recovery only: pull once from prefs then delete (never open-book hot path).
        val fromPrefs = appCtx.getPrefString(PreferKey.advancedTitleLottieJson)?.takeIf { it.isNotBlank() }
        if (fromPrefs != null) {
            appCtx.removePref(PreferKey.advancedTitleLottieJson)
            return fromPrefs
        }
        val path = AdvancedTitleConfig.lottiePath?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { readJsonFile(File(path)) }.getOrNull()
    }

    private fun legacyTemplateForOpen(): String? {
        if (legacyOpenTemplateChecked) return legacyOpenTemplateCache
        val fromPrefs = runCatching {
            appCtx.getPrefString(PreferKey.advancedTitleLottieJson)
        }.getOrNull()?.let(::safeLegacyTemplateCandidate)
        val fromPath = if (fromPrefs == null) {
            val path = runCatching { AdvancedTitleConfig.lottiePath }.getOrNull()
                ?.takeIf { it.isNotBlank() }
            path?.let { runCatching { readJsonFile(File(it)) }.getOrNull() }
                ?.let(::safeLegacyTemplateCandidate)
        } else {
            null
        }
        legacyOpenTemplateCache = fromPrefs ?: fromPath
        legacyOpenTemplateChecked = true
        return legacyOpenTemplateCache
    }

    internal fun safeLegacyTemplateCandidate(value: String): String? {
        if (value.isBlank() || value.length.toLong() > MAX_JSON_BYTES) return null
        if (utf8SizeUpTo(value, MAX_JSON_BYTES) > MAX_JSON_BYTES) return null
        return value.takeIf(AdvancedTitleConfig::hasRenderableLayers)
    }

    private fun builtinJson(): String {
        builtinJsonCache?.let { return it }
        return appCtx.resources.openRawResource(R.raw.advanced_title_lottie)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
            .also { builtinJsonCache = it }
    }

    private fun readJsonFile(file: File): String {
        require(file.isFile) { "Advanced title file is missing" }
        require(file.length() in 1..MAX_JSON_BYTES) {
            appCtx.getString(R.string.advanced_title_too_large)
        }
        return file.readText(Charsets.UTF_8)
    }

    private fun localDir(id: String): File = rootDir.getFile(id)

    private fun lottieFile(directory: File): File = directory.getFile(LOTTIE_FILE)

    private fun normalizeName(value: String): String {
        return value.trim().replace(Regex("[\\r\\n\\t]+"), " ")
            .take(100)
            .ifBlank { appCtx.getString(R.string.advanced_title_unnamed) }
    }

    private fun isValidId(value: String): Boolean = value.matches(Regex("^[A-Za-z0-9_-]{1,64}$"))
}

internal class AdvancedTitleTemplateCache(
    private val mutationLock: Any,
    private val readFile: (File) -> String
) {
    private data class CacheEntry(
        val id: String,
        val lastModified: Long,
        val length: Long,
        val json: String
    )

    private var entry: CacheEntry? = null

    fun read(id: String, file: File): String? = synchronized(mutationLock) {
        if (!file.isFile) return@synchronized null
        val lastModified = file.lastModified()
        val length = file.length()
        entry?.takeIf {
            it.id == id && it.lastModified == lastModified && it.length == length
        }?.let { return@synchronized it.json }
        runCatching { readFile(file) }.getOrNull()?.also { json ->
            entry = CacheEntry(
                id = id,
                lastModified = lastModified,
                length = length,
                json = json
            )
        }
    }

    fun invalidate() = synchronized(mutationLock) {
        entry = null
    }
}

internal class AdvancedTitlePackageReadGate(
    private val mutationLock: Any
) {
    fun <T> read(block: () -> T): T = synchronized(mutationLock) {
        block()
    }
}

internal class AdvancedTitleDirectoryRestorer(
    private val mutationLock: Any,
    private val expectedTargetParent: File,
    private val verifyInstalledRoot: (File) -> Unit,
    private val invalidateCache: () -> Unit,
    private val transactionFactory: () -> BubbleDirectoryTransaction = { BubbleDirectoryTransaction() },
    private val cleanupArtifacts: (File) -> Boolean = { cleanupRestoreArtifacts(it) },
    private val reportCleanupFailure: (String) -> Unit = { AppLog.put(it) }
) {
    fun restore(sourceDir: File, targetDir: File) {
        require(sourceDir.isDirectory) { "Advanced title restore source is missing" }
        val source = sourceDir.canonicalFile
        val parent = expectedTargetParent.apply { mkdirs() }.canonicalFile
        val target = AdvancedTitleDirectoryTree.resolveDirectChild(targetDir, parent)
        AdvancedTitleDirectoryTree.requireDisjoint(source, target)
        val staging = File(parent, ".${target.name}.staging-${UUID.randomUUID()}").canonicalFile
        val backup = File(parent, ".${target.name}.backup-${UUID.randomUUID()}").canonicalFile
        require(staging.parentFile == parent && backup.parentFile == parent) {
            "Advanced title restore staging escaped its parent"
        }
        try {
            AdvancedTitleDirectoryTree.copyStrict(source, staging)
            synchronized(mutationLock) {
                transactionFactory().install(target, staging, backup) { installedDir ->
                    verifyInstalledRoot(installedDir)
                    invalidateCache()
                }
                // Cleanup cannot participate in rollback: deleting stale artifacts may partially
                // succeed, while the verified target is already the only complete installation.
                if (!cleanupArtifacts(target)) {
                    runCatching {
                        reportCleanupFailure(
                            "Unable to clean committed advanced title restore artifacts for: " +
                                target.absolutePath
                        )
                    }
                }
            }
        } finally {
            AdvancedTitlePackageStorage.deleteStagingDirectory(parent, staging)
        }
    }

    companion object {
        private const val STAGING_MARKER = ".staging-"
        private const val BACKUP_MARKER = ".backup-"

        internal fun cleanupRestoreArtifacts(targetDir: File): Boolean {
            val target = runCatching { targetDir.canonicalFile }.getOrNull() ?: return false
            val parent = runCatching { target.parentFile?.canonicalFile }.getOrNull() ?: return false
            var cleaned = true
            val artifacts = parent.listFiles() ?: return false
            artifacts
                .asSequence()
                .filter { isRestoreArtifactName(it.name, target.name) }
                .forEach { artifact ->
                    if (Files.isSymbolicLink(artifact.toPath())) {
                        cleaned = false
                        return@forEach
                    }
                    val canonical = runCatching { artifact.canonicalFile }.getOrNull()
                    val normalized = artifact.absoluteFile.toPath().normalize().toFile()
                    if (canonical == null || canonical != normalized || canonical.parentFile != parent) {
                        cleaned = false
                    } else if (!deleteTreeNoFollow(artifact)) {
                        cleaned = false
                    }
                }
            return cleaned
        }

        private fun isRestoreArtifactName(name: String, targetName: String): Boolean {
            val prefix = ".$targetName"
            val suffix = when {
                name.startsWith(prefix + STAGING_MARKER) -> {
                    name.removePrefix(prefix + STAGING_MARKER)
                }
                name.startsWith(prefix + BACKUP_MARKER) -> {
                    name.removePrefix(prefix + BACKUP_MARKER)
                }
                else -> return false
            }
            return runCatching { UUID.fromString(suffix) }.isSuccess
        }

        private fun deleteTreeNoFollow(root: File): Boolean {
            val path = root.toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
            return runCatching {
                Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes
                    ): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        exc: IOException
                    ): FileVisitResult {
                        throw exc
                    }

                    override fun postVisitDirectory(
                        dir: Path,
                        exc: IOException?
                    ): FileVisitResult {
                        if (exc != null) throw exc
                        Files.delete(dir)
                        return FileVisitResult.CONTINUE
                    }
                })
                !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            }.getOrDefault(false)
        }
    }
}

internal class AdvancedTitlePackagesRootVerifier(
    private val maxPackages: Int,
    private val verifyPackage: (File) -> Unit
) {
    fun verify(rootDir: File) {
        require(maxPackages >= 0) { "Advanced title package limit is invalid" }
        val root = rootDir.canonicalFile
        require(root.isDirectory) { "Advanced title restore target is invalid" }
        val packages = requireNotNull(root.listFiles()) {
            "Unable to list advanced title restore target"
        }.filterNot { it.name.startsWith('.') }
        require(packages.size <= maxPackages) { "Too many advanced title packages" }
        packages.forEach { packageDir ->
            val canonical = packageDir.canonicalFile
            require(canonical.parentFile == root) {
                "Advanced title package escaped its root"
            }
            require(canonical.isDirectory) { "Advanced title package is not a directory" }
            verifyPackage(canonical)
        }
    }
}

internal object AdvancedTitleDirectoryTree {
    fun resolveDirectChild(targetDir: File, expectedParentDir: File): File {
        val parent = expectedParentDir.canonicalFile
        val requested = targetDir.absoluteFile.toPath().normalize().toFile()
        val requestedParent = requireNotNull(requested.parentFile) {
            "Advanced title target has no parent"
        }.canonicalFile
        require(requestedParent == parent) { "Advanced title target escaped its expected parent" }
        val target = File(parent, requested.name).absoluteFile.toPath().normalize().toFile()
        val path = target.toPath()
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(path) && target.canonicalFile == target) {
                "Advanced title target must not be a symbolic link"
            }
        }
        return target
    }

    fun requireDisjoint(firstDir: File, secondDir: File) {
        val first = firstDir.canonicalFile
        val second = secondDir.canonicalFile
        require(!isSameOrDescendant(first, second) && !isSameOrDescendant(second, first)) {
            "Advanced title directories overlap"
        }
    }

    fun copyStrict(sourceDir: File, targetDir: File) {
        val source = sourceDir.canonicalFile
        val target = targetDir.canonicalFile
        requireDisjoint(source, target)
        require(source.isDirectory) { "Advanced title restore source is missing" }
        require(!target.exists()) { "Advanced title copy target already exists" }
        copyDirectory(source, target)
        verifyEquivalent(source, target)
    }

    fun copyVisibleDirectoriesStrict(sourceDir: File, targetDir: File): Boolean {
        val source = sourceDir.absoluteFile.toPath().normalize().toFile()
        if (!source.isDirectory) return false
        val sourceCanonical = source.canonicalFile
        require(sourceCanonical == source) { "Advanced title export source must not be a link" }
        val packages = source.listFiles()
            ?.filter { !it.name.startsWith('.') && it.isDirectory }
            ?: throw IOException("Unable to list advanced title export source")
        if (packages.isEmpty()) return false
        val verifiedPackages = packages.map { packageDir ->
            val normalized = packageDir.absoluteFile.toPath().normalize().toFile()
            val canonical = packageDir.canonicalFile
            require(canonical == normalized && canonical.parentFile == sourceCanonical) {
                "Advanced title export package escaped its root"
            }
            canonical
        }
        val target = targetDir.canonicalFile
        requireDisjoint(sourceCanonical, target)
        require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Advanced title export target already exists"
        }
        try {
            check(target.mkdirs()) { "Unable to create advanced title export target" }
            verifiedPackages.forEach { packageDir ->
                copyStrict(packageDir, File(target, packageDir.name))
            }
            val exported = directChildren(target).associateBy { it.name }
            check(exported.keys == verifiedPackages.mapTo(hashSetOf()) { it.name }) {
                "Advanced title package export is incomplete"
            }
            verifiedPackages.forEach { packageDir ->
                verifyEquivalent(packageDir, requireNotNull(exported[packageDir.name]))
            }
            return true
        } catch (error: Exception) {
            target.deleteRecursively()
            throw error
        }
    }

    fun verifyEquivalent(sourceDir: File, targetDir: File) {
        val source = sourceDir.canonicalFile
        val target = targetDir.canonicalFile
        require(source.isDirectory && target.isDirectory) {
            "Advanced title directory comparison requires two directories"
        }
        verifyCopy(source, target)
    }

    private fun copyDirectory(source: File, target: File) {
        check(target.mkdirs() || target.isDirectory) {
            "Unable to create advanced title directory: ${target.absolutePath}"
        }
        directChildren(source).forEach { sourceChild ->
            val targetChild = File(target, sourceChild.name)
            when {
                sourceChild.isDirectory -> copyDirectory(sourceChild, targetChild)
                sourceChild.isFile -> {
                    sourceChild.copyTo(targetChild, overwrite = false)
                    check(targetChild.isFile && targetChild.length() == sourceChild.length()) {
                        "Incomplete advanced title file copy: ${sourceChild.absolutePath}"
                    }
                }
                else -> throw IOException(
                    "Unsupported advanced title package entry: ${sourceChild.absolutePath}"
                )
            }
        }
    }

    private fun verifyCopy(source: File, target: File) {
        val sourceChildren = directChildren(source).associateBy { it.name }
        val targetChildren = directChildren(target).associateBy { it.name }
        check(sourceChildren.keys == targetChildren.keys) {
            "Advanced title directory snapshot is incomplete"
        }
        sourceChildren.forEach { (name, sourceChild) ->
            val targetChild = requireNotNull(targetChildren[name])
            when {
                sourceChild.isDirectory -> {
                    check(targetChild.isDirectory) {
                        "Advanced title directory snapshot changed entry type"
                    }
                    verifyCopy(sourceChild, targetChild)
                }
                sourceChild.isFile -> {
                    check(targetChild.isFile && targetChild.length() == sourceChild.length()) {
                        "Advanced title directory snapshot has an incomplete file"
                    }
                    check(fileDigest(sourceChild).contentEquals(fileDigest(targetChild))) {
                        "Advanced title directory snapshot file content changed"
                    }
                }
                else -> throw IOException(
                    "Unsupported advanced title package entry: ${sourceChild.absolutePath}"
                )
            }
        }
    }

    private fun directChildren(directory: File): Array<File> {
        val canonical = directory.canonicalFile
        val children = directory.listFiles()
            ?: throw IOException("Unable to list advanced title directory: ${directory.absolutePath}")
        children.forEach { child ->
            val normalized = child.absoluteFile.toPath().normalize().toFile()
            val resolved = child.canonicalFile
            if (resolved != normalized || resolved.parentFile != canonical) {
                throw IOException("Advanced title directory entry escaped its parent")
            }
        }
        return children
    }

    private fun fileDigest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun isSameOrDescendant(file: File, ancestor: File): Boolean {
        var current: File? = file
        while (current != null) {
            if (current == ancestor) return true
            current = current.parentFile
        }
        return false
    }
}
