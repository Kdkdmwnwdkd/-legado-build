package io.legado.app.ui.book.read.config

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityHighlightRuleManageBinding
import io.legado.app.databinding.ItemThemePackageBinding
import io.legado.app.help.book.highlight.HighlightImport
import io.legado.app.help.book.highlight.HighlightMatcher
import io.legado.app.help.book.highlight.HighlightRule
import io.legado.app.help.book.highlight.HighlightRuleSearch
import io.legado.app.help.book.highlight.HighlightRules
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
import io.legado.app.model.ReadBook
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.utils.sendToClip
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Uses the same cards, action buttons and drag ordering as paragraph-rule management. */
class HighlightRuleManageActivity : BaseActivity<ActivityHighlightRuleManageBinding>() {
    override val binding by viewBinding(ActivityHighlightRuleManageBinding::inflate)
    private val adapter = RuleAdapter()
    private var allRules: List<HighlightRule> = emptyList()
    private var query = ""
    private var bookUrl: String? = null
    private var busy = false
    private var loaded = false
    private var loading = true
    private var dragging = false
    private var orderChanged = false
    private var loadVersion = 0
    private var loadJob: Job? = null
    private val canInteract get() = loaded && !loading && !busy && !dragging
    private val dragCallback = object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun isLongPressDragEnabled() = canInteract
        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) =
            if (loaded && !loading && !busy) super.getMovementFlags(recyclerView, viewHolder) else 0

        override fun onMove(recyclerView: RecyclerView, source: RecyclerView.ViewHolder,
                            target: RecyclerView.ViewHolder) = swap(source.bindingAdapterPosition, target.bindingAdapterPosition)

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                dragging = true
                orderChanged = false
                cancelLoad()
                binding.searchView.clearFocus()
                updateControls()
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            finishDrag()
        }
    }
    private val importFile = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let(::importUri)
    }
    private val exportFile = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            if (uri.scheme == "http" || uri.scheme == "https") {
                showComposeConfirmDialog(getString(R.string.upload_url), uri.toString(),
                    positiveText = getString(R.string.copy_text), onPositive = { sendToClip(uri.toString()) })
            } else toastOnUi(R.string.export_success)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        bookUrl = intent.getStringExtra("bookUrl") ?: ReadBook.book?.bookUrl
        query = savedInstanceState?.getString("query").orEmpty()
        binding.run {
            titleBar.title = getString(R.string.highlight_rule_manage)
            searchView.maxWidth = Int.MAX_VALUE
            searchView.findViewById<TextView>(androidx.appcompat.R.id.search_src_text).filters =
                arrayOf(InputFilter.LengthFilter(512))
            searchView.setQuery(query, false)
            searchView.clearFocus()
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(value: String?): Boolean {
                    searchView.clearFocus()
                    return true
                }
                override fun onQueryTextChange(value: String?): Boolean {
                    query = value.orEmpty()
                    if (!dragging && !busy) {
                        renderRules()
                        recyclerView.scrollToPosition(0)
                    }
                    return true
                }
            })
            btnAdd.text = getString(R.string.add)
            btnAdd.background = UiCorner.actionSelector(themeCardColorOrDefault(), themeMutedColorOrDefault(),
                UiCorner.actionRadius(this@HighlightRuleManageActivity))
            btnAdd.setOnClickListener { addActions() }
            tvSummary.applyUiLabelStyle(this@HighlightRuleManageActivity)
            tvSummary.setTextColor(secondaryTextColor)
            tvEmpty.applyUiLabelStyle(this@HighlightRuleManageActivity)
            tvEmpty.setTextColor(secondaryTextColor)
            recyclerView.layoutManager = LinearLayoutManager(this@HighlightRuleManageActivity)
            recyclerView.adapter = adapter
            (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            ItemTouchHelper(dragCallback).attachToRecyclerView(recyclerView)
            root.applyUiBodyTypefaceDeep(this@HighlightRuleManageActivity.uiTypeface())
        }
        updateControls()
        if (savedInstanceState == null) intent.data?.let(::importUri)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.import_str)
        menu.add(0, 2, 1, R.string.export_str)
        menu.add(0, 3, 2, R.string.help)
        menu.add(0, 4, 3, R.string.reader_assets_title)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> pickFile()
            2 -> exportCurrentRules()
            3 -> showComposeConfirmDialog(getString(R.string.highlight_rule_manage),
                getString(R.string.highlight_rule_help) + "\n\n" + getString(R.string.highlight_rule_render_hint),
                positiveText = getString(R.string.ok), showNegative = false, onPositive = {})
            4 -> startActivity<ReaderAssetManageActivity>()
            else -> return super.onCompatOptionsItemSelected(item)
        }
        return true
    }

    private fun addActions() = showComposeChoiceListDialog(getString(R.string.highlight_rule_manage),
        listOf(getString(R.string.add), getString(R.string.import_str))) {
        when (it) { 0 -> edit(null); 1 -> pickFile() }
    }

    private fun pickFile() {
        if (!canInteract) return
        importFile.launch {
            mode = HandleFileContract.FILE
            title = getString(R.string.highlight_rule_manage)
            allowExtensions = arrayOf("red")
        }
    }

    private fun importUri(uri: Uri) {
        work {
            val report = withContext(Dispatchers.IO) { HighlightImport.importUri(uri, bookUrl) }
            showComposeConfirmDialog(getString(R.string.import_str), report.summary(),
                positiveText = getString(R.string.ok), showNegative = false, onPositive = {})
        }
    }

    private fun edit(rule: HighlightRule?) {
        if (!canInteract) return
        startActivity<HighlightRuleEditActivity> {
            rule?.let { putExtra("id", it.id) }
            bookUrl?.let { putExtra("bookUrl", it) }
        }
    }

    private fun toggle(rule: HighlightRule) {
        if (!canInteract) return
        val bindHint = getString(R.string.highlight_rule_bind_hint)
        work { withContext(Dispatchers.IO) {
            HighlightRules.store.update(rule.id) { current ->
                if (!current.enabled) {
                    HighlightMatcher.validationError(current)?.let { error(it) }
                    require(current.global || current.bookUrl != null) { bindHint }
                }
                current.copy(enabled = !current.enabled)
            }
        } }
    }

    private fun actions(rule: HighlightRule) {
        if (!canInteract) return
        val labels = listOf(getString(R.string.edit), getString(R.string.highlight_rule_scope),
            getString(R.string.export_str), getString(R.string.highlight_rule_notice), getString(R.string.delete))
        showComposeChoiceListDialog(rule.displayName(), labels) { index ->
            when (index) {
                0 -> edit(rule)
                1 -> showComposeChoiceListDialog(getString(R.string.highlight_rule_scope),
                    listOf(getString(R.string.highlight_rule_global), getString(R.string.highlight_rule_this_book))) {
                    if (it == 1 && bookUrl == null) toastOnUi(R.string.highlight_rule_bind_hint)
                    else work { withContext(Dispatchers.IO) {
                        val global = it == 0
                        HighlightRules.store.update(rule.id) { current ->
                            current.copy(global = global, bookUrl = if (global) null else bookUrl)
                        }
                    } }
                }
                2 -> exportRules(listOf(rule))
                3 -> showComposeConfirmDialog(rule.displayName(),
                    listOfNotNull(rule.importWarning.takeIf { it.isNotBlank() }, HighlightMatcher.validationError(rule),
                        getString(R.string.highlight_rule_render_hint)).joinToString("\n\n"),
                    positiveText = getString(R.string.ok), showNegative = false, onPositive = {})
                4 -> showComposeConfirmDialog(getString(R.string.delete), getString(R.string.sure_del) + "\n" + rule.displayName(),
                    onPositive = { work { withContext(Dispatchers.IO) { HighlightRules.store.delete(rule.id) } } })
            }
        }
    }

    private fun exportRules(rules: List<HighlightRule>) {
        if (!canInteract || rules.isEmpty()) return
        work(changesRules = false) {
            val bytes = withContext(Dispatchers.IO) { HighlightRules.store.export(rules) }
            exportFile.launch {
                mode = HandleFileContract.EXPORT
                fileData = HandleFileContract.FileData("高亮规则.red", bytes, "application/octet-stream")
            }
        }
    }

    private fun exportCurrentRules() {
        if (!canInteract || allRules.isEmpty()) return
        if (query.isBlank()) {
            exportRules(allRules)
            return
        }
        val all = allRules
        val results = adapter.items
        showComposeChoiceListDialog(getString(R.string.export_str), listOf(
            getString(R.string.highlight_rule_export_all, all.size),
            getString(R.string.highlight_rule_export_results, results.size)
        )) { index ->
            if (index == 0) exportRules(all)
            else if (results.isEmpty()) toastOnUi(R.string.highlight_rule_search_empty)
            else exportRules(results)
        }
    }

    private fun work(changesRules: Boolean = true, block: suspend () -> Unit) {
        if (busy || dragging) return
        busy = true
        cancelLoad()
        updateControls()
        lifecycleScope.launch {
            try {
                block()
                if (changesRules) setResult(RESULT_OK)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                toastOnUi(error.localizedMessage ?: getString(R.string.wrong_format))
            } finally {
                try {
                    // Keep actions blocked until the list reflects the completed write.
                    if (isActive) acceptRules(withContext(Dispatchers.IO) { HighlightRules.store.all() })
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    toastOnUi(error.localizedMessage)
                } finally {
                    busy = false
                    updateControls()
                }
            }
        }
    }

    private fun load() {
        if (busy || dragging) return
        cancelLoad()
        loading = true
        updateControls()
        val version = loadVersion
        loadJob = lifecycleScope.launch {
            try {
                val rules = withContext(Dispatchers.IO) { HighlightRules.store.all() }
                if (version == loadVersion) acceptRules(rules)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                toastOnUi(error.localizedMessage)
            } finally {
                if (version == loadVersion) {
                    loading = false
                    renderRules()
                    updateControls()
                }
            }
        }
    }

    private fun cancelLoad() {
        loadVersion++
        loadJob?.cancel()
        loadJob = null
        loading = false
    }

    private fun acceptRules(rules: List<HighlightRule>) {
        allRules = rules
        loaded = true
        renderRules()
    }

    private fun renderRules() {
        if (isDestroyed || dragging) return
        val visible = HighlightRuleSearch.filter(allRules, query)
        adapter.submit(visible)
        binding.tvSummary.text = if (query.isBlank()) getString(R.string.highlight_rule_summary,
            allRules.size, allRules.count { it.enabled }) else
            getString(R.string.highlight_rule_search_summary, visible.size, allRules.size)
        binding.tvEmpty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.setText(when {
            !loaded && loading -> R.string.highlight_rule_loading
            !loaded -> R.string.highlight_rule_load_failed
            query.isNotBlank() -> R.string.highlight_rule_search_empty
            else -> R.string.highlight_rule_empty
        })
    }

    private fun updateControls() {
        if (isDestroyed) return
        binding.btnAdd.isEnabled = canInteract
    }

    private fun swap(srcPosition: Int, targetPosition: Int): Boolean {
        if (busy || loading || srcPosition !in adapter.items.indices || targetPosition !in adapter.items.indices) return false
        if (srcPosition == targetPosition) return false
        adapter.items = adapter.items.toMutableList().apply { add(targetPosition, removeAt(srcPosition)) }
        orderChanged = true
        adapter.notifyItemMoved(srcPosition, targetPosition)
        return true
    }

    private fun finishDrag() {
        dragging = false
        if (!orderChanged) {
            load()
            return
        }
        orderChanged = false
        val ids = adapter.items.map { it.id }
        work { withContext(Dispatchers.IO) { HighlightRules.store.reorder(ids) } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("query", query)
        super.onSaveInstanceState(outState)
    }

    private inner class RuleAdapter : RecyclerView.Adapter<RuleAdapter.Holder>() {
        init { stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY }
        var items: List<HighlightRule> = emptyList()
        fun submit(next: List<HighlightRule>) {
            val previous = items
            val changes = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = previous.size
                override fun getNewListSize() = next.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    previous[oldItemPosition].id == next[newItemPosition].id
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    previous[oldItemPosition] == next[newItemPosition]
            })
            items = next
            changes.dispatchUpdatesTo(this)
        }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemThemePackageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        inner class Holder(private val row: ItemThemePackageBinding) : RecyclerView.ViewHolder(row.root) {
            fun bind(rule: HighlightRule) = row.run {
                root.background = UiCorner.panelRounded(this@HighlightRuleManageActivity,
                    themeCardColorOrDefault(), UiCorner.panelRadius(this@HighlightRuleManageActivity))
                cardPreview.visibility = View.GONE
                tvSource.visibility = View.GONE
                (layInfo.layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.marginStart = 0; layInfo.layoutParams = it }
                tvName.text = rule.displayName()
                val scope = when {
                    rule.global -> getString(R.string.highlight_rule_global)
                    rule.bookUrl == bookUrl && bookUrl != null -> getString(R.string.highlight_rule_this_book)
                    rule.bookUrl == null -> getString(R.string.highlight_rule_unbound)
                    else -> getString(R.string.highlight_rule_other_book)
                }
                tvInfo.text = listOf(rule.groupName, scope,
                    if (rule.titleOnly) getString(R.string.highlight_rule_title) else "",
                    if (rule.importWarning.isNotBlank()) getString(R.string.highlight_rule_has_notice) else "")
                    .filter { it.isNotBlank() }.joinToString(" · ")
                tvName.applyUiSectionTitleStyle(this@HighlightRuleManageActivity)
                tvInfo.applyUiLabelStyle(this@HighlightRuleManageActivity)
                tvInfo.setTextColor(secondaryTextColor)
                listOf(btnApply, btnEdit, btnMore).forEach {
                    it.background = UiCorner.actionSelector(Color.TRANSPARENT, themeMutedColorOrDefault(),
                        UiCorner.actionRadius(this@HighlightRuleManageActivity))
                    it.typeface = this@HighlightRuleManageActivity.uiTypeface()
                }
                btnApply.text = getString(if (rule.enabled) R.string.disable else R.string.enable)
                btnApply.setTextColor(accentColor)
                btnEdit.text = getString(R.string.edit)
                btnEdit.setTextColor(primaryTextColor)
                btnMore.text = getString(R.string.more)
                btnMore.setTextColor(primaryTextColor)
                btnApply.setOnClickListener { toggle(rule) }
                btnEdit.setOnClickListener { edit(rule) }
                btnMore.setOnClickListener { actions(rule) }
                root.setOnClickListener { actions(rule) }
            }
        }
    }
}
