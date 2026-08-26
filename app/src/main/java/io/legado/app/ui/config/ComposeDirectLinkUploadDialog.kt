package io.legado.app.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.help.DirectLinkUpload
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

class ComposeDirectLinkUploadDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val initialRule by produceState<DirectLinkUpload.Rule?>(null) {
                    value = withContext(IO) {
                        runCatching { DirectLinkUpload.getRule() }
                            .getOrElse { DirectLinkUpload.Rule("", "", "") }
                    }
                }
                initialRule?.let { rule ->
                    DirectLinkUploadContent(
                        initialRule = rule,
                        onDismiss = { dismissAllowingStateLoss() },
                        onImportDefault = ::importDefault,
                        onCopyRule = ::copyRule,
                        onPasteRule = ::pasteRule,
                        onTest = ::test,
                        onSave = ::saveRuleAndDismiss
                    )
                } ?: DirectLinkUploadLoadingContent(
                    onDismiss = { dismissAllowingStateLoss() }
                )
            }
        }
    }

    private fun importDefault() {
        lifecycleScope.launch {
            val rules = withContext(IO) { DirectLinkUpload.defaultRules }
            showComposeActionListDialog(
                title = getString(R.string.import_default_rule),
                labels = rules.map { it.summary.orEmpty() }
            ) { index ->
                rules.getOrNull(index)?.let { rule ->
                    saveRuleAndDismiss(rule)
                }
            }
        }
    }

    private fun copyRule() {
        lifecycleScope.launch {
            val result = withContext(IO) {
                DirectLinkUpload.encodeRule(DirectLinkUpload.getRule())
            }
            result.onSuccess { appCtx.sendToClip(it) }
                .onFailure {
                    toastOnUi("复制失败：${it.localizedMessage ?: "规则格式不正确"}")
                }
        }
    }

    private fun pasteRule() {
        lifecycleScope.launch {
            val clipText = requireContext().getClipText()
            if (clipText.isNullOrBlank()) {
                toastOnUi("剪贴板为空或格式不对")
                return@launch
            }
            val result = withContext(IO) { DirectLinkUpload.importRule(clipText) }
            result.onSuccess {
                dismissAllowingStateLoss()
            }.onFailure {
                toastOnUi("格式不对：${it.localizedMessage ?: "规则校验失败"}")
            }
        }
    }

    private fun saveRuleAndDismiss(rule: DirectLinkUpload.Rule) {
        lifecycleScope.launch {
            val result = withContext(IO) { DirectLinkUpload.putConfig(rule) }
            result.onSuccess {
                dismissAllowingStateLoss()
            }.onFailure {
                toastOnUi("保存失败：${it.localizedMessage ?: "规则校验失败"}")
            }
        }
    }

    private fun test() {
        lifecycleScope.launch {
            val result = withContext(IO) {
                val rule = DirectLinkUpload.getRule()
                runCatching { DirectLinkUpload.upLoad("test.json", "{}", "application/json", rule) }
            }
            result.onSuccess { msg ->
                showComposeConfirmDialog(
                    title = "result",
                    message = msg,
                    positiveText = getString(android.R.string.ok),
                    negativeText = getString(R.string.copy_text),
                    onPositive = {},
                    onNegative = { appCtx.sendToClip(msg) }
                )
            }.onFailure { e ->
                showComposeConfirmDialog(
                    title = "result",
                    message = e.localizedMessage ?: "ERROR",
                    positiveText = getString(android.R.string.ok),
                    onPositive = {}
                )
            }
        }
    }
}

@Composable
private fun DirectLinkUploadLoadingContent(onDismiss: () -> Unit) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    AppDialogFrame(
        title = stringResource(R.string.backup_restore),
        scrollContent = false,
        content = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = style.accent)
            }
        },
        actions = {
            LegadoMiuixActionButton(
                text = stringResource(R.string.cancel),
                palette = palette,
                onClick = onDismiss,
                cornerRadius = style.actionRadius
            )
        }
    )
}

