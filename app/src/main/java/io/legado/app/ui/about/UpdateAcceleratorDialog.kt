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
import io.legado.app.R
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixChoiceRow
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.showDialogFragment

object UpdateAcceleratorDialog {

    fun show(fragment: Fragment, onChanged: () -> Unit) {
        val initialProxyIndex = AppUpdateConfig.githubProxyIndex
        val initialProxyTemplates = AppUpdateConfig.githubProxyTemplates.toList()

        fragment.showDialogFragment(
            UpdateAcceleratorComposeDialog.create(
                initialProxyTemplates = initialProxyTemplates,
                initialProxyIndex = initialProxyIndex,
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

                var proxyTemplates by rememberSaveable {
                    mutableStateOf(
                        args.getStringArrayList(ARG_PROXY_TEMPLATES)?.toList().orEmpty()
                    )
                }
                var selectedProxyIndex by rememberSaveable {
                    mutableIntStateOf(args.getInt(ARG_PROXY_INDEX))
                }

                AppDialogFrame(
                    title = getString(R.string.update_accel_title),
                    scrollContent = true,
                    content = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = getString(R.string.update_accel_github_proxy),
                                color = style.accent,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = getString(R.string.update_accel_template_hint),
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
                                        text = getString(R.string.update_accel_no_proxy),
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
                                    text = getString(R.string.update_accel_add),
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
                                    text = getString(R.string.edit),
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
                                    text = getString(R.string.delete),
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
                            text = getString(R.string.ok),
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
            title = getString(
                if (oldValue == null) R.string.update_accel_add else R.string.update_accel_edit
            ),
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
            initialProxyTemplates: List<String>,
            initialProxyIndex: Int,
            onProxySelected: (Int) -> Unit,
            onProxyAdded: (String) -> Unit,
            onProxyEdited: (Int, String) -> Unit,
            onProxyDeleted: (Int) -> Unit,
            onDismissed: () -> Unit
        ): UpdateAcceleratorComposeDialog {
            return UpdateAcceleratorComposeDialog().apply {
                arguments = Bundle().apply {
                    putStringArrayList(
                        ARG_PROXY_TEMPLATES,
                        ArrayList(initialProxyTemplates)
                    )
                    putInt(ARG_PROXY_INDEX, initialProxyIndex)
                }
                this.onProxySelected = onProxySelected
                this.onProxyAdded = onProxyAdded
                this.onProxyEdited = onProxyEdited
                this.onProxyDeleted = onProxyDeleted
                this.onDismissed = onDismissed
            }
        }

        private const val ARG_PROXY_TEMPLATES = "proxyTemplates"
        private const val ARG_PROXY_INDEX = "proxyIndex"
    }
}
