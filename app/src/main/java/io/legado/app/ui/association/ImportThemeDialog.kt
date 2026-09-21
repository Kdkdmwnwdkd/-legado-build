package io.legado.app.ui.association

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import io.legado.app.R
import io.legado.app.help.config.ThemeConfig
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppListSpacing
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.ui.widget.dialog.WaitDialog

/**
 * 导入主题弹出窗口
 */
class ImportThemeDialog() : ComposeDialogFragment() {

    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("source", source)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    private val viewModel by viewModels<ImportThemeViewModel>()

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (arguments?.getBoolean("finishOnDismiss") == true) {
            activity?.finish()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val source = arguments?.getString("source")
        if (source.isNullOrEmpty()) {
            dismissAllowingStateLoss()
        } else {
            viewModel.importSource(source)
        }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ImportThemeContent()
            }
        }
    }

    @Composable
    private fun ImportThemeContent() {
        val style = rememberAppDialogStyle()
        val palette = style.toMiuixPalette()
        var loadState by remember { mutableStateOf(ImportLoadState.LOADING) }
        var errorMsg by remember { mutableStateOf("") }
        var refreshTrigger by remember { mutableIntStateOf(0) }

        DisposableEffect(Unit) {
            val errorObserver = Observer<String> {
                loadState = ImportLoadState.ERROR
                errorMsg = it ?: ""
            }
            val successObserver = Observer<Int> { count ->
                if (count != null && count > 0) {
                    loadState = ImportLoadState.SUCCESS
                    refreshTrigger++
                } else {
                    loadState = ImportLoadState.ERROR
                    errorMsg = getString(R.string.wrong_format)
                }
            }
            viewModel.errorLiveData.observe(viewLifecycleOwner, errorObserver)
            viewModel.successLiveData.observe(viewLifecycleOwner, successObserver)
            onDispose {
                viewModel.errorLiveData.removeObserver(errorObserver)
                viewModel.successLiveData.removeObserver(successObserver)
            }
        }

        AppDialogFrame(
            title = stringResource(R.string.import_theme),
            scrollContent = false,
            content = {
                when (loadState) {
                    ImportLoadState.LOADING -> {
                        Text(
                            text = getString(R.string.loading),
                            color = style.secondaryText,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp)
                        )
                    }
                    ImportLoadState.ERROR -> {
                        Text(
                            text = errorMsg,
                            color = style.primaryText,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp)
                        )
                    }
                    ImportLoadState.SUCCESS -> {
                        // Force recomposition when items change
                        refreshTrigger
                        val allSources = viewModel.allSources
                        val selectStatus = viewModel.selectStatus
                        val checkSources = viewModel.checkSources

                        // Select all / deselect all
                        val selectAllText = if (viewModel.isSelectAll) {
                            getString(R.string.select_cancel_count, viewModel.selectCount, allSources.size)
                        } else {
                            getString(R.string.select_all_count, viewModel.selectCount, allSources.size)
                        }
                        Text(
                            text = selectAllText,
                            color = style.accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val selectAll = viewModel.isSelectAll
                                    viewModel.selectStatus.forEachIndexed { index, b ->
                                        if (b != !selectAll) {
                                            viewModel.selectStatus[index] = !selectAll
                                        }
                                    }
                                    refreshTrigger++
                                }
                                .padding(vertical = 6.dp)
                        )

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(AppListSpacing.Normal)
                        ) {
                            itemsIndexed(
                                allSources,
                                key = { index, it -> "${it.themeName}#$index" }
                            ) { index, item ->
                                val isChecked = selectStatus.getOrNull(index) ?: false
                                val localSource = checkSources.getOrNull(index)
                                val stateText = when {
                                    localSource == null -> "新增"
                                    localSource != item -> "更新"
                                    else -> "已有"
                                }
                                ImportSourceItemRow(
                                    name = item.themeName ?: "",
                                    isChecked = isChecked,
                                    stateText = stateText,
                                    style = style,
                                    onCheckedChange = { checked ->
                                        if (index in selectStatus.indices) {
                                            viewModel.selectStatus[index] = checked
                                            refreshTrigger++
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            },
            actions = {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.cancel),
                    palette = palette,
                    onClick = { dismissAllowingStateLoss() },
                    cornerRadius = style.actionRadius
                )
                if (loadState == ImportLoadState.SUCCESS) {
                    Spacer(modifier = Modifier.width(8.dp))
                    LegadoMiuixActionButton(
                        text = stringResource(R.string.ok),
                        palette = palette,
                        onClick = {
                            val waitDialog = WaitDialog(requireContext())
                            waitDialog.show()
                            viewModel.importSelect {
                                waitDialog.dismiss()
                                dismissAllowingStateLoss()
                            }
                        },
                        primary = true,
                        cornerRadius = style.actionRadius
                    )
                }
            }
        )
    }
}