@Composable
private fun DirectLinkUploadContent(
    initialRule: DirectLinkUpload.Rule,
    onDismiss: () -> Unit,
    onImportDefault: () -> Unit,
    onCopyRule: () -> Unit,
    onPasteRule: () -> Unit,
    onTest: () -> Unit,
    onSave: (DirectLinkUpload.Rule) -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    var uploadUrl by remember { mutableStateOf(initialRule.uploadUrl.orEmpty()) }
    var downloadUrlRule by remember {
        mutableStateOf(initialRule.downloadUrlRule.orEmpty())
    }
    var summary by remember { mutableStateOf(initialRule.summary.orEmpty()) }
    var compress by rememberSaveable { mutableStateOf(initialRule.compress) }

    AppDialogFrame(
        title = stringResource(R.string.backup_restore),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Menu actions row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LegadoMiuixActionButton(
                        text = stringResource(R.string.import_default_rule),
                        palette = palette,
                        onClick = onImportDefault,
                        cornerRadius = style.actionRadius
                    )
                    LegadoMiuixActionButton(
                        text = stringResource(R.string.copy_text),
                        palette = palette,
                        onClick = onCopyRule,
                        cornerRadius = style.actionRadius
                    )
                    LegadoMiuixActionButton(
                        text = stringResource(R.string.paste_rule),
                        palette = palette,
                        onClick = onPasteRule,
                        cornerRadius = style.actionRadius
                    )
                }
                // Upload URL
                DirectLinkTextField(
                    value = uploadUrl,
                    onValueChange = { uploadUrl = it },
                    label = "上传Url",
                    style = style
                )
                // Download URL rule
                DirectLinkTextField(
                    value = downloadUrlRule,
                    onValueChange = { downloadUrlRule = it },
                    label = "下载Url规则",
                    style = style
                )
                // Summary
                DirectLinkTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = "注释",
                    style = style
                )
                // Compress checkbox
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = compress,
                        onCheckedChange = { compress = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = style.accent,
                            uncheckedColor = style.secondaryText,
                            checkmarkColor = style.surface
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "压缩",
                        color = style.primaryText,
                        fontSize = 14.sp,
                        fontFamily = style.bodyFontFamily
                    )
                }
            }
        },
        actions = {
            LegadoMiuixActionButton(
                text = "测试",
                palette = palette,
                onClick = onTest,
                cornerRadius = style.actionRadius
            )
            Spacer(modifier = Modifier.width(16.dp))
            LegadoMiuixActionButton(
                text = stringResource(R.string.cancel),
                palette = palette,
                onClick = onDismiss,
                cornerRadius = style.actionRadius
            )
            Spacer(modifier = Modifier.width(8.dp))
            LegadoMiuixActionButton(
                text = stringResource(R.string.ok),
                palette = palette,
                onClick = {
                    if (uploadUrl.isBlank()) {
                        return@LegadoMiuixActionButton
                    }
                    if (downloadUrlRule.isBlank()) {
                        return@LegadoMiuixActionButton
                    }
                    onSave(
                        DirectLinkUpload.Rule(
                            uploadUrl,
                            downloadUrlRule,
                            summary,
                            compress,
                            initialRule.expiryDate
                        )
                    )
                },
                primary = true,
                cornerRadius = style.actionRadius
            )
        }
    )
}

@Composable
private fun DirectLinkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    style: AppDialogStyle
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(text = label, fontSize = 13.sp) },
        textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = 15.sp,
            fontFamily = style.bodyFontFamily,
            color = style.primaryText
        ),
        shape = RoundedCornerShape(style.actionRadius),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = style.accent,
            unfocusedBorderColor = style.stroke,
            focusedLabelColor = style.accent,
            unfocusedLabelColor = style.secondaryText,
            cursorColor = style.accent,
            focusedContainerColor = style.fieldSurface,
            unfocusedContainerColor = style.fieldSurface
        ),
        singleLine = false,
        minLines = 1,
        maxLines = 6
    )
}
