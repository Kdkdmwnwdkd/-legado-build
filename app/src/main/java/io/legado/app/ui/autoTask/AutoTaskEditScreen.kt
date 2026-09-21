package io.legado.app.ui.autoTask

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.annotation.StringRes
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.AppRuleFieldSpacer
import io.legado.app.ui.widget.compose.AppRuleSwitchRow
import io.legado.app.ui.widget.compose.AppRuleTextField
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.toMiuixPalette
import androidx.compose.ui.res.stringResource

internal enum class AutoTaskEditorField(
    @param:StringRes val labelRes: Int,
    val languageName: String = "source.js"
) {
    COMMENT(R.string.auto_task_comment),
    SCRIPT(R.string.auto_task_script),
    HEADER(R.string.auto_task_header),
    JS_LIB(R.string.auto_task_jslib),
    LOGIN_UI(R.string.login_ui),
    LOGIN_CHECK_JS(R.string.login_check_js)
}

internal data class AutoTaskEditorState(
    val name: TextFieldValue = TextFieldValue(),
    val cron: TextFieldValue = TextFieldValue(),
    val comment: TextFieldValue = TextFieldValue(),
    val script: TextFieldValue = TextFieldValue(),
    val header: TextFieldValue = TextFieldValue(),
    val jsLib: TextFieldValue = TextFieldValue(),
    val concurrentRate: TextFieldValue = TextFieldValue(),
    val loginUrl: TextFieldValue = TextFieldValue(),
    val loginUi: TextFieldValue = TextFieldValue(),
    val loginCheckJs: TextFieldValue = TextFieldValue(),
    val enabled: Boolean = true,
    val cookieJar: Boolean = true
)

@Composable
internal fun AutoTaskEditScreen(
    state: AutoTaskEditorState,
    onStateChange: (AutoTaskEditorState) -> Unit,
    style: AppDialogStyle,
    onOpenEditor: ((AutoTaskEditorField) -> Unit)? = null,
    onLogin: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        AppRuleSwitchRow(
            text = stringResource(R.string.is_enable),
            checked = state.enabled,
            onCheckedChange = { onStateChange(state.copy(enabled = it)) },
            style = style
        )
        AppRuleFieldSpacer()
        AppRuleSwitchRow(
            text = stringResource(R.string.auto_task_cookie_jar),
            checked = state.cookieJar,
            onCheckedChange = { onStateChange(state.copy(cookieJar = it)) },
            style = style
        )
        AppRuleFieldSpacer()
        EditorField(
            value = state.name,
            label = stringResource(R.string.name),
            singleLine = true,
            style = style,
            onValueChange = { onStateChange(state.copy(name = it)) }
        )
        AppRuleFieldSpacer()
        EditorField(
            value = state.cron,
            label = stringResource(R.string.auto_task_cron),
            singleLine = true,
            style = style,
            onValueChange = { onStateChange(state.copy(cron = it)) }
        )
        AppRuleFieldSpacer()
        EditorField(
            value = state.comment,
            label = stringResource(R.string.auto_task_comment),
            style = style,
            onValueChange = { onStateChange(state.copy(comment = it)) },
            onOpenEditor = onOpenEditor?.let { open -> { open(AutoTaskEditorField.COMMENT) } }
        )
        AppRuleFieldSpacer()
        EditorField(
            value = state.script,
            label = stringResource(R.string.auto_task_script),
            minLines = 6,
            maxLines = 14,
            style = style,
            onValueChange = { onStateChange(state.copy(script = it)) },
            onOpenEditor = onOpenEditor?.let { open -> { open(AutoTaskEditorField.SCRIPT) } }
        )
        AppRuleFieldSpacer()
        EditorField(
            value = state.header,
            label = stringResource(R.string.auto_task_header),
            minLines = 2,
            style = style,
            onValueChange = { onStateChange(state.copy(header = it)) },
            onOpenEditor = onOpenEditor?.let { open -> { open(AutoTaskEditorField.HEADER) } }
        )
        AppRuleFieldSpacer()
        EditorField(
            value = state.jsLib,
            label = stringResource(R.string.auto_task_jslib),
            minLines = 2,
            style = style,
            onValueChange = { onStateChange(state.copy(jsLib = it)) },
            onOpenEditor = onOpenEditor?.let { open -> { open(AutoTaskEditorField.JS_LIB) } }
        )
        AppRuleFieldSpacer()
        EditorField(
            value = state.concurrentRate,
            label = stringResource(R.string.auto_task_concurrent_rate),
            singleLine = true,
            keyboardType = KeyboardType.Ascii,
            style = style,
            onValueChange = { onStateChange(state.copy(concurrentRate = it)) }
        )
        AppRuleFieldSpacer()
        EditorField(
            value = state.loginUrl,
            label = stringResource(R.string.login_url),
            singleLine = true,
            style = style,
            onValueChange = { onStateChange(state.copy(loginUrl = it)) }
        )
        AppRuleFieldSpacer()
        EditorField(
            value = state.loginUi,
            label = stringResource(R.string.login_ui),
            minLines = 2,
            style = style,
            onValueChange = { onStateChange(state.copy(loginUi = it)) },
            onOpenEditor = onOpenEditor?.let { open -> { open(AutoTaskEditorField.LOGIN_UI) } }
        )
        AppRuleFieldSpacer()
        EditorField(
            value = state.loginCheckJs,
            label = stringResource(R.string.login_check_js),
            minLines = 2,
            style = style,
            onValueChange = { onStateChange(state.copy(loginCheckJs = it)) },
            onOpenEditor = onOpenEditor?.let { open -> { open(AutoTaskEditorField.LOGIN_CHECK_JS) } }
        )
        onLogin?.let { login ->
            AppRuleFieldSpacer()
            LegadoMiuixActionButton(
                text = stringResource(R.string.login),
                palette = style.toMiuixPalette(),
                onClick = login,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                primary = true,
                cornerRadius = style.actionRadius
            )
        }
    }
}

@Composable
private fun EditorField(
    value: TextFieldValue,
    label: String,
    style: AppDialogStyle,
    onValueChange: (TextFieldValue) -> Unit,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    keyboardType: KeyboardType = KeyboardType.Text,
    onOpenEditor: (() -> Unit)? = null
) {
    AppRuleTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardType = keyboardType,
        style = style,
        onOpenEditor = onOpenEditor
    )
}
