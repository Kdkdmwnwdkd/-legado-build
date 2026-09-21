package io.legado.app.ui.book.read.config

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.widget.SearchView
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityReaderAssetManageBinding
import io.legado.app.databinding.ItemReaderAssetBinding
import io.legado.app.help.reader.ReaderAsset
import io.legado.app.help.reader.ReaderAssetFolder
import io.legado.app.help.reader.ReaderAssetLibrary
import io.legado.app.help.reader.ReaderAssetReferences
import io.legado.app.help.reader.ReaderAssets
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.themeMutedColorOrDefault
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.FileDoc
import io.legado.app.utils.postEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One shared library, with optional image/font picker modes used by both editors. */
class ReaderAssetManageActivity : BaseActivity<ActivityReaderAssetManageBinding>() {
    override val binding by viewBinding(ActivityReaderAssetManageBinding::inflate)
    private val adapter = AssetAdapter()
    private val navigation = arrayListOf<FileDoc>()
    private var library = ReaderAssetLibrary()
    private var localFiles: List<FileDoc> = emptyList()
    private var query = ""
    private var filter = "all"
    private var loaded = false
    private var busy = false
    private var loading = true
    private var loadJob: Job? = null
    private val pickKind get() = intent.getStringExtra("pickKind").orEmpty().takeIf { it in setOf("font", "image", "any") }.orEmpty()
    private val canInteract get() = loaded && !busy && !loading
    private val importFile = registerForActivityResult(HandleFileContract()) { result -> result.uri?.let(::importOne) }
    private val folderPicker = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri -> work {
            withContext(Dispatchers.IO) {
                if (uri.scheme == "content") runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ReaderAssets.addFolder(uri)
            }
            filter = "folder"; navigation.clear()
        } }
    }
    private val exportFile = registerForActivityResult(HandleFileContract()) { result ->
        if (result.uri != null) toastOnUi(R.string.export_success)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        filter = savedInstanceState?.getString("filter") ?: pickKind.takeIf { it in setOf("image", "font") } ?: "all"
        query = savedInstanceState?.getString("query").orEmpty()
        val directoryNames = savedInstanceState?.getStringArrayList("directoryNames").orEmpty()
        val directoryUris = savedInstanceState?.getStringArrayList("directoryUris").orEmpty()
        directoryNames.zip(directoryUris).forEach { (name, uri) ->
            navigation.add(FileDoc(name, true, 0L, 0L, uri.toUri()))
        }
        binding.run {
            titleBar.title = getString(when (pickKind) {
                "font" -> R.string.reader_assets_font_select
                "image" -> R.string.reader_assets_image_select
                else -> R.string.reader_assets_title
            })
            searchView.maxWidth = Int.MAX_VALUE
            searchView.findViewById<TextView>(androidx.appcompat.R.id.search_src_text).filters = arrayOf(InputFilter.LengthFilter(256))
            searchView.setQuery(query, false)
            searchView.clearFocus()
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(value: String?): Boolean { searchView.clearFocus(); return true }
                override fun onQueryTextChange(value: String?): Boolean { query = value.orEmpty(); render(); return true }
            })
            listOf(filterAll to "all", filterImage to "image", filterFont to "font", filterFolder to "folder").forEach { (button, kind) ->
                button.visibility = if ((pickKind == "font" && kind in setOf("image", "all")) ||
                    (pickKind == "image" && kind in setOf("font", "all"))) View.GONE else View.VISIBLE
                button.setOnClickListener {
                    if (canInteract) { filter = kind; navigation.clear(); localFiles = emptyList(); render() }
                }
            }
            listOf(btnImport, btnFolder, btnUp, btnDefault).forEach {
                it.background = UiCorner.actionSelector(themeCardColorOrDefault(), themeMutedColorOrDefault(), UiCorner.actionRadius(this@ReaderAssetManageActivity))
                it.setTextColor(accentColor)
            }
            btnDefault.visibility = if (pickKind in setOf("font", "image")) View.VISIBLE else View.GONE
            btnDefault.setText(if (pickKind == "font") R.string.reader_assets_follow_font else R.string.reader_assets_no_image)
            btnDefault.setOnClickListener {
                if (canInteract) { setResult(RESULT_OK, Intent().putExtra("assetId", "")); finish() }
            }
            btnUp.setOnClickListener { up() }
            btnImport.setOnClickListener { pickFile() }
            btnFolder.setOnClickListener { pickFolder() }
            listOf(tvHint, tvSummary, tvEmpty).forEach { it.applyUiLabelStyle(this@ReaderAssetManageActivity); it.setTextColor(secondaryTextColor) }
            recyclerView.layoutManager = LinearLayoutManager(this@ReaderAssetManageActivity)
            recyclerView.adapter = adapter
            (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            root.applyUiBodyTypefaceDeep(this@ReaderAssetManageActivity.uiTypeface())
        }
        onBackPressedDispatcher.addCallback(this) { if (navigation.isNotEmpty() && !busy) up() else finish() }
        render()
    }

    override fun onResume() { super.onResume(); if (!busy) reload() }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 10, 0, R.string.reader_assets_import_folder)
        menu.add(0, 11, 1, "刷新")
        menu.add(0, 12, 2, R.string.help)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> { if (navigation.isNotEmpty() && !busy) up() else finish() }
            10 -> importFolder()
            11 -> if (!busy) reload()
            12 -> showComposeConfirmDialog(getString(R.string.reader_assets_title), getString(R.string.reader_assets_hint) + "\n\n" +
                getString(R.string.reader_assets_supported), showNegative = false, onPositive = {})
            else -> return super.onCompatOptionsItemSelected(item)
        }
        return true
    }

    private fun pickFile() {
        if (!canInteract) return
        importFile.launch {
            mode = HandleFileContract.FILE; title = getString(R.string.reader_assets_import); showUploadUrl = false
            allowExtensions = when (pickKind) {
                "font" -> arrayOf("ttf", "otf", "ttc", "woff", "woff2")
                "image" -> arrayOf("png", "jpg", "jpeg", "gif", "webp")
                else -> ReaderAssets.extensions
            }
        }
    }

    private fun pickFolder() {
        if (!canInteract) return
        folderPicker.launch { mode = HandleFileContract.DIR; title = getString(R.string.reader_assets_add_folder); showUploadUrl = false }
    }

    private fun importOne(uri: Uri) = work {
        val asset = withContext(Dispatchers.IO) { ReaderAssets.importUri(uri) }
        changed()
        if (pickKind.isNotBlank()) select(asset) else toastOnUi("已导入：${asset.name}")
    }

    private fun importFolder() {
        if (!canInteract) return
        if (navigation.isEmpty()) { toastOnUi("请先打开一个来源文件夹"); return }
        val files = filteredEntries().mapNotNull { it.local }.filterNot { it.isDir }
        if (files.isEmpty()) { toastOnUi(R.string.reader_assets_search_empty); return }
        if (files.size > 100) { toastOnUi("一次最多导入 100 个素材，请搜索后分批导入"); return }
        showComposeConfirmDialog(getString(R.string.reader_assets_import_folder),
            getString(R.string.reader_assets_import_folder_hint) + "\n\n${files.size} 个文件", onPositive = { work {
                val failures = arrayListOf<String>()
                val count = withContext(Dispatchers.IO) {
                    files.count { file -> runCatching { ReaderAssets.importUri(file.uri) }
                        .onFailure { failures.add(file.name + "：" + it.localizedMessage) }.isSuccess }
                }
                changed()
                showComposeConfirmDialog(getString(R.string.reader_assets_import),
                    "已导入或合并 $count 个素材" + if (failures.isEmpty()) "" else "\n\n" + failures.take(10).joinToString("\n"),
                    showNegative = false, onPositive = {})
            } })
    }

    private fun openFolder(entry: Entry) {
        if (!canInteract) return
        work {
            val folder = withContext(Dispatchers.IO) {
                entry.local ?: FileDoc.fromUri(requireNotNull(entry.folder).uri.toUri(), true)
            }
            // Read before changing navigation, so an expired grant leaves a usable screen.
            val children = withContext(Dispatchers.IO) { ReaderAssets.browse(folder) }
            navigation.add(folder); localFiles = children; filter = "folder"
            binding.searchView.setQuery("", false)
        }
    }

    private fun up() {
        if (busy || navigation.isEmpty()) return
        navigation.removeAt(navigation.lastIndex)
        binding.searchView.setQuery("", false)
        reload()
    }

    private fun select(asset: ReaderAsset) {
        if (pickKind.isBlank()) { copyReference(asset); return }
        if (pickKind != "any" && asset.kind != pickKind) { toastOnUi("请选择${if (pickKind == "font") "字体" else "图片"}素材"); return }
        setResult(RESULT_OK, Intent().putExtra("assetId", asset.id).putExtra("kind", asset.kind))
        finish()
    }

    private fun actions(asset: ReaderAsset) {
        if (!canInteract) return
        showComposeChoiceListDialog(asset.name, listOf(
            getString(R.string.reader_assets_preview), getString(R.string.reader_assets_rename),
            getString(R.string.reader_assets_references), getString(R.string.export_str),
            getString(if (asset.kind == "font") R.string.reader_assets_copy_font else R.string.reader_assets_copy_url), getString(R.string.delete)
        )) { index -> when (index) {
            0 -> preview(asset)
            1 -> showComposeTextInputDialog(getString(R.string.reader_assets_rename), initialValue = asset.name,
                message = getString(R.string.reader_assets_rename_hint), validateInput = { it.isNotBlank() && it.length <= 256 },
                onPositive = { name -> work { withContext(Dispatchers.IO) { ReaderAssets.store.rename(asset.id, name) } } })
            2 -> work {
                val used = withContext(Dispatchers.IO) { ReaderAssets.references(asset.id) }
                showComposeConfirmDialog(getString(R.string.reader_assets_references), used.joinToString("\n").ifEmpty {
                    getString(R.string.reader_assets_unused)
                }, showNegative = false, messageInContent = true, onPositive = {})
            }
            3 -> work {
                val bytes = withContext(Dispatchers.IO) { ReaderAssets.store.verifiedBytes(asset.id) }
                exportFile.launch {
                    mode = HandleFileContract.EXPORT; showUploadUrl = false
                    val name = asset.name.takeIf { it.endsWith(".${asset.extension}", true) } ?: "${asset.name}.${asset.extension}"
                    fileData = HandleFileContract.FileData(name, bytes, asset.mimeType)
                }
            }
            4 -> copyReference(asset)
            5 -> checkDelete(asset)
        } }
    }

    private fun checkDelete(asset: ReaderAsset) = work {
        val used = withContext(Dispatchers.IO) { ReaderAssets.references(asset.id) }
        if (used.isNotEmpty()) showComposeConfirmDialog(getString(R.string.reader_assets_references),
            getString(R.string.reader_assets_reference_block) + "\n\n" + used.joinToString("\n"), showNegative = false,
            messageInContent = true, onPositive = {})
        else showComposeConfirmDialog(getString(R.string.delete), asset.name + "\n\n" + getString(R.string.reader_assets_delete_hint),
            dangerPositive = true, onPositive = { work {
                withContext(Dispatchers.IO) { ReaderAssets.deleteUnused(asset.id) }; changed()
            } })
    }

    private fun folderActions(folder: ReaderAssetFolder) {
        if (!canInteract) return
        showComposeChoiceListDialog(folder.name, listOf("打开文件夹", getString(R.string.reader_assets_remove_folder))) {
            if (it == 0) openFolder(Entry(folder = folder))
            else showComposeConfirmDialog(getString(R.string.reader_assets_remove_folder),
                getString(R.string.reader_assets_remove_folder_hint), onPositive = { work {
                    withContext(Dispatchers.IO) { ReaderAssets.store.removeFolder(folder.id) }
                } })
        }
    }

    private fun preview(asset: ReaderAsset) = startActivity<ReaderAssetPreviewActivity> { putExtra("assetId", asset.id) }
    private fun copyReference(asset: ReaderAsset) {
        sendToClip(if (asset.kind == "font") "font-family:'${ReaderAssetReferences.fontFamily(asset.id)}';" else ReaderAssetReferences.url(asset.id))
    }
    private fun changed() { postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5)) }

    private suspend fun readContent() {
        val directory = navigation.lastOrNull()
        val result = withContext(Dispatchers.IO) { ReaderAssets.store.library() to (directory?.let(ReaderAssets::browse) ?: emptyList()) }
        library = result.first; localFiles = result.second; loaded = true
    }
    private fun reload() {
        if (busy) return
        loadJob?.cancel(); loading = true; render()
        loadJob = lifecycleScope.launch {
            try { readContent() }
            catch (error: Exception) { if (error is CancellationException) throw error; toastOnUi(error.localizedMessage) }
            finally { if (isActive) { loading = false; render() } }
        }
    }
    private fun work(action: suspend () -> Unit) {
        if (busy) return
        loadJob?.cancel(); loading = false; busy = true; render()
        lifecycleScope.launch {
            try {
                // A file/index commit may finish after rotation; the next instance reloads it.
                action()
                if (!isFinishing) readContent()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                toastOnUi(error.localizedMessage)
            } finally { if (isActive && !isDestroyed) { busy = false; render() } }
        }
    }

    private fun filteredEntries(): List<Entry> {
        val entries = when {
            navigation.isNotEmpty() -> localFiles.filter { file -> file.isDir || pickKind !in setOf("font", "image") ||
                (file.name.substringAfterLast('.', "").lowercase() in setOf("ttf", "otf", "ttc", "woff", "woff2")) == (pickKind == "font")
            }.map { Entry(local = it) }
            filter == "folder" -> library.folders.map { Entry(folder = it) }
            else -> library.assets.filter { (filter == "all" || it.kind == filter) &&
                (pickKind !in setOf("font", "image") || it.kind == pickKind) }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                .map { Entry(asset = it) }
        }
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return entries.filter { entry -> terms.all { (entry.name + " " + entry.asset?.extension.orEmpty()).contains(it, true) } }
    }

    private fun render() {
        if (isDestroyed) return
        val entries = filteredEntries()
        adapter.submit(entries)
        binding.run {
            listOf(filterAll to "all", filterImage to "image", filterFont to "font", filterFolder to "folder").forEach { (button, kind) ->
                button.isEnabled = canInteract
                button.background = UiCorner.actionSelector(if (filter == kind) themeMutedColorOrDefault() else Color.TRANSPARENT,
                    themeMutedColorOrDefault(), UiCorner.actionRadius(this@ReaderAssetManageActivity))
                button.setTextColor(if (filter == kind) accentColor else secondaryTextColor)
                button.isSelected = filter == kind
            }
            listOf(btnImport, btnFolder, btnDefault, btnUp).forEach { it.isEnabled = canInteract }
            btnUp.visibility = if (navigation.isEmpty()) View.GONE else View.VISIBLE
            tvHint.setText(if (filter == "folder") R.string.reader_assets_folder_hint else R.string.reader_assets_hint)
            tvSummary.text = if (busy || loading) getString(R.string.reader_assets_busy) else navigation.lastOrNull()?.let { "${it.name} · ${entries.size} 项" }
                ?: "${entries.size} 项 · 素材库 ${Formatter.formatFileSize(this@ReaderAssetManageActivity, library.assets.sumOf { it.size })}"
            tvEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            tvEmpty.setText(when {
                !loaded && (busy || loading) -> R.string.reader_assets_loading
                !loaded -> R.string.reader_assets_load_failed
                query.isNotBlank() || navigation.isNotEmpty() -> R.string.reader_assets_search_empty
                filter == "folder" -> R.string.reader_assets_folder_empty
                else -> R.string.reader_assets_empty
            })
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("query", query); outState.putString("filter", filter)
        outState.putStringArrayList("directoryNames", ArrayList(navigation.map { it.name }))
        outState.putStringArrayList("directoryUris", ArrayList(navigation.map { it.uri.toString() }))
        super.onSaveInstanceState(outState)
    }
    private data class Entry(val asset: ReaderAsset? = null, val folder: ReaderAssetFolder? = null, val local: FileDoc? = null) {
        val key get() = asset?.id ?: folder?.id ?: local?.uri.toString()
        val name get() = asset?.name ?: folder?.name ?: local?.name.orEmpty()
        val isFolder get() = folder != null || local?.isDir == true
    }

    private inner class AssetAdapter : RecyclerView.Adapter<AssetAdapter.Holder>() {
        var items = emptyList<Entry>()
        init { stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY }
        fun submit(next: List<Entry>) {
            val old = items
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = next.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) = old[oldItemPosition].key == next[newItemPosition].key
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) = old[oldItemPosition] == next[newItemPosition]
            })
            items = next; diff.dispatchUpdatesTo(this)
        }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemReaderAssetBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
        override fun onViewRecycled(holder: Holder) { holder.clear(); super.onViewRecycled(holder) }
        inner class Holder(private val row: ItemReaderAssetBinding) : RecyclerView.ViewHolder(row.root) {
            private var fontJob: Job? = null
            fun clear() { fontJob?.cancel(); Glide.with(row.ivPreview).clear(row.ivPreview) }
            fun bind(entry: Entry) = row.run {
                clear()
                root.background = UiCorner.panelRounded(this@ReaderAssetManageActivity, themeCardColorOrDefault(), UiCorner.panelRadius(this@ReaderAssetManageActivity))
                tvName.text = entry.name
                tvName.applyUiSectionTitleStyle(this@ReaderAssetManageActivity)
                tvInfo.applyUiLabelStyle(this@ReaderAssetManageActivity); tvInfo.setTextColor(secondaryTextColor)
                val asset = entry.asset
                val isFont = asset?.kind == "font" || entry.local?.name?.substringAfterLast('.')?.lowercase() in setOf("ttf", "otf", "ttc", "woff", "woff2")
                tvInfo.text = when {
                    asset != null -> listOf(if (asset.id == intent.getStringExtra("selectedId")) getString(R.string.reader_assets_current) else asset.extension.uppercase(),
                        if (asset.kind == "image") "${asset.width} × ${asset.height}" else getString(R.string.reader_assets_fonts),
                        Formatter.formatFileSize(this@ReaderAssetManageActivity, asset.size)).joinToString(" · ")
                    entry.isFolder -> if (entry.folder != null) "本地来源 · 点击浏览" else "子文件夹"
                    else -> "待导入 · " + Formatter.formatFileSize(this@ReaderAssetManageActivity, entry.local?.size ?: 0)
                }
                tvPreview.visibility = if (isFont) View.VISIBLE else View.GONE
                ivPreview.visibility = if (isFont) View.GONE else View.VISIBLE
                tvPreview.setText(R.string.reader_assets_font_tile_sample); tvPreview.typeface = this@ReaderAssetManageActivity.uiTypeface()
                when {
                    entry.isFolder -> ivPreview.setImageResource(android.R.drawable.ic_menu_agenda)
                    isFont && asset != null -> fontJob = lifecycleScope.launch {
                        val face = withContext(Dispatchers.IO) { ReaderAssets.typeface(asset.id) }
                        if (isActive && face != null) tvPreview.typeface = face
                    }
                    !isFont -> Glide.with(ivPreview).load(asset?.let { File(ReaderAssets.store.directory, "files/${it.id}") } ?: entry.local?.uri)
                        .fitCenter().error(android.R.drawable.ic_menu_report_image).into(ivPreview)
                }
                listOf(btnUse, btnMore).forEach {
                    it.background = UiCorner.actionSelector(Color.TRANSPARENT, themeMutedColorOrDefault(), UiCorner.actionRadius(this@ReaderAssetManageActivity))
                    it.typeface = this@ReaderAssetManageActivity.uiTypeface()
                }
                btnUse.setTextColor(accentColor); btnMore.setTextColor(primaryTextColor)
                btnUse.text = when { entry.isFolder -> "打开"; entry.local != null -> "导入"; pickKind.isNotBlank() -> getString(R.string.reader_assets_select); else -> getString(R.string.reader_assets_use) }
                btnMore.visibility = if (entry.local != null) View.GONE else View.VISIBLE
                btnUse.setOnClickListener {
                    if (canInteract) when { entry.isFolder -> openFolder(entry); entry.local != null -> importOne(entry.local.uri); asset != null -> select(asset) }
                }
                btnMore.setOnClickListener { if (asset != null) actions(asset) else entry.folder?.let(::folderActions) }
                root.setOnClickListener { if (canInteract) when { entry.isFolder -> openFolder(entry); asset != null -> preview(asset); entry.local != null -> importOne(entry.local.uri) } }
            }
        }
    }
}
