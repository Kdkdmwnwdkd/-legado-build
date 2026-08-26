package io.legado.app.ui.about

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import io.legado.app.help.update.AppUpdateConfig
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogOptionGroup
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixChoiceRow
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.showDialogFragment

object UpdateAcceleratorDialog {

    private data class StrategyOption(
        val label: String,
        val value: String
    )

    fun show(fragment: Fragment, onChanged: () -> Unit) {
        val strategies = listOf(
            StrategyOption("Gitee 优先，失败后 GitHub", AppUpdateConfig.STRATEGY_GITEE_THEN_GITHUB),
            StrategyOption("只使用 Gitee", AppUpdateConfig.STRATEGY_GITEE_ONLY),
            StrategyOption("只使用 GitHub", AppUpdateConfig.STRATEGY_GITHUB_ONLY)
        )
        val initialStrategyIndex = strategies.indexOfFirst {
            it.value == AppUpdateConfig.strategy
        }.coerceAtLeast(0)
        val initialProxyIndex = AppUpdateConfig.githubProxyIndex
        val initialProxyTemplates = AppUpdateConfig.githubProxyTemplates.toList()

        fragment.showDialogFragment(
            UpdateAcceleratorComposeDialog.create(
                strategyLabels = strategies.map { it.label },
                initialStrategyIndex = initialStrategyIndex,
                initialProxyTemplates = initialProxyTemplates,
                initialProxyIndex = initialProxyIndex,
                onStrategyChanged = { index ->
                    AppUpdateConfig.strategy = strategies[index].value
                    onChanged()
                },
                onProxySelected = { index ->
                    AppUpdateConfig.githubProxyIndex = index
                    onChanged()
                },
                onProxyAdded = { value ->
                    val proxies = AppUpdateConfig.githubProxyTemplates.toMutableList()
                    proxies += value
                    AppUpdateConfig.githubProxyTemplates = proxies
                    AppUpdateConfig.githubProxyIndex = AppUpdateConfig.githubProxyTemplates.indexOf(value)
                    onChanged()
                },
                onProxyEdited = { index, value ->
                    val proxies = AppUpdateConfig.githubProxyTemplates.toMutableList()
                    proxies[index] = value
                    AppUpdateConfig.githubProxyTemplates = proxies
                    AppUpdateConfig.githubProxyIndex = AppUpdateConfig.githubProxyTemplates.indexOf(value)
                    onChanged()
                },
                onProxyDeleted = { index ->
                    val proxies = AppUpdateConfig.githubProxyTemplates.toMutableList()
                    if (index in proxies.indices) {
                        proxies.removeAt(index)
                        AppUpdateConfig.githubProxyTemplates = proxies
                        AppUpdateConfig.githubProxyIndex = -1
                        onChanged()
                    }
                },
                onDismissed = {
                    onChanged()
                }
            )
        )
    }
}

class UpdateAcceleratorComposeDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    private var onStrategyChanged: ((Int) -> Unit)? = null
    private var onProxySelected: ((Int) -> Unit)? = null
    private var onProxyAdded: ((String) -> Unit)? = null
    private var onProxyEdited: ((Int, String) -> Unit)? = null
    private var onProxyDeleted: ((Int) -> Unit)? = null
    private var onDismissed: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                val palette = style.toMiuixPalette()

                val strategies = rememberSaveable {
                    args.getStringArrayList(ARG_STRATEGY_LABELS)?.toList().orEmpty()
                }
                var selectedStrategyIndex by rememberSaveable {
                    mutableIntStateOf(args.getInt(ARG_STRATEGY_INDEX))
                }

                var proxyTemplates by rememberSaveable {
                    mutableStateOf(
                        args.getStringArrayList(ARG_PROXY_TEMPLATES)?.toList().orEmpty()
                    )
                }
                var selectedProxyIndex by rememberSaveable {
                    mutableIntStateOf(args.getInt(ARG_PROXY_INDEX))
                }

                AppDialogFrame(
                    title = "加速管理",
                    scrollContent = true,
                    content = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // -- Strategy section --
                            AppDialogOptionGroup(
                                title = "更新通道",
                                options = strategies,
                                selectedIndex = selectedStrategyIndex,
                                onSelected = { index ->
                                    selectedStrategyIndex = index
                                    onStrategyChanged?.invoke(index)
                                }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // -- Proxy section --
                            Text(
                                text = "GitHub 加速代理",
                                color = style.accent,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "代理模板支持 \${url} 占位符。未填写占位符时，会自动把原始 GitHub 地址拼到代理地址后面。",
                                color = style.secondaryText,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Proxy radio list
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                item(key = "proxy_none") {
                                    LegadoMiuixChoiceRow(
                                        text = "不使用代理",
                                        selected = selectedProxyIndex < 0,
                                        palette = palette,
                                        onClick = {
                                            selectedProxyIndex = -1
                                            onProxySelected?.invoke(-1)
                                        },
                                        minHeight = 40.dp
                                    )
                                }
                                itemsIndexed(
                                    proxyTemplates,
                                    key = { index, _ -> "proxy_$index" }
                                ) { index, template ->
                                    LegadoMiuixChoiceRow(
                                        text = template,
                                        selected = selectedProxyIndex == index,
                                        palette = palette,
                                        onClick = {
                                            selectedProxyIndex = index
                                            onProxySelected?.invoke(index)
                                        },
                                        minHeight = 40.dp
                                    )
                                }
                            }

                            // Action buttons row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                LegadoMiuixActionButton(
                                    text = "添加代理",
                                    palette = palette,
                                    onClick = {
                                        showProxyEditDialog(oldValue = null) { value ->
                                            proxyTemplates = proxyTemplates + value
                                            selectedProxyIndex = proxyTemplates.indexOf(value)
                                            onProxyAdded?.invoke(value)
                                        }
                                    },
                                    cornerRadius = style.actionRadius
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                LegadoMiuixActionButton(
                                    text = "编辑",
                                    palette = palette,
                                    onClick = {
                                        val index = selectedProxyIndex
                                        val oldValue = proxyTemplates.getOrNull(index)
                                            ?: return@LegadoMiuixActionButton
                                        showProxyEditDialog(oldValue = oldValue) { value ->
                                            proxyTemplates = proxyTemplates.toMutableList().apply {
                                                set(index, value)
                                            }
                                            selectedProxyIndex = proxyTemplates.indexOf(value)
                                            onProxyEdited?.invoke(index, value)
                                        }
                                    },
                                    cornerRadius = style.actionRadius
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                LegadoMiuixActionButton(
                                    text = "删除",
                                    palette = palette,
                                    onClick = {
                                        val index = selectedProxyIndex
                                        if (index !in proxyTemplates.indices) return@LegadoMiuixActionButton
                                        proxyTemplates = proxyTemplates.toMutableList().apply {
                                            removeAt(index)
                                        }
                                        selectedProxyIndex = -1
                                        onProxyDeleted?.invoke(index)
                                    },
                                    danger = true,
                                    cornerRadius = style.actionRadius
                                )
                            }
                        }
                    },
                    actions = {
                        LegadoMiuixActionButton(
                            text = "确定",
                            palette = palette,
                            onClick = {
                                dismissAllowingStateLoss()
                            },
                            primary = true,
                            cornerRadius = style.actionRadius
                        )
                    }
                )
            }
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onDismissed?.invoke()
    }

    private fun showProxyEditDialog(
        oldValue: String?,
        onSaved: (String) -> Unit
    ) {
        showComposeTextInputDialog(
            title = if (oldValue == null) "添加代理" else "编辑代理",
            hint = "https://proxy.example/\${url}",
            initialValue = oldValue.orEmpty(),
            onPositive = { value ->
                val trimmed = value.trim()
                if (trimmed.isNotBlank()) {
                    onSaved(trimmed)
                }
            }
        )
    }

    companion object {
        fun create(
            strategyLabels: List<String>,
            initialStrategyIndex: Int,
            initialProxyTemplates: List<String>,
            initialProxyIndex: Int,
            onStrategyChanged: (Int) -> Unit,
            onProxySelected: (Int) -> Unit,
            onProxyAdded: (String) -> Unit,
            onProxyEdited: (Int, String) -> Unit,
            onProxyDeleted: (Int) -> Unit,
            onDismissed: () -> Unit
        ): UpdateAcceleratorComposeDialog {
            return UpdateAcceleratorComposeDialog().apply {
                arguments = Bundle().apply {
                    putStringArrayList(
                        ARG_STRATEGY_LABELS,
                        ArrayList(strategyLabels)
                    )
                    putInt(ARG_STRATEGY_INDEX, initialStrategyIndex)
                    putStringArrayList(
                        ARG_PROXY_TEMPLATES,
                        ArrayList(initialProxyTemplates)
                    )
                    putInt(ARG_PROXY_INDEX, initialProxyIndex)
                }
                this.onStrategyChanged = onStrategyChanged
                this.onProxySelected = onProxySelected
                this.onProxyAdded = onProxyAdded
                this.onProxyEdited = onProxyEdited
                this.onProxyDeleted = onProxyDeleted
                this.onDismissed = onDismissed
            }
        }

        private const val ARG_STRATEGY_LABELS = "strategyLabels"
        private const val ARG_STRATEGY_INDEX = "strategyIndex"
        private const val ARG_PROXY_TEMPLATES = "proxyTemplates"
        private const val ARG_PROXY_INDEX = "proxyIndex"
    }
}
