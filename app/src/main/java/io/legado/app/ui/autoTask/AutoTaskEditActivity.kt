package io.legado.app.ui.autoTask

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityAutoTaskEditBinding
import io.legado.app.help.CacheManager
import io.legado.app.model.AutoTaskRule
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRuleValidator
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.utils.CronSchedule
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.ui.login.SourceLoginActivity
import kotlinx.coroutines.launch

class AutoTaskEditActivity : VMBaseActivity<ActivityAutoTaskEditBinding, AutoTaskEditViewModel>() {

    override val binding by viewBinding(ActivityAutoTaskEditBinding::inflate)
    override val viewModel by viewModels<AutoTaskEditViewModel>()

    private var taskId: String? = null
    private var loaded = false
    private var finishAfterSave = false
    private var origin: AutoTaskRule? = null
    private var editorState by mutableStateOf(AutoTaskEditorState())
    private var editingField: AutoTaskEditorField? = null
    private var editingCacheKey: String? = null

    private val codeEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val field = editingField
        val sourceCacheKey = editingCacheKey
        editingField = null
        editingCacheKey = null

        val data = result.data
        val returnedCacheKey = data?.getStringExtra("cacheKey")
        val editedText = if (returnedCacheKey != null) {
            CacheManager.getFromMemory(returnedCacheKey) as? String
        } else {
            data?.getStringExtra("text")
        }
        if (result.resultCode == Activity.RESULT_OK && field != null && editedText != null) {
            val cursor = data?.getIntExtra("cursorPosition", editedText.length)
                ?.takeIf { it in 0..editedText.length }
                ?: editedText.length
            updateEditorField(field, TextFieldValue(editedText, TextRange(cursor)))
        }
        sourceCacheKey?.let(CacheManager::deleteMemory)
        if (returnedCacheKey != null && returnedCacheKey != sourceCacheKey) {
            returnedCacheKey.let(CacheManager::deleteMemory)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        taskId = intent.getStringExtra(EXTRA_TASK_ID)
        binding.composeRoot.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeRoot.setContent {
            val style = rememberAppDialogStyle()
            AppManagementScaffold(
                title = getString(
                    if (taskId.isNullOrBlank()) R.string.auto_task_add else R.string.auto_task_edit
                ),
                selectedCount = 0,
                totalCount = 0,
                topActions = listOfNotNull(
                    AppManagementAction(
                        text = getString(R.string.action_save),
                        iconRes = R.drawable.ic_save,
                        primary = true,
                        onClick = ::saveTask
                    ),
                    AppManagementAction(
                        text = getString(R.string.login),
                        iconRes = R.drawable.ic_bottom_person_e,
                        onClick = ::openLogin
                    ),
                    AppManagementAction(
                        text = getString(R.string.more_menu),
                        iconRes = R.drawable.ic_more_vert,
                        menuActions = { editorMenuActions() }
                    )
                ),
                onBack = ::finish
            ) {
                AutoTaskEditScreen(
                    state = editorState,
                    onStateChange = { editorState = it },
                    style = style,
                    onOpenEditor = ::openCodeEditor,
                    onLogin = ::openLogin
                )
            }
        }
        observeTask()
        viewModel.load(taskId)
    }

