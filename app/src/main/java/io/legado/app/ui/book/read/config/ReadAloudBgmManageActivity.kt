package io.legado.app.ui.book.read.config

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadAloudBgmGroup
import io.legado.app.data.entities.ReadAloudBgmTrack
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.readaloud.ReadAloudConfigChangeNotifier
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.themeMutedColorOrDefault
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import splitties.init.appCtx

class ReadAloudBgmManageActivity : BaseActivity<ActivityThemeManageBinding>() {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)

    private val adapter = BgmAdapter()
    private var groups: List<ReadAloudBgmGroup> = emptyList()
    private var tracks: List<ReadAloudBgmTrack> = emptyList()
    private val selectedIds = linkedSetOf<Long>()
    private val expandedGroupIds = linkedSetOf<Long>()
    private var expandedGroupsInitialized = false
    private var currentAssetType: String = ReadAloudBgmTrack.TYPE_BGM
    private var pendingPackageAssetType: String = ReadAloudBgmTrack.TYPE_SFX
    private var pendingExportPackageFile: File? = null
    private var importing = false
    private var importingText = ""
    private lateinit var batchActionBar: LinearLayout

    private val currentAssetLabel: String
        get() = assetLabel(currentAssetType)

    private val importAudio = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            if (importing) return@registerForActivityResult
            lifecycleScope.launch {
                setImporting(true, getString(R.string.bgm_importing_progress, currentAssetLabel))
                kotlin.runCatching {
                    withContext(Dispatchers.IO) { importTrack(uri) }
                }.onSuccess {
                    selectedIds.clear()
                    notifyAudioChanged()
                    finishImportingAndLoad()
                    toastOnUi(R.string.toast_import_done)
                }.onFailure {
                    finishImportingAndLoad()
                    toastOnUi(it.localizedMessage ?: getString(R.string.import_failed))
                }
            }
        }
    }

    private val importAudios = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        if (importing) return@registerForActivityResult
        lifecycleScope.launch {
            setImporting(true, getString(R.string.bgm_importing_batch, currentAssetLabel))
            var success = 0
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    uris.forEach { uri ->
                        runCatching {
                            importTrack(uri)
                            success += 1
                        }
                    }
                }
            }.onSuccess {
                selectedIds.clear()
                if (success > 0) notifyAudioChanged()
                finishImportingAndLoad()
                toastOnUi(appCtx.getString(R.string.toast_imported_count_label, success, currentAssetLabel))
            }.onFailure {
                finishImportingAndLoad()
                toastOnUi(it.localizedMessage ?: getString(R.string.batch_import_failed))
            }
        }
    }

    private val importAudioPackage = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            val assetType = pendingPackageAssetType
            confirmImportAudioPackage(uri, assetType)
        }
    }

    private val exportAudioPackage = registerForActivityResult(HandleFileContract()) { result ->
        pendingExportPackageFile?.delete()
        pendingExportPackageFile = null
        result.uri?.let {
            toastOnUi(appCtx.getString(R.string.toast_exported_zip, currentAssetLabel))
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        load()
    }

    override fun onDestroy() {
        pendingExportPackageFile?.delete()
        pendingExportPackageFile = null
        super.onDestroy()
    }

    private fun initView() = binding.run {
        titleBar.title = getString(R.string.bgm_title)
        tabBar.visibility = View.VISIBLE
        tabBar.background = UiCorner.opaqueRounded(
            themeCardColorOrDefault(),
            UiCorner.actionRadius(this@ReadAloudBgmManageActivity)
        )
        btnDay.text = getString(R.string.bgm_label_music)
        btnNight.text = getString(R.string.bgm_label_sfx)
        btnDay.setOnClickListener { switchAssetType(ReadAloudBgmTrack.TYPE_BGM) }
        btnNight.setOnClickListener { switchAssetType(ReadAloudBgmTrack.TYPE_SFX) }
        btnAdd.visibility = View.GONE
        initBatchActionBar(root)
        tvSummary.setTextColor(secondaryTextColor)
        recyclerView.layoutManager = LinearLayoutManager(this@ReadAloudBgmManageActivity)
        recyclerView.adapter = adapter
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        root.applyUiBodyTypefaceDeep(uiTypeface())
        updateAssetTabs()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_IMPORT, 0, getString(R.string.bgm_menu_import)).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_EXPORT_PACKAGE, 1, getString(R.string.bgm_menu_export)).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_ADD_GROUP, 2, getString(R.string.bgm_group_add)).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_MANAGE_GROUPS, 3, getString(R.string.bgm_group_manage)).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_IMPORT -> showImportActions()
            MENU_EXPORT_PACKAGE -> exportCurrentAudioPackage()
            MENU_ADD_GROUP -> showGroupEditor()
            MENU_MANAGE_GROUPS -> showGroupManage()
        }
        return true
    }

    private fun initBatchActionBar(root: View) {
        val parent = root as? LinearLayout ?: return
        batchActionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(batchActionButton(getString(R.string.bgm_batch_group)) { moveSelected() })
            addView(batchActionButton(getString(R.string.delete)) { deleteSelected() })
            addView(batchActionButton(getString(R.string.bgm_batch_cancel)) {
                selectedIds.clear()
                load()
            })
        }
        parent.addView(
            batchActionBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                56.dpToPx()
            ).apply {
                leftMargin = 16.dpToPx()
                rightMargin = 16.dpToPx()
                bottomMargin = 16.dpToPx()
            }
        )
    }

    private fun batchActionButton(text: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = uiTypeface()
            setTextColor(primaryTextColor)
            background = UiCorner.actionSelector(
                themeCardColorOrDefault(),
                themeMutedColorOrDefault(),
                UiCorner.actionRadius(this@ReadAloudBgmManageActivity)
            )
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                leftMargin = 4.dpToPx()
                rightMargin = 4.dpToPx()
            }
        }
    }

    private fun showImportActions() {
        if (importing) {
            toastOnUi(importingText.ifBlank { getString(R.string.bgm_importing_busy) })
            return
        }
        selector(
            getString(R.string.bgm_import_dialog_title),
            listOf(
                getString(R.string.bgm_import_batch_item, currentAssetLabel),
                getString(R.string.bgm_import_single_item, currentAssetLabel),
                getString(R.string.bgm_import_music_zip),
                getString(R.string.bgm_import_sfx_zip),
                getString(R.string.bgm_group_add),
                getString(R.string.bgm_group_manage)
            )
        ) { _, index ->
            when (index) {
                0 -> importAudios.launch("audio/*")
                1 -> importAudio.launch {
                    mode = HandleFileContract.FILE
                    title = getString(R.string.bgm_import_file_title, currentAssetLabel)
                    allowExtensions = arrayOf("mp3", "wav", "m4a", "aac", "ogg", "flac")
                }
                2 -> openAudioPackageImport(ReadAloudBgmTrack.TYPE_BGM)
                3 -> openAudioPackageImport(ReadAloudBgmTrack.TYPE_SFX)
                4 -> showGroupEditor()
                5 -> showGroupManage()
            }
        }
    }

    private fun openAudioPackageImport(assetType: String) {
        if (importing) return
        pendingPackageAssetType = ReadAloudBgmTrack.normalizeAssetType(assetType)
        importAudioPackage.launch {
            mode = HandleFileContract.FILE
            title = getString(R.string.bgm_import_zip_title, assetLabel(pendingPackageAssetType))
            allowExtensions = arrayOf("zip")
        }
    }

    private fun confirmImportAudioPackage(uri: Uri, assetType: String) {
        val normalized = ReadAloudBgmTrack.normalizeAssetType(assetType)
        val label = assetLabel(normalized)
        selector(
            getString(R.string.bgm_import_zip_title, label),
            listOf(
                getString(R.string.bgm_import_replace, label),
                getString(R.string.bgm_import_append)
            )
        ) { _, index ->
            val replaceOld = index == 0
            lifecycleScope.launch {
                setImporting(true, getString(R.string.bgm_importing_zip, label))
                kotlin.runCatching {
                    withContext(Dispatchers.IO) {
                        importAudioPackage(uri, normalized, replaceOld = replaceOld)
                    }
                }.onSuccess { count ->
                    currentAssetType = normalized
                    selectedIds.clear()
                    expandedGroupIds.clear()
                    expandedGroupsInitialized = false
                    updateAssetTabs()
                    notifyAudioChanged()
                    finishImportingAndLoad()
                    toastOnUi(appCtx.getString(R.string.toast_imported_count_label, count, label))
                }.onFailure {
                    finishImportingAndLoad()
                    toastOnUi(it.localizedMessage ?: getString(R.string.ai_bgm_pack_import_failed))
                }
            }
        }
    }

    private fun exportCurrentAudioPackage() {
        if (importing) {
            toastOnUi(importingText.ifBlank { getString(R.string.bgm_processing_busy) })
            return
        }
        val assetType = currentAssetType
        val label = assetLabel(assetType)
        lifecycleScope.launch {
            setImporting(true, getString(R.string.bgm_exporting_zip, label))
            val result = kotlin.runCatching {
                withContext(Dispatchers.IO) { buildAudioPackageZip(assetType) }
            }
            finishImportingAndLoad()
            result.onSuccess { data ->
                pendingExportPackageFile?.delete()
                pendingExportPackageFile = data
                exportAudioPackage.launch {
                    mode = HandleFileContract.EXPORT
                    title = getString(R.string.bgm_export_zip_title, label)
                    fileData = HandleFileContract.FileData(
                        getString(R.string.bgm_export_file_name, label, System.currentTimeMillis()),
                        data,
                        "application/zip"
                    )
                }
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.ai_bgm_zip_export_failed, label))
            }
        }
    }

    private fun load() {
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                appDb.readAloudBgmDao.groupsByType(currentAssetType) to appDb.readAloudBgmDao.enabledTracksByType(currentAssetType)
            }
            groups = data.first
            tracks = data.second
            selectedIds.retainAll(tracks.map { it.id }.toSet())
            adapter.submit(buildRows())
            updateBatchActionBar()
            binding.tvSummary.text = if (importing) {
                importingText.ifBlank { getString(R.string.bgm_importing_generic) }
            } else if (tracks.isEmpty()) {
                if (currentAssetType == ReadAloudBgmTrack.TYPE_SFX) {
                    getString(R.string.bgm_empty_sfx)
                } else {
                    getString(R.string.bgm_empty_music)
                }
            } else {
                getString(
                    R.string.bgm_summary,
                    tracks.size,
                    currentAssetLabel,
                    groups.size.coerceAtLeast(1),
                    selectedIds.size
                )
            }
        }
    }

    private fun switchAssetType(assetType: String) {
        val normalized = ReadAloudBgmTrack.normalizeAssetType(assetType)
        if (currentAssetType == normalized) return
        currentAssetType = normalized
        selectedIds.clear()
        expandedGroupIds.clear()
        expandedGroupsInitialized = false
        updateAssetTabs()
        load()
    }

    private fun updateAssetTabs() = binding.run {
        val bgmSelected = currentAssetType == ReadAloudBgmTrack.TYPE_BGM
        btnDay.isSelected = bgmSelected
        btnNight.isSelected = !bgmSelected
        btnDay.setTextColor(if (bgmSelected) accentColor else secondaryTextColor)
        btnNight.setTextColor(if (!bgmSelected) accentColor else secondaryTextColor)
    }

    private fun updateBatchActionBar() {
        if (::batchActionBar.isInitialized) {
            batchActionBar.visibility = if (selectedIds.isEmpty() || importing) View.GONE else View.VISIBLE
        }
    }

    private fun setImporting(value: Boolean, text: String = "") {
        importing = value
        importingText = if (value) text else ""
        binding.recyclerView.alpha = if (value) 0.45f else 1f
        binding.tvSummary.text = if (value) text.ifBlank { getString(R.string.bgm_importing_generic) } else binding.tvSummary.text
        updateBatchActionBar()
    }

    private fun finishImportingAndLoad() {
        setImporting(false)
        load()
    }

    private fun assetLabel(assetType: String): String {
        return if (ReadAloudBgmTrack.normalizeAssetType(assetType) == ReadAloudBgmTrack.TYPE_SFX) {
            getString(R.string.bgm_label_sfx)
        } else {
            getString(R.string.bgm_label_music)
        }
    }

    private fun notifyAudioChanged() {
        ReadAloudConfigChangeNotifier.notifyAudio()
    }

    private fun showGroupEditor(group: ReadAloudBgmGroup? = null) {
        val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = getString(R.string.bgm_group_name_hint)
            editView.setText(group?.name.orEmpty())
            editView.setSelection(editView.text?.length ?: 0)
        }
        alert(if (group == null) getString(R.string.bgm_group_add) else getString(R.string.bgm_group_edit)) {
            customView { binding.root }
            okButton {
                val name = binding.editView.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    toastOnUi(R.string.toast_group_name_empty)
                    return@okButton
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    if (group == null) {
                        appDb.readAloudBgmDao.insertGroup(
                            ReadAloudBgmGroup(
                                name = name,
                                assetType = currentAssetType,
                                sortOrder = (appDb.readAloudBgmDao.maxGroupOrderByType(currentAssetType) ?: 0) + 1,
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                    } else {
                        appDb.readAloudBgmDao.updateGroup(group.copy(name = name, updatedAt = now))
                    }
                    notifyAudioChanged()
                    launch(Dispatchers.Main) { load() }
                }
            }
            cancelButton()
        }
    }

    private fun showGroupManage() {
        val managedGroups = groups.filterNot { it.isDefaultGroup() }
        if (managedGroups.isEmpty()) {
            toastOnUi(R.string.toast_no_group)
            return
        }
        selector(getString(R.string.bgm_group_manage), managedGroups.map { it.displayName() }) { _, index ->
            val group = managedGroups[index]
            selector(
                group.displayName(),
                listOf(getString(R.string.bgm_group_edit), getString(R.string.bgm_group_delete))
            ) { _, action ->
                when (action) {
                    0 -> showGroupEditor(group)
                    1 -> confirmDeleteGroup(group)
                }
            }
        }
    }

    private fun confirmDeleteGroup(group: ReadAloudBgmGroup) {
        if (group.isDefaultGroup()) {
            toastOnUi(R.string.toast_default_group_undeletable)
            return
        }
        alert(getString(R.string.bgm_group_delete)) {
            setMessage(getString(R.string.bgm_group_delete_message, group.displayName(), currentAssetLabel))
            okButton {
                lifecycleScope.launch(Dispatchers.IO) {
                    appDb.readAloudBgmDao.resetTrackGroup(group.id, currentAssetType)
                    appDb.readAloudBgmDao.deleteGroup(group.id, currentAssetType)
                    selectedIds.clear()
                    notifyAudioChanged()
                    launch(Dispatchers.Main) { load() }
                }
            }
            cancelButton()
        }
    }

    private suspend fun importTrack(uri: Uri) {
        val groupId = ensureDefaultGroup(currentAssetType)
        val displayName = displayName(uri).ifBlank { "${currentAssetType}-${System.currentTimeMillis()}" }
        val extension = displayName.substringAfterLast('.', "mp3").lowercase()
        val folder = File(filesDir, "readAloudAudio/$currentAssetType").apply { mkdirs() }
        val target = File(folder, "${System.currentTimeMillis()}_${displayName.toSafeFileName(extension)}")
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error(getString(R.string.bgm_err_read_audio))
        val checksum = target.inputStream().use { MD5Utils.md5Encode(it) }
        val duration = readDuration(target)
        val now = System.currentTimeMillis()
        appDb.readAloudBgmDao.insertTrack(
            ReadAloudBgmTrack(
                groupId = groupId,
                assetType = currentAssetType,
                name = displayName.substringBeforeLast('.'),
                fileName = displayName,
                filePath = target.absolutePath,
                checksum = checksum,
                durationMs = duration,
                sortOrder = (appDb.readAloudBgmDao.maxTrackOrder(groupId, currentAssetType) ?: 0) + 1,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    private suspend fun importAudioPackage(
        uri: Uri,
        assetType: String,
        replaceOld: Boolean
    ): Int {
        val normalizedType = ReadAloudBgmTrack.normalizeAssetType(assetType)
        val metadata = readAudioPackageMetadata(uri)
        if (replaceOld) {
            appDb.readAloudBgmDao.tracksByType(normalizedType).forEach { track ->
                runCatching { File(track.filePath).delete() }
            }
            appDb.readAloudBgmDao.deleteTracksByType(normalizedType)
            appDb.readAloudBgmDao.deleteGroupsByType(normalizedType)
        }
        val folder = File(filesDir, "readAloudAudio/$normalizedType").apply { mkdirs() }
        val groupIds = mutableMapOf<String, Long>()
        val nextSortByGroup = mutableMapOf<Long, Int>()
        var imported = 0
        contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    if (!entry.isDirectory && entryName.isSupportedAudioEntry()) {
                        val fileName = entryName.substringAfterLast('/').ifBlank {
                            "audio-${System.currentTimeMillis()}.mp3"
                        }
                        val info = metadata[fileName.lowercase(Locale.ROOT)]
                            ?: metadata[entryName.substringAfterLast('/').lowercase(Locale.ROOT)]
                        val extension = fileName.substringAfterLast('.', "mp3").lowercase(Locale.ROOT)
                        val target = File(
                            folder,
                            "${System.currentTimeMillis()}_${imported}_${fileName.toSafeFileName(extension)}"
                        )
                        target.outputStream().use { output -> zip.copyTo(output) }
                        val checksum = target.inputStream().use { MD5Utils.md5Encode(it) }
                        val fallbackGroup = fallbackGroupName(entryName)
                        val groupName = info?.groupName?.ifBlank { fallbackGroup } ?: fallbackGroup
                        val groupId = groupIds.getOrPut(groupName) { ensureGroup(groupName, normalizedType) }
                        val sortOrder = nextSortByGroup.getOrPut(groupId) {
                            (appDb.readAloudBgmDao.maxTrackOrder(groupId, normalizedType) ?: 0) + 1
                        }
                        nextSortByGroup[groupId] = sortOrder + 1
                        val now = System.currentTimeMillis()
                        appDb.readAloudBgmDao.insertTrack(
                            ReadAloudBgmTrack(
                                groupId = groupId,
                                assetType = normalizedType,
                                name = info?.name?.ifBlank { fallbackTrackName(fileName) }
                                    ?: fallbackTrackName(fileName),
                                fileName = fileName,
                                filePath = target.absolutePath,
                                tags = info?.tags?.ifBlank { fallbackTags(groupName, fileName) }
                                    ?: fallbackTags(groupName, fileName),
                                checksum = checksum,
                                durationMs = readDuration(target),
                                defaultVolume = (info?.volume ?: 1f).coerceIn(0f, 1f),
                                sortOrder = sortOrder,
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                        imported += 1
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: error(getString(R.string.bgm_err_read_package))
        if (imported == 0) error(getString(R.string.bgm_err_empty_package))
        return imported
    }

    private fun buildAudioPackageZip(assetType: String): File {
        val normalizedType = ReadAloudBgmTrack.normalizeAssetType(assetType)
        val exportTracks = appDb.readAloudBgmDao.enabledTracksByType(normalizedType)
        if (exportTracks.isEmpty()) error(getString(R.string.bgm_err_nothing_export, assetLabel(normalizedType)))
        val exportGroups = appDb.readAloudBgmDao.groupsByType(normalizedType).associateBy { it.id }
        val entries = buildPackageExportEntries(exportTracks, exportGroups)
        if (entries.isEmpty()) error(getString(R.string.bgm_err_no_files))
        val output = File.createTempFile("read_aloud_${normalizedType}_", ".zip", cacheDir)
        runCatching {
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("config.yaml"))
            zip.write(buildPackageConfigYaml(entries).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            entries.forEach { entry ->
                zip.putNextEntry(ZipEntry(entry.entryName))
                entry.file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
        }.onFailure {
            output.delete()
            throw it
        }
        return output
    }

    private fun buildPackageExportEntries(
        exportTracks: List<ReadAloudBgmTrack>,
        exportGroups: Map<Long, ReadAloudBgmGroup>
    ): List<PackageExportEntry> {
        val usedNames = linkedSetOf<String>()
        return exportTracks.mapNotNull { track ->
            val file = File(track.filePath)
            if (!file.exists() || file.length() <= 0L) return@mapNotNull null
            val groupName = exportGroups[track.groupId]?.name
                ?.takeUnless { it == ReadAloudBgmGroup.DEFAULT_NAME }
                ?: ReadAloudBgmGroup.DEFAULT_NAME
            val extension = track.fileName.substringAfterLast('.', "mp3").lowercase(Locale.ROOT)
            val safeFileName = track.fileName
                .ifBlank { "${track.displayName()}.$extension" }
                .toSafeFileName(extension)
            val folder = groupName.toZipPathSegment()
            var entryName = "$folder/$safeFileName"
            var index = 1
            while (!usedNames.add(entryName.lowercase(Locale.ROOT))) {
                val base = safeFileName.substringBeforeLast('.', safeFileName)
                val ext = safeFileName.substringAfterLast('.', "")
                entryName = if (ext.isBlank()) {
                    "$folder/${base}_$index"
                } else {
                    "$folder/${base}_$index.$ext"
                }
                index += 1
            }
            PackageExportEntry(
                track = track,
                groupName = groupName,
                entryName = entryName,
                file = file
            )
        }
    }

    private fun buildPackageConfigYaml(entries: List<PackageExportEntry>): String {
        return buildString {
            entries.forEach { entry ->
                val track = entry.track
                val tags = track.tags.split(',', '，')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                val desc = (listOf(entry.groupName) + tags)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString("｜")
                appendLine("- name: ${track.displayName().toYamlScalar()}")
                appendLine("  desc: ${desc.toYamlScalar()}")
                appendLine("  param: ${entry.entryName.toYamlScalar()}")
                appendLine("  volume: ${track.defaultVolume.coerceIn(0f, 1f)}")
            }
        }
    }

    private fun readAudioPackageMetadata(uri: Uri): Map<String, PackageAudioInfo> {
        val configText = contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.substringAfterLast('/') == "config.yaml") {
                        return@use zip.readTextLimited(MAX_AUDIO_PACKAGE_CONFIG_BYTES)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                ""
            }
        }.orEmpty()
        return parsePackageAudioConfig(configText)
    }

    private fun ZipInputStream.readTextLimited(maxBytes: Int): String {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { getString(R.string.bgm_err_config_too_large) }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun parsePackageAudioConfig(text: String): Map<String, PackageAudioInfo> {
        if (text.isBlank()) return emptyMap()
        val result = linkedMapOf<String, PackageAudioInfo>()
        var name = ""
        var desc = ""
        var param = ""
        var volume = 1f

        fun commit() {
            val fileName = param.substringAfterLast('/').trim()
            if (!fileName.isSupportedAudioFileName()) return
            val groupName = desc.substringBefore('｜')
                .substringBefore('|')
                .trim()
                .ifBlank { ReadAloudBgmGroup.DEFAULT_NAME }
            val tags = desc.replace('｜', ',')
                .replace('|', ',')
                .split(',', '，')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(",")
            result[fileName.lowercase(Locale.ROOT)] = PackageAudioInfo(
                name = name.ifBlank { fileName.substringBeforeLast('.') },
                groupName = groupName,
                tags = tags,
                volume = volume
            )
        }

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trimStart()
            if (line.startsWith("- ")) {
                commit()
                name = ""
                desc = ""
                param = ""
                volume = 1f
            }
            when {
                line.startsWith("name:") -> name = line.substringAfter(':').cleanYamlScalar()
                line.startsWith("desc:") -> desc = line.substringAfter(':').cleanYamlScalar()
                line.startsWith("param:") -> param = line.substringAfter(':').cleanYamlScalar()
                line.startsWith("volume:") -> volume = line.substringAfter(':').cleanYamlScalar()
                    .toFloatOrNull()
                    ?: 1f
            }
        }
        commit()
        return result
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index).orEmpty()
            }
        }
        return uri.lastPathSegment.orEmpty().substringAfterLast('/')
    }

    private fun readDuration(file: File): Long {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
        }.getOrDefault(0L)
    }

    private fun ensureDefaultGroup(assetType: String = currentAssetType): Long {
        val normalized = ReadAloudBgmTrack.normalizeAssetType(assetType)
        groups.firstOrNull { (it.id == 0L || it.name == ReadAloudBgmGroup.DEFAULT_NAME) && it.assetType == normalized }?.let { return it.id }
        appDb.readAloudBgmDao.groupsByType(normalized).firstOrNull { it.name == ReadAloudBgmGroup.DEFAULT_NAME }?.let { return it.id }
        val now = System.currentTimeMillis()
        return appDb.readAloudBgmDao.insertGroup(
            ReadAloudBgmGroup(name = ReadAloudBgmGroup.DEFAULT_NAME, assetType = normalized, sortOrder = 0, createdAt = now, updatedAt = now)
        )
    }

    private fun ensureGroup(name: String, assetType: String = currentAssetType): Long {
        val normalizedType = ReadAloudBgmTrack.normalizeAssetType(assetType)
        val normalized = name.trim().ifBlank { ReadAloudBgmGroup.DEFAULT_NAME }
        if (normalized == ReadAloudBgmGroup.DEFAULT_NAME) return ensureDefaultGroup(normalizedType)
        appDb.readAloudBgmDao.groupsByType(normalizedType).firstOrNull { it.name == normalized }?.let { return it.id }
        val now = System.currentTimeMillis()
        return appDb.readAloudBgmDao.insertGroup(
            ReadAloudBgmGroup(
                name = normalized,
                assetType = normalizedType,
                sortOrder = (appDb.readAloudBgmDao.maxGroupOrderByType(normalizedType) ?: 0) + 1,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    private fun showTrackActions(track: ReadAloudBgmTrack) {
        selector(
            track.displayName(),
            listOf(
                getString(R.string.bgm_track_edit_tags),
                getString(R.string.bgm_track_volume),
                getString(R.string.bgm_track_move),
                getString(R.string.delete)
            )
        ) { _, index ->
            when (index) {
                0 -> showTagsEditor(track)
                1 -> showVolumeEditor(track)
                2 -> moveTracks(listOf(track.id))
                3 -> confirmDelete(listOf(track.id))
            }
        }
    }

    private fun showTagsEditor(track: ReadAloudBgmTrack) {
        val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = getString(R.string.bgm_tags_hint)
            editView.setText(track.tags)
            editView.setSelection(editView.text?.length ?: 0)
        }
        alert(getString(R.string.bgm_track_edit_tags)) {
            customView { binding.root }
            okButton {
                val tags = binding.editView.text?.toString().orEmpty()
                    .split(',', '，')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(",")
                lifecycleScope.launch(Dispatchers.IO) {
                    appDb.readAloudBgmDao.updateTrack(track.copy(tags = tags, updatedAt = System.currentTimeMillis()))
                    notifyAudioChanged()
                    launch(Dispatchers.Main) { load() }
                }
            }
            cancelButton()
        }
    }

    private fun showVolumeEditor(track: ReadAloudBgmTrack) {
        val current = (track.defaultVolume * 100).toInt().coerceIn(0, 100)
        io.legado.app.ui.widget.number.NumberPickerDialog(this)
            .setTitle(getString(R.string.bgm_volume_title, track.assetTypeLabel()))
            .setMinValue(0)
            .setMaxValue(100)
            .setValue(current)
            .show { value ->
                lifecycleScope.launch(Dispatchers.IO) {
                    appDb.readAloudBgmDao.updateTrack(
                        track.copy(
                            defaultVolume = (value / 100f).coerceIn(0f, 1f),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    notifyAudioChanged()
                    launch(Dispatchers.Main) { load() }
                }
            }
    }

    private fun moveSelected() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) {
            toastOnUi(appCtx.getString(R.string.toast_long_press_select, currentAssetLabel))
            return
        }
        moveTracks(ids)
    }

    private fun moveTracks(ids: List<Long>) {
        val options = listOf(ReadAloudBgmGroup(id = 0L, name = ReadAloudBgmGroup.DEFAULT_NAME, assetType = currentAssetType)) +
            groups.filterNot { it.isDefaultGroup() }
        selector(getString(R.string.bgm_select_group), options.map { it.displayName() }) { _, index ->
            val groupId = options[index].id
            lifecycleScope.launch(Dispatchers.IO) {
                appDb.readAloudBgmDao.moveTracks(ids, groupId)
                selectedIds.clear()
                notifyAudioChanged()
                launch(Dispatchers.Main) { load() }
            }
        }
    }

    private fun deleteSelected() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) {
            toastOnUi(appCtx.getString(R.string.toast_long_press_select, currentAssetLabel))
            return
        }
        confirmDelete(ids)
    }

    private fun confirmDelete(ids: List<Long>) {
        alert(getString(R.string.bgm_delete_title, currentAssetLabel)) {
            setMessage(getString(R.string.bgm_delete_message, ids.size, currentAssetLabel))
            okButton {
                lifecycleScope.launch(Dispatchers.IO) {
                    ids.mapNotNull { appDb.readAloudBgmDao.track(it) }.forEach { track ->
                        runCatching { File(track.filePath).delete() }
                    }
                    appDb.readAloudBgmDao.deleteTracks(ids)
                    selectedIds.clear()
                    notifyAudioChanged()
                    launch(Dispatchers.Main) { load() }
                }
            }
            cancelButton()
        }
    }

    private fun String.toSafeFileName(extension: String): String {
        val safe = replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "$currentAssetType.$extension" }
        return if (safe.contains('.')) safe else "$safe.$extension"
    }

    private fun String.toZipPathSegment(): String {
        return replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .ifBlank { ReadAloudBgmGroup.DEFAULT_NAME }
    }

    private fun String.toYamlScalar(): String {
        return "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private fun String.cleanYamlScalar(): String {
        val value = trim()
        if (value.isBlank() || value == "null" || value.startsWith("|")) return ""
        return value.trim('"', '\'')
    }

    private fun fallbackGroupName(entryName: String): String {
        val firstSegment = entryName.split('/').firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != entryName.substringAfterLast('/') }
        return firstSegment ?: getString(R.string.default_group)
    }

    private fun fallbackTrackName(fileName: String): String {
        return fileName.substringBeforeLast('.')
            .replace(Regex("""^\d+[-_ ]*"""), "")
            .trim()
            .ifBlank { fileName.substringBeforeLast('.') }
    }

    private fun fallbackTags(groupName: String, fileName: String): String {
        val bracketTags = Regex("""[（(【\[]([^）)】\]]+)[）)】\]]""")
            .findAll(fileName)
            .map { it.groupValues.getOrNull(1).orEmpty().trim() }
            .filter { it.isNotBlank() }
        return (sequenceOf(groupName.takeIf { it != ReadAloudBgmGroup.DEFAULT_NAME }.orEmpty()) + bracketTags)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")
    }

    private fun String.isSupportedAudioEntry(): Boolean {
        return substringAfterLast('/').isSupportedAudioFileName()
    }

    private fun String.isSupportedAudioFileName(): Boolean {
        val ext = substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in supportedAudioExtensions
    }

    private fun groupName(groupId: Long): String {
        if (groupId == 0L) return getString(R.string.default_group)
        return groups.firstOrNull { it.id == groupId }?.displayName() ?: getString(R.string.default_group)
    }

    private fun buildRows(): List<AudioRow> {
        val tracksByGroup = tracks.groupBy { it.groupId }
        val rowGroups = (listOf(ReadAloudBgmGroup(id = 0L, name = ReadAloudBgmGroup.DEFAULT_NAME, assetType = currentAssetType, sortOrder = Int.MIN_VALUE)) + groups)
            .distinctBy { it.id }
            .filter { group -> group.id != 0L || tracksByGroup.containsKey(0L) || tracks.isEmpty() }
            .sortedWith(compareBy<ReadAloudBgmGroup> { it.sortOrder }.thenBy { it.id })
        if (!expandedGroupsInitialized) {
            expandedGroupIds.clear()
            expandedGroupIds.addAll(rowGroups.filterNot { it.isDefaultGroup() }.map { it.id })
            expandedGroupsInitialized = true
        } else {
            expandedGroupIds.retainAll(rowGroups.map { it.id }.toSet())
        }
        return buildList {
            rowGroups.forEach { group ->
                val groupTracks = tracksByGroup[group.id].orEmpty()
                val expanded = group.id in expandedGroupIds
                add(
                    AudioRow.GroupHeader(
                        group = group,
                        count = groupTracks.size,
                        selectedCount = groupTracks.count { it.id in selectedIds },
                        expanded = expanded
                    )
                )
                if (expanded) {
                    groupTracks.forEach { track ->
                        add(AudioRow.TrackItem(track))
                    }
                }
            }
        }
    }

    private fun toggleGroup(groupId: Long) {
        if (!expandedGroupIds.add(groupId)) {
            expandedGroupIds.remove(groupId)
        }
        adapter.submit(buildRows())
    }

    private fun toggleGroupSelection(groupId: Long) {
        val ids = tracks.filter { it.groupId == groupId }.map { it.id }
        if (ids.isEmpty()) return
        if (ids.all { it in selectedIds }) {
            selectedIds.removeAll(ids.toSet())
        } else {
            selectedIds.addAll(ids)
        }
        load()
    }

    private sealed class AudioRow {
        data class GroupHeader(
            val group: ReadAloudBgmGroup,
            val count: Int,
            val selectedCount: Int,
            val expanded: Boolean
        ) : AudioRow()

        data class TrackItem(val track: ReadAloudBgmTrack) : AudioRow()
    }

    private inner class BgmAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<AudioRow> = emptyList()

        fun submit(value: List<AudioRow>) {
            items = value
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            return when (items[position]) {
                is AudioRow.GroupHeader -> VIEW_TYPE_GROUP
                is AudioRow.TrackItem -> VIEW_TYPE_TRACK
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == VIEW_TYPE_GROUP) {
                val root = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(10.dpToPx(), 10.dpToPx(), 10.dpToPx(), 10.dpToPx())
                    background = UiCorner.actionSelector(
                        parent.context.themeMutedColorOrDefault(),
                        parent.context.themeCardColorOrDefault(),
                        UiCorner.actionRadius(parent.context)
                    )
                }
                val title = TextView(parent.context).apply {
                    textSize = 14f
                    setTextColor(primaryTextColor)
                    typeface = uiTypeface()
                }
                val sub = TextView(parent.context).apply {
                    textSize = 12f
                    setTextColor(secondaryTextColor)
                    typeface = uiTypeface()
                }
                root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                root.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 3.dpToPx()
                })
                root.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 8.dpToPx()
                }
                return GroupHolder(root, title, sub)
            }
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(18.dpToPx(), 12.dpToPx(), 14.dpToPx(), 12.dpToPx())
                background = UiCorner.actionSelector(
                    parent.context.themeCardColorOrDefault(),
                    parent.context.themeMutedColorOrDefault(),
                    UiCorner.panelRadius(parent.context)
                )
            }
            val title = TextView(parent.context).apply {
                textSize = 15f
                setTextColor(primaryTextColor)
                typeface = uiTypeface()
            }
            val sub = TextView(parent.context).apply {
                textSize = 12f
                setTextColor(secondaryTextColor)
                typeface = uiTypeface()
                gravity = Gravity.START
            }
            root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 4.dpToPx()
            })
            root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 16.dpToPx()
                bottomMargin = 10.dpToPx()
            }
            return TrackHolder(root, title, sub)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = items[position]) {
                is AudioRow.GroupHeader -> (holder as GroupHolder).bind(row)
                is AudioRow.TrackItem -> (holder as TrackHolder).bind(row.track)
            }
        }
    }

    private inner class GroupHolder(
        itemView: View,
        private val title: TextView,
        private val sub: TextView
    ) : RecyclerView.ViewHolder(itemView) {

        fun bind(row: AudioRow.GroupHeader) {
            val arrow = if (row.expanded) "▼" else "▶"
            title.text = "$arrow ${row.group.displayName()}"
            sub.text = buildList {
                add(getString(R.string.bgm_group_count, row.count, currentAssetLabel))
                if (row.selectedCount > 0) add(getString(R.string.bgm_selected_count, row.selectedCount))
            }.joinToString(" · ")
            itemView.setOnClickListener { toggleGroup(row.group.id) }
            itemView.setOnLongClickListener {
                toggleGroupSelection(row.group.id)
                true
            }
        }
    }

    private inner class TrackHolder(
        itemView: View,
        private val title: TextView,
        private val sub: TextView
    ) : RecyclerView.ViewHolder(itemView) {

        fun bind(track: ReadAloudBgmTrack) {
            val selected = track.id in selectedIds
            title.text = if (selected) "✓ ${track.displayName()}" else track.displayName()
            sub.text = buildList {
                add(groupName(track.groupId))
                add(track.assetTypeLabel())
                if (track.tags.isNotBlank()) add(track.tags)
                add(getString(R.string.bgm_volume_percent, (track.defaultVolume * 100).toInt().coerceIn(0, 100)))
                if (track.durationMs > 0) add("${track.durationMs / 1000}s")
            }.joinToString(" · ")
            itemView.setOnClickListener {
                if (selectedIds.isNotEmpty()) {
                    toggle(track.id)
                } else {
                    showTrackActions(track)
                }
            }
            itemView.setOnLongClickListener {
                toggle(track.id)
                true
            }
        }

        private fun toggle(id: Long) {
            if (!selectedIds.add(id)) selectedIds.remove(id)
            load()
        }
    }

    companion object {
        private const val MENU_IMPORT = 1
        private const val MENU_EXPORT_PACKAGE = 2
        private const val MENU_ADD_GROUP = 3
        private const val MENU_MANAGE_GROUPS = 4
        private const val VIEW_TYPE_GROUP = 1
        private const val VIEW_TYPE_TRACK = 2
        private const val MAX_AUDIO_PACKAGE_CONFIG_BYTES = 1024 * 1024
        private val supportedAudioExtensions = setOf("mp3", "wav", "m4a", "aac", "ogg", "flac")
    }

    private data class PackageAudioInfo(
        val name: String,
        val groupName: String,
        val tags: String,
        val volume: Float
    )

    private data class PackageExportEntry(
        val track: ReadAloudBgmTrack,
        val groupName: String,
        val entryName: String,
        val file: File
    )
}
