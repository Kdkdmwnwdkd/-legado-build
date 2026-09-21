package io.legado.app.ui.book.toc.rule

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.databinding.ActivityTxtTocRuleBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.ui.association.ImportTxtTocRuleDialog
import io.legado.app.ui.association.showShibbolethDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.widget.compose.replaceByIndex
import io.legado.app.ui.widget.compose.replaceFirst
import io.legado.app.ui.widget.compose.replaceMatching
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.ShibbolethCodec
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.launch
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class TxtTocRuleActivity : VMBaseActivity<ActivityTxtTocRuleBinding, TxtTocRuleViewModel>(),
    SelectActionBar.CallBack,
    TxtTocRuleEditComposeDialog.Callback,
    MenuItem.OnMenuItemClickListener {

    override val viewModel by viewModels<TxtTocRuleViewModel>()
    override val binding by viewBinding(ActivityTxtTocRuleBinding::inflate)

    private val rulesState = mutableStateListOf<TxtTocRule>()
    private val selectedIds = mutableStateOf<Set<Long>>(emptySet())
    private val importTocRuleKey = "tocRuleUrl"

    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        showDialogFragment(ImportTxtTocRuleDialog(it))
    }
    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportTxtTocRuleDialog(uri.toString()))
        }
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            val uriStr = uri.toString()
            val summary = if (uriStr.isAbsUrl()) {
                DirectLinkUpload.getSummary()
            } else {
                null
            }
            showComposeConfirmDialog(
                title = getString(R.string.export_success),
                message = summary,
                positiveText = getString(R.string.copy_text),
                negativeText = getString(R.string.cancel),
                neutralText = getString(R.string.shibboleth)
                    .takeIf { ShibbolethCodec.canEncodeUrl(uriStr) },
                onPositive = { sendToClip(uriStr) },
                onNeutral = { showShibbolethDialog(uriStr, ShibbolethCodec.TOC_RULE) }
            )
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeContent()
        initData()
    }

    private fun initComposeContent() {
        binding.titleBar.visibility = View.GONE
        binding.selectActionBar.visibility = View.GONE
        val container = binding.recyclerView.parent as? ViewGroup ?: return
        val index = container.indexOfChild(binding.recyclerView)
        container.removeView(binding.recyclerView)
        val cv = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setContent {
                AppManagementScaffold(
                    title = getString(R.string.txt_toc_rule),
                    selectedCount = selectedIds.value.size,
                    totalCount = rulesState.size,
                    topActions = listOf(
                        AppManagementAction(
                            text = getString(R.string.add),
                            iconRes = R.drawable.ic_add,
                            onClick = { showDialogFragment(TxtTocRuleEditComposeDialog.create()) }
                        ),
                        AppManagementAction(
                            text = getString(R.string.more_menu),
                            iconRes = R.drawable.ic_more_vert,
                            menuActions = ::pageMenuActions
                        )
                    ),
                    bottomActions = listOf(
                        AppManagementAction(
                            text = getString(R.string.enable_selection),
                            onClick = ::enableSelected
                        ),
                        AppManagementAction(
                            text = getString(R.string.disable_selection),
                            onClick = ::disableSelected
                        ),
                        AppManagementAction(
                            text = getString(R.string.export_selection),
                            onClick = ::exportSelected
                        ),
                        AppManagementAction(
                            text = getString(R.string.delete),
                            danger = true,
                            onClick = ::delSourceDialog
                        )
                    ),
                    onBack = { finish() },
                    onSelectAll = { selectAll(true) },
                    onInvertSelection = { revertSelection() }
                ) {
                    TxtTocRuleScreen(
                        rules = rulesState,
                        selectedIds = selectedIds.value,
                        isSelectMode = selectedIds.value.isNotEmpty(),
                        onReorder = viewModel::upOrder,
                        onToggleSelect = ::onToggleSelect,
                        onToggleEnable = ::onToggleEnable,
                        onEdit = ::onEdit,
                        ruleMenuActions = ::ruleMenuActions
                    )
                }
            }
        }
        container.addView(cv, index)
    }

    private fun initData() {
        lifecycleScope.launch {
            appDb.txtTocRuleDao.observeAll().catch {
                AppLog.put("TXT目录规则界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect { tocRules ->
                rulesState.replaceByIndex(tocRules, ::sameTxtTocRuleContent)
                // Prune stale selections
                val currentIds = tocRules.mapTo(mutableSetOf()) { it.id }
                selectedIds.value = selectedIds.value.filter { it in currentIds }.toSet()
                upCountView()
            }
        }
    }

    private fun onToggleSelect(rule: TxtTocRule) {
        val current = selectedIds.value.toMutableSet()
        if (current.contains(rule.id)) {
            current.remove(rule.id)
        } else {
            current.add(rule.id)
        }
        selectedIds.value = current
        upCountView()
    }

    private fun onToggleEnable(rule: TxtTocRule, enabled: Boolean) {
        val updated = rule.copy(enable = enabled)
        rulesState.replaceFirst({ it.id == rule.id }) { updated }
        viewModel.update(updated)
    }

    private fun onEdit(rule: TxtTocRule) {
        showDialogFragment(TxtTocRuleEditComposeDialog.create(rule.id))
    }

    private fun ruleMenuActions(rule: TxtTocRule): List<AppManagementMenuAction> {
        return listOf(
            AppManagementMenuAction(getString(R.string.to_top)) {
                viewModel.toTop(rule)
            },
            AppManagementMenuAction(getString(R.string.to_bottom)) {
                viewModel.toBottom(rule)
            },
            AppManagementMenuAction(
                text = getString(R.string.delete),
                danger = true,
                onClick = { del(rule) }
            )
        )
    }

    private fun del(source: TxtTocRule) {
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.sure_del) + "\n" + source.name,
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            onPositive = { viewModel.del(source) }
        )
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.txt_toc_rule, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add -> showDialogFragment(TxtTocRuleEditComposeDialog.create())
            R.id.menu_import_local -> importDoc.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("txt", "json")
            }

            R.id.menu_import_onLine -> showImportDialog()
            R.id.menu_import_qr -> qrCodeResult.launch()
            R.id.menu_import_default -> viewModel.importDefault()
            R.id.menu_help -> showHelp("txtTocRuleHelp")
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun pageMenuActions(): List<AppManagementMenuAction> {
        return listOf(
            AppManagementMenuAction(getString(R.string.import_local)) {
                importDoc.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("txt", "json")
                }
            },
            AppManagementMenuAction(getString(R.string.import_on_line)) {
                showImportDialog()
            },
            AppManagementMenuAction(getString(R.string.import_by_qr_code)) {
                qrCodeResult.launch()
            },
            AppManagementMenuAction(getString(R.string.import_default_rule)) {
                viewModel.importDefault()
            },
            AppManagementMenuAction(getString(R.string.help)) {
                showHelp("txtTocRuleHelp")
            }
        )
    }

    override fun saveTxtTocRule(txtTocRule: TxtTocRule) {
        viewModel.save(txtTocRule)
    }

    override fun onClickSelectBarMainAction() {
        delSourceDialog()
    }

    override fun revertSelection() {
        val allIds = rulesState.map { it.id }
        val current = selectedIds.value
        selectedIds.value = allIds.filter { it !in current }.toSet()
        upCountView()
    }

    override fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            selectedIds.value = rulesState.map { it.id }.toSet()
        } else {
            selectedIds.value = emptySet()
        }
        upCountView()
    }

    private fun delSourceDialog() {
        val selected = rulesState.filter { it.id in selectedIds.value }
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.sure_del),
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            onPositive = { viewModel.del(*selected.toTypedArray()) }
        )
    }

    private fun showImportDialog() {
        val aCache = ACache.get(cacheDir = false)
        val defaultUrl = "https://gitee.com/fisher52/YueDuJson/raw/master/myTxtChapterRule.json"
        val cacheUrls: MutableList<String> = aCache
            .getAsString(importTocRuleKey)
            ?.splitNotBlank(",")
            ?.toMutableList()
            ?: mutableListOf()
        if (!cacheUrls.contains(defaultUrl)) {
            cacheUrls.add(0, defaultUrl)
        }
        showComposeTextInputDialog(
            title = getString(R.string.import_on_line),
            hint = "url",
            initialValue = cacheUrls.firstOrNull().orEmpty(),
            positiveText = getString(R.string.ok),
            negativeText = getString(R.string.cancel),
            onPositive = { text ->
                if (text.isNotBlank()) {
                    if (text.isAbsUrl() && !cacheUrls.contains(text)) {
                        cacheUrls.add(0, text)
                        aCache.put(importTocRuleKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(ImportTxtTocRuleDialog(text))
                }
            }
        )
    }

    private fun upCountView() {
        binding.selectActionBar
            .upCountView(selectedIds.value.size, rulesState.size)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_enable_selection -> enableSelected()
            R.id.menu_disable_selection -> disableSelected()
            R.id.menu_export_selection -> exportSelected()
        }
        return true
    }

    private fun getSelectedRules(): List<TxtTocRule> {
        val ids = selectedIds.value
        return rulesState.filter { it.id in ids }
    }

    private fun enableSelected() {
        val selected = getSelectedRules()
        updateSelectedEnabled(enabled = true)
        viewModel.enableSelection(*selected.toTypedArray())
    }

    private fun disableSelected() {
        val selected = getSelectedRules()
        updateSelectedEnabled(enabled = false)
        viewModel.disableSelection(*selected.toTypedArray())
    }

    private fun exportSelected() {
        val selected = getSelectedRules()
        exportResult.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                "exportTxtTocRule.json",
                GSON.toJson(selected).toByteArray(),
                "application/json"
            )
        }
    }

    private fun updateSelectedEnabled(enabled: Boolean) {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        rulesState.replaceMatching({ it.id in ids }) { it.copy(enable = enabled) }
    }

    private fun sameTxtTocRuleContent(old: TxtTocRule, new: TxtTocRule): Boolean {
        return old.id == new.id &&
            old.name == new.name &&
            old.rule == new.rule &&
            old.replacement == new.replacement &&
            old.example == new.example &&
            old.serialNumber == new.serialNumber &&
            old.enable == new.enable
    }

}
