package com.shimmer.reader.ui.book.read

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shimmer.reader.R
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.ReplaceRule
import com.shimmer.reader.help.ai.AiTextPurify
import com.shimmer.reader.ui.widget.compose.AppDialogFrame
import com.shimmer.reader.ui.widget.compose.AppDialogSize
import com.shimmer.reader.ui.widget.compose.ComposeDialogFragment
import com.shimmer.reader.ui.widget.compose.LegadoMiuixActionButton
import com.shimmer.reader.ui.widget.compose.rememberAppDialogStyle
import com.shimmer.reader.ui.widget.compose.toMiuixPalette
import com.shimmer.reader.utils.sendToClip
import com.shimmer.reader.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 选中文字的 AI 净化结果面板：
 * 打开即开始净化，完成后可复制结果，或存成只作用于当前书的替换规则。
 */
class AiPurifyDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    private val sourceText: String
        get() = arguments?.getString(ARG_TEXT).orEmpty()

    private val bookName: String
        get() = arguments?.getString(ARG_BOOK).orEmpty()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { PurifyContent() }
        }
    }

    @Composable
    private fun PurifyContent() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val style = rememberAppDialogStyle()
        val palette = style.toMiuixPalette()
        var loading by remember { mutableStateOf(true) }
        var result by remember { mutableStateOf("") }
        var error by remember { mutableStateOf("") }
        var ruleSaved by remember { mutableStateOf(false) }

        LaunchedEffect(sourceText) {
            loading = true
            error = ""
            runCatching { withContext(Dispatchers.IO) { AiTextPurify.purify(sourceText) } }
                .onSuccess { result = it }
                .onFailure { error = it.localizedMessage ?: "unknown error" }
            loading = false
        }

        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
        ) {
            AppDialogFrame(
                title = stringResource(R.string.ai_purify_title),
                content = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        when {
                            loading -> Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = stringResource(R.string.ai_purify_running),
                                    color = style.secondaryText,
                                    fontSize = 14.sp
                                )
                            }

                            error.isNotBlank() -> Text(
                                text = stringResource(R.string.ai_purify_failed, error),
                                color = style.secondaryText,
                                fontSize = 14.sp,
                                lineHeight = 21.sp
                            )

                            else -> {
                                Text(
                                    text = stringResource(R.string.ai_purify_result),
                                    color = style.secondaryText,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                SelectionContainer {
                                    Text(
                                        text = result,
                                        color = style.primaryText,
                                        fontSize = 15.sp,
                                        lineHeight = 24.sp,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 360.dp)
                                            .verticalScroll(rememberScrollState())
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    LegadoMiuixActionButton(
                        text = stringResource(R.string.dialog_cancel),
                        palette = palette,
                        onClick = { dismissAllowingStateLoss() },
                        cornerRadius = style.actionRadius
                    )
                    if (!loading && error.isBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.ai_purify_copy),
                            palette = palette,
                            onClick = {
                                context.sendToClip(result)
                                context.toastOnUi(R.string.toast_copied)
                            },
                            cornerRadius = style.actionRadius
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        LegadoMiuixActionButton(
                            text = if (ruleSaved) {
                                stringResource(R.string.ai_purify_rule_saved)
                            } else {
                                stringResource(R.string.ai_purify_save_rule)
                            },
                            palette = palette,
                            primary = true,
                            onClick = {
                                if (ruleSaved) {
                                    return@LegadoMiuixActionButton
                                }
                                val original = AiTextPurify.normalize(sourceText)
                                if (original.isBlank() || result.isBlank()) {
                                    return@LegadoMiuixActionButton
                                }
                                val rules = buildRules(original, result)
                                if (rules.isEmpty()) {
                                    context.toastOnUi(R.string.ai_purify_identical)
                                    return@LegadoMiuixActionButton
                                }
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        appDb.replaceRuleDao.insert(*rules.toTypedArray())
                                    }
                                    ruleSaved = true
                                    context.toastOnUi(R.string.ai_purify_rule_saved)
                                }
                            },
                            cornerRadius = style.actionRadius
                        )
                    }
                }
            )
        }
    }

    /**
     * 逐段对比生成规则：段落能一一对应时拆成多条精确替换，
     * 命中率比整段匹配高；对不齐时退回整段一条。
     */
    private fun buildRules(original: String, cleaned: String): List<ReplaceRule> {
        val originLines = original.split("\n").filter { it.isNotBlank() }
        val cleanLines = cleaned.split("\n").filter { it.isNotBlank() }
        val name = getString(R.string.ai_purify_rule_name, bookName.ifBlank { "AI" })
        val scope = bookName.takeIf { it.isNotBlank() }
        if (originLines.size > 1 && originLines.size == cleanLines.size) {
            return originLines.zip(cleanLines)
                .filter { (before, after) -> before.trim() != after.trim() }
                .map { (before, after) ->
                    ReplaceRule(
                        name = name,
                        pattern = before.trim(),
                        replacement = after.trim(),
                        scope = scope,
                        scopeContent = true,
                        scopeTitle = false,
                        isRegex = false,
                        isEnabled = true
                    )
                }
        }
        if (original.trim() == cleaned.trim()) return emptyList()
        return listOf(
            ReplaceRule(
                name = name,
                pattern = original,
                replacement = cleaned,
                scope = scope,
                scopeContent = true,
                scopeTitle = false,
                isRegex = false,
                isEnabled = true
            )
        )
    }

    companion object {
        private const val ARG_TEXT = "text"
        private const val ARG_BOOK = "book"

        fun newInstance(text: String, bookName: String): AiPurifyDialog {
            return AiPurifyDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TEXT, text)
                    putString(ARG_BOOK, bookName)
                }
            }
        }
    }
}
