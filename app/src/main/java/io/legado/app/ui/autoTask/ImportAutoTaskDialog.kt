package io.legado.app.ui.autoTask

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.ui.association.ImportSourceItemRow
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppListSpacing
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.showDialogFragment

class ImportAutoTaskDialog() : ComposeDialogFragment(), CodeDialog.Callback {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    private val viewModel by viewModels<ImportAutoTaskViewModel>()

    constructor(source: String) : this() {
        arguments = bundleOf(ARG_SOURCE to source)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel.load(arguments?.getString(ARG_SOURCE))
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val state by viewModel.state.collectAsStateWithLifecycle()
                ImportAutoTaskContent(state)
            }
        }
    }

    @Composable
    private fun ImportAutoTaskContent(state: ImportAutoTaskState) {
        val style = rememberAppDialogStyle()
        val palette = style.toMiuixPalette()
        AppDialogFrame(
            title = stringResource(R.string.import_auto_task),
            scrollContent = false,
            content = {
                when (state) {
                    ImportAutoTaskState.Loading -> LoadingContent(style)
                    is ImportAutoTaskState.Error -> ErrorContent(state.message, style)
                    ImportAutoTaskState.Imported -> {
                        Text(
                            text = stringResource(R.string.auto_task_import_done),
                            color = style.primaryText,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                    is ImportAutoTaskState.Ready -> ReadyContent(state.items, style)
                    is ImportAutoTaskState.Saving -> ReadyContent(state.items, style)
                }
            },
            actions = {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.cancel),
                    palette = palette,
                    onClick = { dismissAllowingStateLoss() },
                    cornerRadius = style.actionRadius
                )
                if (state is ImportAutoTaskState.Ready) {
                    Spacer(modifier = Modifier.width(8.dp))
                    LegadoMiuixActionButton(
                        text = stringResource(R.string.import_),
                        palette = palette,
                        primary = true,
                        onClick = {
                            viewModel.importSelected(
                                onSuccess = {
                                    parentFragmentManager.setFragmentResult(
                                        RESULT_KEY,
                                        bundleOf(RESULT_REFRESH to true)
                                    )
                                    dismissAllowingStateLoss()
                                },
                                onError = { error ->
                                    context?.let { ctx ->
                                        android.widget.Toast.makeText(
                                            ctx,
                                            error.localizedMessage
                                                ?: getString(R.string.wrong_format),
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            )
                        },
                        cornerRadius = style.actionRadius
                    )
                }
            }
        )
    }

    @Composable
    private fun LoadingContent(style: io.legado.app.ui.widget.compose.AppDialogStyle) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircularProgressIndicator(color = style.accent)
            Text(
                text = stringResource(R.string.loading),
                color = style.secondaryText,
                fontSize = 14.sp
            )
        }
    }

    @Composable
    private fun ErrorContent(message: String, style: io.legado.app.ui.widget.compose.AppDialogStyle) {
        Text(
            text = message,
            color = style.primaryText,
            fontSize = 14.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp)
        )
    }

    @Composable
    private fun ReadyContent(
        items: List<ImportAutoTaskItem>,
        style: io.legado.app.ui.widget.compose.AppDialogStyle
    ) {
        val selectedCount = items.count { it.selected }
        Text(
            text = if (selectedCount == items.size) {
                stringResource(R.string.select_cancel_count, selectedCount, items.size)
            } else {
                stringResource(R.string.select_all_count, selectedCount, items.size)
            },
            color = style.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.toggleAll() }
                .padding(vertical = 6.dp)
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(AppListSpacing.Normal)
        ) {
            itemsIndexed(
                items = items,
                key = { index, item -> "${item.rule.id}#$index" }
            ) { index, item ->
                ImportSourceItemRow(
                    name = item.rule.name.ifBlank { item.rule.id },
                    isChecked = item.selected,
                    stateText = stateText(item.state),
                    style = style,
                    comment = item.rule.comment,
                    onCodeView = {
                        showDialogFragment(
                            CodeDialog(
                                code = GSON.toJson(item.rule),
                                disableEdit = false,
                                requestId = index.toString()
                            )
                        )
                    },
                    onCheckedChange = { checked -> viewModel.toggle(index, checked) }
                )
            }
        }
    }

    private fun stateText(state: io.legado.app.model.AutoTaskImport.State): String {
        return when (state) {
            io.legado.app.model.AutoTaskImport.State.NEW -> getString(R.string.auto_task_import_new)
            io.legado.app.model.AutoTaskImport.State.UPDATE -> getString(R.string.auto_task_import_update)
            io.legado.app.model.AutoTaskImport.State.EXISTING -> getString(R.string.auto_task_import_existing)
        }
    }

    override fun onCodeSave(code: String, requestId: String?) {
        requestId?.toIntOrNull()?.let { index ->
            viewModel.updateRuleFromJson(index, code)
        }
    }

    companion object {
        const val RESULT_KEY = "auto_task_imported"
        const val RESULT_REFRESH = "refresh"
        private const val ARG_SOURCE = "source"
    }
}
