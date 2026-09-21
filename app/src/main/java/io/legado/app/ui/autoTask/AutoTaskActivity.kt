package io.legado.app.ui.autoTask

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityAutoTaskBinding
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRule
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.utils.startActivity
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.CronSchedule
import kotlinx.coroutines.launch

class AutoTaskActivity : VMBaseActivity<ActivityAutoTaskBinding, AutoTaskViewModel>() {

    override val binding by viewBinding(ActivityAutoTaskBinding::inflate)
    override val viewModel by viewModels<AutoTaskViewModel>()

    private val rulesState = mutableStateListOf<AutoTaskRule>()
    private val selectedIds = mutableStateOf<Set<String>>(emptySet())
    private val searchQuery = mutableStateOf("")

    private val importDoc = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri -> showDialogFragment(ImportAutoTaskDialog(uri.toString())) }
    }

    private val exportResult = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            showComposeConfirmDialog(
                title = getString(R.string.export_success),
                message = uri.toString(),
                positiveText = getString(R.string.copy_text),
                negativeText = getString(R.string.ok),
                onPositive = { sendToClip(uri.toString()) },
                onNegative = {}
            )
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeRoot.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeRoot.setContent {
            AppManagementScaffold(
                title = getString(R.string.auto_task_manage),
                selectedCount = selectedIds.value.size,
                totalCount = filteredRules().size,
                searchQuery = searchQuery.value,
                searchHint = getString(R.string.search),
                onSearchChange = ::updateSearchQuery,
                topActions = listOf(
                    AppManagementAction(
                        text = getString(R.string.auto_task_add),
                        iconRes = R.drawable.ic_add,
                        onClick = { startActivity<AutoTaskEditActivity>() }
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
                        onClick = { viewModel.updateEnabled(selectedIds.value, true) }
                    ),
                    AppManagementAction(
                        text = getString(R.string.disable_selection),
                        onClick = { viewModel.updateEnabled(selectedIds.value, false) }
                    ),
                    AppManagementAction(
                        text = getString(R.string.auto_task_batch_cron),
                        onClick = ::showBatchCronDialog
                    ),
                    AppManagementAction(
                        text = getString(R.string.delete),
                        danger = true,
                        onClick = ::deleteSelected
                    ),
                    AppManagementAction(
                        text = getString(R.string.export_selection),
                        onClick = ::exportSelected
                    )
                ),
                onBack = { finish() },
                onSelectAll = { selectAll() },
                onInvertSelection = ::invertSelection
            ) {
                AutoTaskScreen(
                    rules = filteredRules(),
                    emptyText = getString(
                        if (rulesState.isEmpty()) {
                            R.string.auto_task_no_task
                        } else {
                            R.string.auto_task_no_search_result
                        }
                    ),
                    selectedIds = selectedIds.value,
                    isSelectMode = selectedIds.value.isNotEmpty(),
                    reorderEnabled = searchQuery.value.isBlank() && selectedIds.value.isEmpty(),
                    onReorder = viewModel::reorder,
                    onToggleSelection = ::toggleSelection,
                    onToggleEnabled = { rule, enabled ->
                        viewModel.toggleEnabled(rule.id, enabled)
                    },
                    onEdit = { rule ->
                        startActivity<AutoTaskEditActivity> { putExtra(EXTRA_TASK_ID, rule.id) }
                    },
                    onLogin = ::openLogin,
                    onRunNow = { rule -> viewModel.runNow(rule.id) },
                    onShowLog = { rule -> showTaskLog(rule) },
                    onDelete = ::confirmDelete
                )
            }
        }
        observeRules()
        supportFragmentManager.setFragmentResultListener(
            ImportAutoTaskDialog.RESULT_KEY,
            this
        ) { _, _ -> viewModel.refresh() }
        viewModel.refresh()
    }

    private fun observeRules() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.rulesFlow.collect { rules ->
                    rulesState.clear()
                    rulesState.addAll(rules)
                    val ids = rules.mapTo(HashSet(), AutoTaskRule::id)
                    selectedIds.value = selectedIds.value.filterTo(linkedSetOf()) { it in ids }
                }
            }
        }
    }

    private fun filteredRules(): List<AutoTaskRule> {
        val query = searchQuery.value.trim()
        if (query.isBlank()) return rulesState.toList()
        return rulesState.filter { rule ->
            rule.name.contains(query, ignoreCase = true) ||
                rule.cron.orEmpty().contains(query, ignoreCase = true) ||
                rule.lastError.orEmpty().contains(query, ignoreCase = true) ||
                rule.comment.orEmpty().contains(query, ignoreCase = true)
        }
    }

    private fun updateSearchQuery(query: String) {
        searchQuery.value = query
        selectedIds.value = emptySet()
    }

    private fun toggleSelection(rule: AutoTaskRule) {
        selectedIds.value = selectedIds.value.toMutableSet().apply {
            if (!add(rule.id)) remove(rule.id)
        }
    }

    private fun selectAll() {
        selectedIds.value = filteredRules().mapTo(linkedSetOf(), AutoTaskRule::id)
    }

    private fun invertSelection() {
        val current = selectedIds.value
        selectedIds.value = filteredRules()
            .mapNotNullTo(linkedSetOf()) { rule -> rule.id.takeUnless { it in current } }
    }

    private fun deleteSelected() {
        val ids = selectedIds.value.toList()
        if (ids.isEmpty()) return
        showComposeConfirmDialog(
            title = getString(R.string.delete),
            message = getString(R.string.sure_del),
            positiveText = getString(R.string.ok),
            negativeText = getString(R.string.cancel),
            dangerPositive = true,
            onPositive = {
                viewModel.delete(ids)
                selectedIds.value = emptySet()
            }
        )
    }

    private fun confirmDelete(rule: AutoTaskRule) {
        showComposeConfirmDialog(
            title = getString(R.string.auto_task_delete),
            message = rule.name.ifBlank { rule.id },
            positiveText = getString(R.string.delete),
            negativeText = getString(R.string.cancel),
            dangerPositive = true,
            onPositive = { viewModel.delete(listOf(rule.id)) }
        )
    }

    private fun showBatchCronDialog() {
        showComposeTextInputDialog(
            title = getString(R.string.auto_task_batch_cron),
            hint = getString(R.string.auto_task_cron),
            initialValue = "",
            positiveText = getString(R.string.ok),
            negativeText = getString(R.string.cancel),
            validateInput = { CronSchedule.parse(it.trim()) != null },
            onPositive = { cron ->
                if (CronSchedule.parse(cron.trim()) == null) {
                    toastOnUi(R.string.auto_task_cron_invalid)
                } else {
                    viewModel.updateCron(selectedIds.value, cron.trim())
                    selectedIds.value = emptySet()
                }
            }
        )
    }

    private fun showTaskLog(rule: AutoTaskRule) {
        val content = rule.lastLog
            ?.takeIf { it.isNotBlank() }
            ?: rule.lastError
            ?: getString(R.string.auto_task_not_run)
        showDialogFragment(TextDialog(rule.name.ifBlank { rule.id }, content))
    }

    private fun openLogin(rule: AutoTaskRule) {
        if (rule.loginUrl.isNullOrBlank() && rule.loginUi.isNullOrBlank()) {
            toastOnUi(R.string.source_no_login)
            return
        }
        startActivity<SourceLoginActivity> {
            val sourceKey = AutoTask.sourceKey(rule.id)
            putExtra("type", AutoTask.SOURCE_TYPE)
            putExtra("key", sourceKey)
            putExtra("taskId", rule.id)
            putExtra(AutoTask.EXTRA_TASK_ID, rule.id)
            putExtra(AutoTask.EXTRA_SOURCE_KEY, sourceKey)
        }
    }

    private fun pageMenuActions(): List<AppManagementMenuAction> = listOf(
        AppManagementMenuAction(getString(R.string.import_local)) {
            importDoc.launch {
                mode = HandleFileContract.FILE
                title = getString(R.string.import_auto_task)
                allowExtensions = arrayOf("json", "txt")
            }
        },
        AppManagementMenuAction(getString(R.string.import_on_line)) {
            showOnlineImportDialog()
        },
        AppManagementMenuAction(getString(R.string.export_all)) {
            exportRules(rulesState.toList(), "autoTask.json")
        },
        AppManagementMenuAction(getString(R.string.log)) {
            val content = rulesState.joinToString("\n\n") { rule ->
                val log = rule.lastLog ?: rule.lastError ?: getString(R.string.auto_task_not_run)
                "${rule.name.ifBlank { rule.id }}\n$log"
            }.ifBlank { getString(R.string.auto_task_no_task) }
            showDialogFragment(TextDialog(getString(R.string.log), content))
        },
        AppManagementMenuAction(getString(R.string.help)) {
            showHelp("autoTaskHelp")
        }
    )

    private fun showOnlineImportDialog() {
        showComposeTextInputDialog(
            title = getString(R.string.import_on_line),
            hint = "https://...",
            positiveText = getString(R.string.ok),
            negativeText = getString(R.string.cancel),
            validateInput = { it.trim().isAbsUrl() },
            onPositive = { value ->
                val url = value.trim()
                if (url.isAbsUrl()) {
                    showDialogFragment(ImportAutoTaskDialog(url))
                } else {
                    toastOnUi(R.string.wrong_format)
                }
            }
        )
    }

    private fun exportSelected() {
        val selected = rulesState.filter { it.id in selectedIds.value }
        exportRules(selected, "autoTaskSelection.json")
    }

    private fun exportRules(rules: List<AutoTaskRule>, fileName: String) {
        if (rules.isEmpty()) {
            toastOnUi(R.string.auto_task_import_select_none)
            return
        }
        exportResult.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                fileName,
                io.legado.app.model.AutoTaskImport.exportJson(rules)
                    .toByteArray(Charsets.UTF_8),
                "application/json"
            )
        }
    }

    companion object {
        private const val EXTRA_TASK_ID = "id"
    }
}
