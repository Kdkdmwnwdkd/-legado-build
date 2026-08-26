package io.legado.app.ui.file

import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.FileProvider
import io.legado.app.constant.AppConst
import io.legado.app.databinding.ActivityFileManageBinding
import io.legado.app.utils.openFileUri
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.ui.widget.compose.ComposeConfirmDialog
import java.io.File

class FileManageActivity : io.legado.app.base.VMBaseActivity<ActivityFileManageBinding, FileManageViewModel>() {

    companion object {
        const val EXTRA_ROOT_PATH = "rootPath"
        const val EXTRA_TITLE = "title"
    }

    override val binding by viewBinding(ActivityFileManageBinding::inflate)
    override val viewModel by viewModels<FileManageViewModel>()
    private val dirParent = ".."
    private val pageTitle by lazy {
        intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { getString(io.legado.app.R.string.file_manage) }
    }

    private val filesState = mutableStateOf<List<File>>(emptyList())
    private val subDocsState = mutableStateOf<List<File>>(emptyList())
    private val searchQueryState = mutableStateOf("")

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        intent.getStringExtra(EXTRA_ROOT_PATH)
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.apply { mkdirs() }
            ?.let(viewModel::setRoot)
        initComposeContent()
        onBackPressedDispatcher.addCallback(this) {
            handleBack()
        }
        viewModel.upFiles(viewModel.rootDoc)
    }

    private fun initComposeContent() {
        val container = binding.container
        container.removeAllViews()
        val cv = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setContent {
                FileManageScreen(
                    title = pageTitle,
                    currentFiles = filesState.value,
                    subDocs = subDocsState.value,
                    searchQuery = searchQueryState.value,
                    onSearchQueryChange = { searchQueryState.value = it },
                    onFileClick = ::onFileClick,
                    onFileLongClick = ::onFileLongClick,
                    onBreadcrumbClick = ::onBreadcrumbClick,
                    onRootBreadcrumbClick = ::onRootBreadcrumbClick,
                    onBackClick = { finish() }
                )
            }
        }
        container.addView(cv)
    }

    private fun handleBack() {
        if (viewModel.lastDir != viewModel.rootDoc) {
            gotoLastDir()
        } else {
            finish()
        }
    }

    private fun onFileClick(file: File) {
        if (file.name == dirParent) {
            gotoLastDir()
        } else if (file.isDirectory && !file.isHiddenWorkspaceBackupDir()) {
            viewModel.subDocs.add(file)
            subDocsState.value = viewModel.subDocs.toList()
            viewModel.upFiles(file)
        } else if (file.isDirectory) {
            return
        } else {
            openFileUri(
                FileProvider.getUriForFile(
                    this,
                    AppConst.authority,
                    file
                )
            )
        }
    }

    private fun onFileLongClick(file: File) {
        if (file.name == dirParent) return
        showDialogFragment(
            ComposeConfirmDialog.create(
                title = file.name,
                message = file.absolutePath,
                positiveText = getString(io.legado.app.R.string.delete),
                negativeText = getString(io.legado.app.R.string.cancel),
                dangerPositive = true,
                onPositive = { viewModel.delFile(file) }
            )
        )
    }

    private fun onBreadcrumbClick(index: Int) {
        viewModel.subDocs = viewModel.subDocs.subList(0, index + 1).toMutableList()
        subDocsState.value = viewModel.subDocs.toList()
        viewModel.upFiles(viewModel.subDocs.lastOrNull())
    }

    private fun onRootBreadcrumbClick() {
        viewModel.subDocs.clear()
        subDocsState.value = emptyList()
        viewModel.upFiles(viewModel.rootDoc)
    }

    private fun gotoLastDir() {
        viewModel.subDocs.removeLastOrNull()
        subDocsState.value = viewModel.subDocs.toList()
        viewModel.upFiles(viewModel.lastDir)
    }

    override fun observeLiveBus() {
        viewModel.filesLiveData.observe(this) {
            filesState.value = it
            subDocsState.value = viewModel.subDocs.toList()
        }
    }

}

private fun File.isHiddenWorkspaceBackupDir(): Boolean {
    return name.lowercase() in setOf(".backup", ".backups")
}
