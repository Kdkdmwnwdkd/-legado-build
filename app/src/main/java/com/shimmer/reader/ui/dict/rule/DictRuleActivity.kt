package com.shimmer.reader.ui.dict.rule

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
import com.shimmer.reader.R
import com.shimmer.reader.base.VMBaseActivity
import com.shimmer.reader.constant.AppLog
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.DictRule
import com.shimmer.reader.databinding.ActivityDictRuleBinding
import com.shimmer.reader.help.DirectLinkUpload
import com.shimmer.reader.ui.association.ImportDictRuleDialog
import com.shimmer.reader.ui.association.showShibbolethDialog
import com.shimmer.reader.ui.file.HandleFileContract
import com.shimmer.reader.ui.qrcode.QrCodeResult
import com.shimmer.reader.ui.widget.SelectActionBar
import com.shimmer.reader.ui.widget.compose.AppManagementAction
import com.shimmer.reader.ui.widget.compose.AppManagementMenuAction
import com.shimmer.reader.ui.widget.compose.AppManagementScaffold
import com.shimmer.reader.ui.widget.compose.replaceByIndex
import com.shimmer.reader.ui.widget.compose.replaceFirst
import com.shimmer.reader.ui.widget.compose.replaceMatching
import com.shimmer.reader.ui.widget.compose.showComposeActionListDialog
import com.shimmer.reader.ui.widget.compose.showComposeConfirmDialog
import com.shimmer.reader.ui.widget.compose.showComposeTextInputDialog
import com.shimmer.reader.utils.ACache
import com.shimmer.reader.utils.GSON
import com.shimmer.reader.utils.ShibbolethCodec
import com.shimmer.reader.utils.isAbsUrl
import com.shimmer.reader.utils.launch
import com.shimmer.reader.utils.sendToClip
import com.shimmer.reader.utils.showDialogFragment
import com.shimmer.reader.utils.showHelp
import com.shimmer.reader.utils.splitNotBlank
import com.shimmer.reader.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class DictRuleActivity : VMBaseActivity<ActivityDictRuleBinding, DictRuleViewModel>(),
    SelectActionBar.CallBack {

    override val viewModel by viewModels<DictRuleViewModel>()
    override val binding by viewBinding(ActivityDictRuleBinding::inflate)
    private val importRecordKey = "dictRuleUrls"
    private val rulesState = mutableStateListOf<DictRule>()
    private val selectedNames = mutableStateOf<Set<String>>(emptySet())
    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        showDialogFragment(ImportDictRuleDialog(it))
    }
    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportDictRuleDialog(uri.toString()))
        }
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            val url = uri.toString()
            showComposeConfirmDialog(
                title = getString(R.string.export_success),
                message = if (url.isAbsUrl()) {
                    DirectLinkUpload.getSummary()
                } else {
                    null
                },
                positiveText = getString(R.string.ok),
                negativeText = getString(R.string.cancel),
                neutralText = getString(R.string.shibboleth)
                    .takeIf { ShibbolethCodec.canEncodeUrl(url) },
                onPositive = { sendToClip(url) },
                onNeutral = { showShibbolethDialog(url, ShibbolethCodec.DICT_RULE) }
            )
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeContent()
        observeDictRuleData()
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
                    title = getString(R.string.dict_rule),
                    selectedCount = selectedNames.value.size,
                    totalCount = rulesState.size,
                    topActions = listOf(
                        AppManagementAction(
                            text = getString(R.string.create),
                            iconRes = R.drawable.ic_add,
                            onClick = { showDialogFragment<DictRuleEditDialog>() }
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
                            onClick = ::onClickSelectBarMainAction
                        )
                    ),
                    onBack = { finish() },
                    onSelectAll = { selectAll(true) },
                    onInvertSelection = { revertSelection() }
                ) {
                    DictRuleScreen(
                        rules = rulesState,
                        selectedNames = selectedNames.value,
                        isSelectMode = selectedNames.value.isNotEmpty(),
                        onReorder = viewModel::upSortNumber,
                        onToggleSelection = ::toggleSelection,
                        onToggleEnabled = ::toggleEnabled,
                        onEdit = ::editRule,
                        onDelete = ::deleteRule
                    )
                }
            }
        }
        container.addView(cv, index)
    }

    private fun initSelectActionView() {
        binding.selectActionBar.setMainActionText(R.string.delete)
        binding.selectActionBar.inflateMenu(R.menu.dict_rule_sel)
        binding.selectActionBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_enable_selection -> enableSelected()
                R.id.menu_disable_selection -> disableSelected()
                R.id.menu_export_selection -> exportSelected()
            }
            true
        }
        binding.selectActionBar.setCallBack(this)
    }

    private fun observeDictRuleData() {
        lifecycleScope.launch {
            appDb.dictRuleDao.flowAll().catch {
                AppLog.put("字典规则获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect { rules ->
                rulesState.replaceByIndex(rules, ::sameDictRuleContent)
                // Clean up selected names for deleted rules
                val currentNames = rules.map { it.name }.toSet()
                selectedNames.value = selectedNames.value.filter { it in currentNames }.toSet()
                upCountView()
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.dict_rule, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add -> showDialogFragment<DictRuleEditDialog>()
            R.id.menu_import_local -> importDoc.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("txt", "json")
            }
            R.id.menu_import_onLine -> showImportDialog()
            R.id.menu_import_qr -> qrCodeResult.launch()
            R.id.menu_import_default -> viewModel.importDefault()
            R.id.menu_help -> showHelp("dictRuleHelp")
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
                showHelp("dictRuleHelp")
            }
        )
    }

    override fun onClickSelectBarMainAction() {
        val selected = rulesState.filter { it.name in selectedNames.value }
        viewModel.delete(*selected.toTypedArray())
    }

    private fun getSelectedRules(): List<DictRule> {
        val names = selectedNames.value
        return rulesState.filter { it.name in names }
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
                "exportDictRule.json",
                GSON.toJson(selected).toByteArray(),
                "application/json"
            )
        }
    }

    override fun selectAll(selectAll: Boolean) {
        selectedNames.value = if (selectAll) {
            rulesState.map { it.name }.toSet()
        } else {
            emptySet()
        }
        upCountView()
    }

    override fun revertSelection() {
        val allNames = rulesState.map { it.name }.toSet()
        selectedNames.value = allNames - selectedNames.value
        upCountView()
    }

    private fun toggleSelection(rule: DictRule) {
        selectedNames.value = selectedNames.value.toMutableSet().apply {
            if (contains(rule.name)) remove(rule.name) else add(rule.name)
        }
        upCountView()
    }

    private fun toggleEnabled(rule: DictRule, enabled: Boolean) {
        val updated = rule.copy(enabled = enabled)
        rulesState.replaceFirst({ it.name == rule.name }) { updated }
        viewModel.update(updated)
    }

    private fun updateSelectedEnabled(enabled: Boolean) {
        val names = selectedNames.value
        if (names.isEmpty()) return
        rulesState.replaceMatching({ it.name in names }) { it.copy(enabled = enabled) }
    }

    private fun editRule(rule: DictRule) {
        showDialogFragment(DictRuleEditDialog(rule.name))
    }

    private fun deleteRule(rule: DictRule) {
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.sure_del) + "\n" + rule.name,
            positiveText = getString(R.string.ok),
            negativeText = getString(R.string.cancel),
            dangerPositive = true,
            onPositive = { viewModel.delete(rule) }
        )
    }

    private fun upCountView() {
        binding.selectActionBar.upCountView(
            selectedNames.value.size,
            rulesState.size
        )
    }

    private fun showImportDialog() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls: MutableList<String> = aCache
            .getAsString(importRecordKey)
            ?.splitNotBlank(",")
            ?.toMutableList() ?: mutableListOf()
        showComposeTextInputDialog(
            title = getString(R.string.import_on_line),
            hint = "url",
            initialValue = "",
            positiveText = getString(R.string.ok),
            negativeText = getString(R.string.cancel),
            onPositive = { text ->
                if (text.isNotBlank()) {
                    if (text.isAbsUrl() && !cacheUrls.contains(text)) {
                        cacheUrls.add(0, text)
                        aCache.put(importRecordKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(ImportDictRuleDialog(text))
                }
            }
        )
    }

    private fun sameDictRuleContent(old: DictRule, new: DictRule): Boolean {
        return old.name == new.name &&
            old.urlRule == new.urlRule &&
            old.showRule == new.showRule &&
            old.enabled == new.enabled &&
            old.sortNumber == new.sortNumber
    }
}