    private fun observeTask() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.task.collect { rule ->
                    if (rule != null && !loaded) {
                        loaded = true
                        origin = rule.copy()
                        editorState = rule.toEditorState()
                    }
                }
            }
        }
    }

    private fun saveTask() {
        val rule = buildRule() ?: return
        viewModel.save(rule) {
            origin = it.copy()
            loaded = true
            finishAfterSave = true
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun buildRule(): AutoTaskRule? {
        val name = editorState.name.text.trim()
        if (name.isBlank()) {
            toastOnUi(R.string.auto_task_name_required)
            return null
        }
        val cron = editorState.cron.text.trim().ifBlank { AutoTaskRule.DEFAULT_CRON }
        if (CronSchedule.parse(cron) == null) {
            toastOnUi(R.string.auto_task_cron_invalid)
            return null
        }
        val script = editorState.script.text.trim()
        if (script.isBlank()) {
            toastOnUi(R.string.auto_task_script_empty)
            return null
        }
        val rate = editorState.concurrentRate.text.trim().ifBlank { null }
        if (!AutoTaskRuleValidator.isValidConcurrentRate(rate.orEmpty())) {
            toastOnUi(R.string.auto_task_concurrent_rate)
            return null
        }
        val base = origin ?: viewModel.task.value ?: AutoTaskRule()
        return base.copy(
            name = name,
            cron = cron,
            comment = editorState.comment.text.trim().ifBlank { null },
            script = script,
            header = editorState.header.text.trim().ifBlank { null },
            jsLib = editorState.jsLib.text.trim().ifBlank { null },
            concurrentRate = rate,
            loginUrl = editorState.loginUrl.text.trim().ifBlank { null },
            loginUi = editorState.loginUi.text.trim().ifBlank { null },
            loginCheckJs = editorState.loginCheckJs.text.trim().ifBlank { null },
            enable = editorState.enabled,
            enabledCookieJar = editorState.cookieJar
        )
    }

    private fun editorMenuActions(): List<AppManagementMenuAction> {
        return listOf(
            AppManagementMenuAction(
                text = getString(R.string.auto_task_debug),
                enabled = editorState.script.text.isNotBlank(),
                onClick = ::debugTask
            ),
            AppManagementMenuAction(
                text = getString(R.string.login),
                onClick = ::openLogin
            ),
            AppManagementMenuAction(
                text = getString(R.string.copy_source),
                onClick = { sendToClip(GSON.toJson(buildDraft())) }
            ),
            AppManagementMenuAction(
                text = getString(R.string.paste_source),
                onClick = ::pasteSource
            ),
            AppManagementMenuAction(
                text = getString(R.string.help),
                onClick = { showHelp("autoTaskHelp") }
            )
        )
    }

    private fun debugTask() {
        val rule = buildRule() ?: return
        viewModel.save(rule) {
            origin = it.copy()
            editorState = it.toEditorState()
            startActivity(AutoTaskDebugActivity.startIntent(this, it.id))
        }
    }

    private fun buildDraft(): AutoTaskRule {
        return (origin ?: viewModel.task.value ?: AutoTaskRule()).copy(
            name = editorState.name.text,
            cron = editorState.cron.text,
            comment = editorState.comment.text,
            script = editorState.script.text,
            header = editorState.header.text,
            jsLib = editorState.jsLib.text,
            concurrentRate = editorState.concurrentRate.text,
            loginUrl = editorState.loginUrl.text,
            loginUi = editorState.loginUi.text,
            loginCheckJs = editorState.loginCheckJs.text,
            enable = editorState.enabled,
            enabledCookieJar = editorState.cookieJar
        )
    }

    private fun pasteSource() {
        val text = getClipText()?.trim().orEmpty()
        if (text.isBlank()) {
            toastOnUi(R.string.empty)
            return
        }
        runCatching { GSON.fromJsonObject<AutoTaskRule>(text).getOrThrow() }
            .onSuccess { imported ->
                editorState = imported.toEditorState()
            }
            .onFailure { toastOnUi(it.localizedMessage.orEmpty()) }
    }

    private fun openLogin() {
        val rule = buildRule() ?: return
        if (rule.loginUrl.isNullOrBlank() && rule.loginUi.isNullOrBlank()) {
            toastOnUi(R.string.source_no_login)
            return
        }
        viewModel.save(rule) {
            origin = it.copy()
            editorState = it.toEditorState()
            startActivity<SourceLoginActivity> {
                val sourceKey = AutoTask.sourceKey(it.id)
                putExtra("type", AutoTask.SOURCE_TYPE)
                putExtra("key", sourceKey)
                putExtra("taskId", it.id)
                putExtra(AutoTask.EXTRA_TASK_ID, it.id)
                putExtra(AutoTask.EXTRA_SOURCE_KEY, sourceKey)
            }
        }
    }

    private fun openCodeEditor(field: AutoTaskEditorField) {
        val value = editorValue(field)
        val cacheKey = "auto_task_editor_${System.nanoTime()}"
        CacheManager.putMemory(cacheKey, value.text)
        editingField = field
        editingCacheKey = cacheKey
        val intent = Intent(this, CodeEditActivity::class.java).apply {
            putExtra("cacheKey", cacheKey)
            putExtra("writable", true)
            putExtra("title", getString(field.labelRes))
            putExtra("languageName", field.languageName)
            putExtra("cursorPosition", value.selection.start)
        }
        codeEditorLauncher.launch(intent)
    }

    private fun editorValue(field: AutoTaskEditorField): TextFieldValue {
        return when (field) {
            AutoTaskEditorField.COMMENT -> editorState.comment
            AutoTaskEditorField.SCRIPT -> editorState.script
            AutoTaskEditorField.HEADER -> editorState.header
            AutoTaskEditorField.JS_LIB -> editorState.jsLib
            AutoTaskEditorField.LOGIN_UI -> editorState.loginUi
            AutoTaskEditorField.LOGIN_CHECK_JS -> editorState.loginCheckJs
        }
    }

    private fun updateEditorField(field: AutoTaskEditorField, value: TextFieldValue) {
        editorState = when (field) {
            AutoTaskEditorField.COMMENT -> editorState.copy(comment = value)
            AutoTaskEditorField.SCRIPT -> editorState.copy(script = value)
            AutoTaskEditorField.HEADER -> editorState.copy(header = value)
            AutoTaskEditorField.JS_LIB -> editorState.copy(jsLib = value)
            AutoTaskEditorField.LOGIN_UI -> editorState.copy(loginUi = value)
            AutoTaskEditorField.LOGIN_CHECK_JS -> editorState.copy(loginCheckJs = value)
        }
    }

    override fun finish() {
        if (finishAfterSave) {
            super.finish()
            return
        }
        val current = buildDraft()
        if (origin != null && current != origin) {
            showComposeConfirmDialog(
                title = getString(R.string.exit),
                message = getString(R.string.exit_no_save),
                positiveText = getString(R.string.yes),
                negativeText = getString(R.string.no),
                onPositive = { super.finish() }
            )
        } else {
            super.finish()
        }
    }

    private fun AutoTaskRule.toEditorState() = AutoTaskEditorState(
        name = androidx.compose.ui.text.input.TextFieldValue(name),
        cron = androidx.compose.ui.text.input.TextFieldValue(cron.orEmpty()),
        comment = androidx.compose.ui.text.input.TextFieldValue(comment.orEmpty()),
        script = androidx.compose.ui.text.input.TextFieldValue(script),
        header = androidx.compose.ui.text.input.TextFieldValue(header.orEmpty()),
        jsLib = androidx.compose.ui.text.input.TextFieldValue(jsLib.orEmpty()),
        concurrentRate = androidx.compose.ui.text.input.TextFieldValue(concurrentRate.orEmpty()),
        loginUrl = androidx.compose.ui.text.input.TextFieldValue(loginUrl.orEmpty()),
        loginUi = androidx.compose.ui.text.input.TextFieldValue(loginUi.orEmpty()),
        loginCheckJs = androidx.compose.ui.text.input.TextFieldValue(loginCheckJs.orEmpty()),
        enabled = enable,
        cookieJar = enabledCookieJar
    )

    companion object {
        private const val EXTRA_TASK_ID = "id"
    }
}
