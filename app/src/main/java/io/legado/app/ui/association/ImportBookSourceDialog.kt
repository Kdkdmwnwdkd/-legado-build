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
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppListSpacing
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeTextFormDialogWithChecks
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.showDialogFragment

/**
 * 导入书源弹出窗口
 */
class ImportBookSourceDialog() : ComposeDialogFragment(),
    CodeDialog.Callback {

    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("source", source)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    private val viewModel by viewModels<ImportBookSourceViewModel>()

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
                ImportBookSourceContent()
            }
        }
    }

    private fun showGroupDialog() {
        showComposeTextFormDialogWithChecks(
            title = getString(R.string.diy_edit_source_group),
            labels = listOf(getString(R.string.group_name)),
            initialValues = listOf(viewModel.groupName ?: ""),
            checkboxLabels = listOf(getString(R.string.custom_group_summary)),
            checkedIndices = if (viewModel.isAddGroup) setOf(0) else emptySet(),
            positiveText = getString(android.R.string.ok),
            negativeText = getString(R.string.cancel),
            onPositive = { values, checked ->
                viewModel.isAddGroup = checked.getOrNull(0) ?: false
                viewModel.groupName = values.getOrNull(0)
            }
        )
    }

    private fun showMenuDialog() {
        val labels = mutableListOf(
            getString(R.string.diy_source_group),
            getString(R.string.select_new_source),
            getString(R.string.select_update_source),
            getString(R.string.keep_original_name),
            getString(R.string.keep_group),
            getString(R.string.keep_enable),
            getString(R.string.show_source_comment)
        )
        showComposeActionListDialog(
            title = getString(R.string.import_book_source),
            labels = labels
        ) { index ->
            when (index) {
                0 -> showGroupDialog()
                1 -> {
                    val selectAllNew = viewModel.isSelectAllNew
                    viewModel.newSourceStatus.forEachIndexed { i, b ->
                        if (b) {
                            viewModel.selectStatus[i] = !selectAllNew
                        }
                    }
                }
                2 -> {
                    val selectAllUpdate = viewModel.isSelectAllUpdate
                    viewModel.updateSourceStatus.forEachIndexed { i, b ->
                        if (b) {
                            viewModel.selectStatus[i] = !selectAllUpdate
                        }
                    }
                }
                3 -> putPrefBoolean(PreferKey.importKeepName, !AppConfig.importKeepName)
                4 -> putPrefBoolean(PreferKey.importKeepGroup, !AppConfig.importKeepGroup)
                5 -> AppConfig.importKeepEnable = !AppConfig.importKeepEnable
                6 -> AppConfig.importShowComment = !AppConfig.importShowComment
            }
        }
    }

    @Composable
    private fun ImportBookSourceContent() {
        val style = rememberAppDialogStyle()
        val palette = style.toMiuixPalette()
        var loadState by remember { mutableStateOf(ImportLoadState.LOADING) }
        var errorMsg by remember { mutableStateOf("") }
        var importErrorMsg by remember { mutableStateOf("") }
        var refreshTrigger by remember { mutableIntStateOf(0) }
        val conflictRefreshPending = viewModel.conflictRefreshPending
        val conflictRefreshError = viewModel.conflictRefreshError

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
            title = stringResource(R.string.import_book_source),
            scrollContent = false,
            content = {
                // Menu row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LegadoMiuixActionButton(
                        text = getString(R.string.diy_source_group),
                        palette = palette,
                        onClick = { showGroupDialog() },
                        cornerRadius = style.actionRadius
                    )
                    LegadoMiuixActionButton(
                        text = getString(R.string.keep_original_name),
                        palette = palette,
                        onClick = { showMenuDialog() },
                        cornerRadius = style.actionRadius
                    )
                }

                if (conflictRefreshPending) {
                    Text(
                        text = getString(R.string.loading),
                        color = style.secondaryText,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                } else if (!conflictRefreshError.isNullOrBlank()) {
                    Text(
                        text = "重新检查书源冲突失败：$conflictRefreshError",
                        color = style.danger,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                if (importErrorMsg.isNotBlank()) {
                    Text(
                        text = importErrorMsg,
                        color = style.danger,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

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
                        refreshTrigger
                        val allSources = viewModel.allSources
                        val selectStatus = viewModel.selectStatus
                        val checkSources = viewModel.checkSources

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
                                key = { index, it -> "${it.bookSourceUrl}#$index" }
                            ) { index, item ->
                                val isChecked = selectStatus.getOrNull(index) ?: false
                                val localSource = checkSources.getOrNull(index)
                                val stateText = when {
                                    localSource == null -> "新增"
                                    item.lastUpdateTime > localSource.lastUpdateTime -> "更新"
                                    else -> "已有"
                                }
                                val comment = if (AppConfig.importShowComment) {
                                    item.bookSourceComment?.takeIf { it.isNotBlank() }
                                } else {
                                    null
                                }
                                ImportSourceItemRow(
                                    name = item.bookSourceName ?: "",
                                    isChecked = isChecked,
                                    stateText = stateText,
                                    style = style,
                                    comment = comment,
                                    onCodeView = {
                                        showDialogFragment(
                                            CodeDialog(
                                                GSON.toJson(item),
                                                disableEdit = false,
                                                requestId = index.toString()
                                            )
                                        )
                                    },
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
                            if (!viewModel.conflictRefreshPending) {
                                importErrorMsg = ""
                                val waitDialog = WaitDialog(requireContext())
                                waitDialog.setCancelable(false)
                                waitDialog.show()
                                viewModel.importSelect(
                                    onSuccess = {
                                        waitDialog.dismiss()
                                        dismissAllowingStateLoss()
                                    },
                                    onError = { error ->
                                        waitDialog.dismiss()
                                        val detail = error.localizedMessage
                                            ?: getString(R.string.unknown_error)
                                        importErrorMsg = "导入失败：$detail"
                                    }
                                )
                            }
                        },
                        primary = true,
                        cornerRadius = style.actionRadius
                    )
                }
            }
        )
    }

    override fun onCodeSave(code: String, requestId: String?) {
        requestId?.toInt()?.let {
            GSON.fromJsonObject<BookSource>(code).getOrNull()?.let { source ->
                viewModel.updateEditedSource(it, source)
            }
        }
    }
}
