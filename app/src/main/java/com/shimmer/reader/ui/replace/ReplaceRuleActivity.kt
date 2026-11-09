package com.shimmer.reader.ui.replace

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.SubMenu
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
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
import com.shimmer.reader.data.entities.ReplaceRule
import com.shimmer.reader.databinding.ActivityReplaceRuleBinding
import com.shimmer.reader.help.DirectLinkUpload
import com.shimmer.reader.help.book.ContentProcessor
import com.shimmer.reader.help.coroutine.Coroutine
import com.shimmer.reader.ui.association.ImportReplaceRuleDialog
import com.shimmer.reader.ui.association.showShibbolethDialog
import com.shimmer.reader.ui.file.HandleFileContract
import com.shimmer.reader.ui.qrcode.QrCodeResult
import com.shimmer.reader.ui.replace.edit.ReplaceEditActivity
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
import com.shimmer.reader.utils.transaction
import com.shimmer.reader.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 替换规则管理
 */
class ReplaceRuleActivity : VMBaseActivity<ActivityReplaceRuleBinding, ReplaceRuleViewModel>(),
    SelectActionBar.CallBack {
    override val binding by viewBinding(ActivityReplaceRuleBinding::inflate)
    override val viewModel by viewModels<ReplaceRuleViewModel>()
    private val importRecordKey = "replaceRuleRecordKey"
    private val rulesState = mutableStateListOf<ReplaceRule>()
    private val selectedIds = mutableStateOf<Set<Long>>(emptySet())
    private val searchQueryState = mutableStateOf("")
    private var groups = arrayListOf<String>()
    private var groupMenu: SubMenu? = null
    private var replaceRuleFlowJob: Job? = null
    private var dataInit = false
    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        showDialogFragment(ImportReplaceRuleDialog(it))
    }
    private val editActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                setResult(RESULT_OK)
            }
        }
    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportReplaceRuleDialog(uri.toString()))
        }
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            val url = uri.toString()
            showComposeConfirmDialog(
                title = getString(R.string.export_success),
                message = buildString {
                    if (url.isAbsUrl()) {
                        append(DirectLinkUpload.getSummary())
                        append("\n")
                    }
                    append(url)
                },
                positiveText = getString(R.string.copy_text),
                negativeText = getString(R.string.cancel),
                neutralText = getString(R.string.shibboleth)
                    .takeIf { ShibbolethCodec.canEncodeUrl(url) },
                onPositive = { sendToClip(url) },
                onNeutral = { showShibbolethDialog(url, ShibbolethCodec.REPLACE_RULE) }
            )
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeContent()
        observeReplaceRuleData()
        observeGroupData()
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
                    title = getString(R.string.replace_purify),
                    selectedCount = selectedIds.value.size,
                    totalCount = rulesState.size,
                    searchQuery = searchQueryState.value,
                    searchHint = getString(R.string.replace_purify_search),
                    onSearchChange = ::updateSearchQuery,
                    topActions = listOf(
                        AppManagementAction(
                            text = getString(R.string.menu_action_group),
                            iconRes = R.drawable.ic_groups,
                            onClick = ::showFilterMenu
                        ),
                        AppManagementAction(
                            text = getString(R.string.add_replace_rule),
                            iconRes = R.drawable.ic_add,
                            onClick = {
                                editActivity.launch(
                                    ReplaceEditActivity.startIntent(this@ReplaceRuleActivity)
                                )
                            }
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
                            text = getString(R.string.selection_to_top),
                            onClick = { viewModel.topSelect(getSelectedRules()) }
                        ),
                        AppManagementAction(
                            text = getString(R.string.selection_to_bottom),
                            onClick = { viewModel.bottomSelect(getSelectedRules()) }
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
                    ReplaceRuleScreen(
                        rules = rulesState,
                        selected = selectedIds.value,
                        isSelectMode = selectedIds.value.isNotEmpty(),
                        reorderEnabled = searchQueryState.value.isBlank(),
                        onReorder = { reordered ->
                            setResult(RESULT_OK)
                            viewModel.upOrder(reordered)
                        },
                        onSelectToggle = ::onSelectToggle,
                        onToggleEnabled = ::onToggleEnabled,
                        onEdit = ::edit,
                        ruleMenuActions = ::ruleMenuActions
                    )
                }
            }
        }
        container.addView(cv, index)
    }

    override fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            selectedIds.value = rulesState.map { it.id }.toSet()
        } else {
            revertSelection()
        }
        upCountView()
    }

    override fun revertSelection() {
        val currentRules = rulesState
        val currentSelected = selectedIds.value
        selectedIds.value = currentRules
            .filter { it.id !in currentSelected }
            .map { it.id }
            .toSet()
        upCountView()
    }

    override fun onClickSelectBarMainAction() {
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.sure_del),
            positiveText = getString(R.string.ok),
            negativeText = getString(R.string.cancel),
            dangerPositive = true,
            onPositive = { viewModel.delSelection(getSelectedRules()) }
        )
    }

    private fun initSelectActionView() {
        binding.selectActionBar.setMainActionText(R.string.delete)
        binding.selectActionBar.inflateMenu(R.menu.replace_rule_sel)
        binding.selectActionBar.setOnMenuItemClickListener { item ->
            onMenuItemClick(item)
        }
        binding.selectActionBar.setCallBack(this)
    }

    private fun observeReplaceRuleData(searchKey: String? = null) {
        dataInit = false
        replaceRuleFlowJob?.cancel()
        replaceRuleFlowJob = lifecycleScope.launch {
            when {
                searchKey.isNullOrEmpty() -> {
                    appDb.replaceRuleDao.flowAll()
                }

                searchKey == getString(R.string.enabled) -> {
                    appDb.replaceRuleDao.flowEnabled()
                }

                searchKey == getString(R.string.disabled) -> {
                    appDb.replaceRuleDao.flowDisabled()
                }

                searchKey == getString(R.string.no_group) -> {
                    appDb.replaceRuleDao.flowNoGroup()
                }

                searchKey.startsWith("group:") -> {
                    val key = searchKey.substringAfter("group:")
                    appDb.replaceRuleDao.flowGroupSearch("%$key%")
                }

                else -> {
                    appDb.replaceRuleDao.flowSearch("%$searchKey%")
                }
            }.catch {
                AppLog.put("替换规则管理界面更新数据出错", it)
            }.flowOn(IO).conflate().collect {
                if (dataInit) {
                    setResult(RESULT_OK)
                }
                rulesState.replaceByIndex(it, ::sameReplaceRuleContent)
                // Remove stale selected IDs
                val currentIds = it.map { rule -> rule.id }.toSet()
                selectedIds.value = selectedIds.value.filter { id -> id in currentIds }.toSet()
                dataInit = true
                upCountView()
                delay(100)
            }
        }
    }

    private fun observeGroupData() {
        lifecycleScope.launch {
            appDb.replaceRuleDao.flowGroups().collect {
                groups.clear()
                groups.addAll(it)
                upGroupMenu()
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.replace_rule, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        groupMenu = menu.findItem(R.id.menu_group)?.subMenu
        upGroupMenu()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add_replace_rule ->
                editActivity.launch(ReplaceEditActivity.startIntent(this))

            R.id.menu_group_manage -> showDialogFragment<GroupManageDialog>()
            R.id.menu_enabled_group -> {
                updateSearchQuery(getString(R.string.enabled))
            }

            R.id.menu_disabled_group -> {
                updateSearchQuery(getString(R.string.disabled))
            }
            R.id.menu_del_selection -> viewModel.delSelection(getSelectedRules())
            R.id.menu_import_onLine -> showImportDialog()
            R.id.menu_import_local -> importDoc.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("txt", "json")
            }

            R.id.menu_import_qr -> qrCodeResult.launch()
            R.id.menu_help -> showHelp("replaceRuleHelp")
            R.id.menu_group_null -> {
                updateSearchQuery(getString(R.string.no_group))
            }

            else -> if (item.groupId == R.id.replace_group) {
                updateSearchQuery("group:${item.title}")
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun showFilterMenu() {
        val fixedLabels = listOf(
            getString(R.string.group_manage),
            getString(R.string.enabled),
            getString(R.string.disabled),
            getString(R.string.no_group)
        )
        val labels = fixedLabels + groups
        showComposeActionListDialog(
            title = getString(R.string.menu_action_group),
            labels = labels
        ) { index ->
            when (index) {
                0 -> showDialogFragment<GroupManageDialog>()
                1 -> updateSearchQuery(getString(R.string.enabled))
                2 -> updateSearchQuery(getString(R.string.disabled))
                3 -> updateSearchQuery(getString(R.string.no_group))
                else -> updateSearchQuery("group:${labels[index]}")
            }
        }
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
            AppManagementMenuAction(getString(R.string.help)) {
                showHelp("replaceRuleHelp")
            }
        )
    }

    private fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_enable_selection -> enableSelected()
            R.id.menu_disable_selection -> disableSelected()
            R.id.menu_top_sel -> viewModel.topSelect(getSelectedRules())
            R.id.menu_bottom_sel -> viewModel.bottomSelect(getSelectedRules())
            R.id.menu_export_selection -> exportSelected()
        }
        return true
    }

    private fun enableSelected() {
        val selected = getSelectedRules()
        updateSelectedEnabled(enabled = true)
        viewModel.enableSelection(selected)
    }

    private fun disableSelected() {
        val selected = getSelectedRules()
        updateSelectedEnabled(enabled = false)
        viewModel.disableSelection(selected)
    }

    private fun exportSelected() {
        exportResult.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                "exportReplaceRule.json",
                GSON.toJson(getSelectedRules()).toByteArray(),
                "application/json"
            )
        }
    }

    private fun upGroupMenu() = groupMenu?.transaction { menu ->
        menu.removeGroup(R.id.replace_group)
        groups.forEach {
            menu.add(R.id.replace_group, Menu.NONE, Menu.NONE, it)
        }
    }

    @SuppressLint("InflateParams")
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
                    showDialogFragment(ImportReplaceRuleDialog(text))
                }
            }
        )
    }

    private fun onSelectToggle(rule: ReplaceRule) {
        val current = selectedIds.value.toMutableSet()
        if (rule.id in current) {
            current.remove(rule.id)
        } else {
            current.add(rule.id)
        }
        selectedIds.value = current
        upCountView()
    }

    private fun onToggleEnabled(rule: ReplaceRule, isEnabled: Boolean) {
        setResult(RESULT_OK)
        val updated = rule.copy(isEnabled = isEnabled)
        rulesState.replaceFirst({ it.id == rule.id }) { updated }
        viewModel.update(updated)
    }

    private fun edit(rule: ReplaceRule) {
        setResult(RESULT_OK)
        editActivity.launch(ReplaceEditActivity.startIntent(this, rule.id))
    }

    private fun ruleMenuActions(rule: ReplaceRule): List<AppManagementMenuAction> {
        return listOf(
            AppManagementMenuAction(getString(R.string.to_top)) {
                setResult(RESULT_OK)
                viewModel.toTop(rule)
            },
            AppManagementMenuAction(getString(R.string.to_bottom)) {
                setResult(RESULT_OK)
                viewModel.toBottom(rule)
            },
            AppManagementMenuAction(
                text = getString(R.string.delete),
                danger = true
            ) {
                showComposeConfirmDialog(
                    title = getString(R.string.draw),
                    message = getString(R.string.sure_del) + "\n" + rule.name,
                    positiveText = getString(R.string.ok),
                    negativeText = getString(R.string.cancel),
                    dangerPositive = true,
                    onPositive = {
                        setResult(RESULT_OK)
                        viewModel.delete(rule)
                    }
                )
            }
        )
    }

    private fun getSelectedRules(): List<ReplaceRule> {
        val ids = selectedIds.value
        return rulesState.filter { it.id in ids }
    }

    private fun updateSelectedEnabled(enabled: Boolean) {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        rulesState.replaceMatching({ it.id in ids }) { it.copy(isEnabled = enabled) }
    }

    private fun updateSearchQuery(query: String) {
        if (searchQueryState.value == query) {
            return
        }
        searchQueryState.value = query
        observeReplaceRuleData(query)
    }

    override fun onDestroy() {
        super.onDestroy()
        Coroutine.async { ContentProcessor.upReplaceRules() }
    }

    private fun upCountView() {
        binding.selectActionBar.upCountView(
            selectedIds.value.size,
            rulesState.size
        )
    }

    private fun sameReplaceRuleContent(old: ReplaceRule, new: ReplaceRule): Boolean {
        return old.id == new.id &&
            old.name == new.name &&
            old.group == new.group &&
            old.pattern == new.pattern &&
            old.replacement == new.replacement &&
            old.scope == new.scope &&
            old.scopeTitle == new.scopeTitle &&
            old.scopeContent == new.scopeContent &&
            old.excludeScope == new.excludeScope &&
            old.isEnabled == new.isEnabled &&
            old.isRegex == new.isRegex &&
            old.timeoutMillisecond == new.timeoutMillisecond &&
            old.order == new.order
    }
}
