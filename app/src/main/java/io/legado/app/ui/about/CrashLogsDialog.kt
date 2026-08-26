package io.legado.app.ui.about

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.delete
import io.legado.app.utils.find
import io.legado.app.utils.getFile
import io.legado.app.utils.list
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileFilter

class CrashLogsDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    private val viewModel by viewModels<CrashViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel.initData()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                CrashLogsContent(
                    viewModel = viewModel,
                    onShowLogFile = { fileDoc -> showLogFile(fileDoc) },
                    onDismiss = { dismissAllowingStateLoss() }
                )
            }
        }
    }

    private fun showLogFile(fileDoc: FileDoc) {
        viewModel.readFile(fileDoc) {
            if (lifecycleScope.isActive) {
                showDialogFragment(TextDialog(fileDoc.name, it))
            }
        }
    }

    class CrashViewModel(application: android.app.Application) : AndroidViewModel(application) {

        private val appCtx: android.content.Context = application

        private val _logFlow = MutableStateFlow<List<FileDoc>>(emptyList())
        val logFlow = _logFlow.asStateFlow()

        fun initData() {
            viewModelScope.launch {
                try {
                    val list = withContext(Dispatchers.IO) {
                        val result = arrayListOf<FileDoc>()
                        appCtx.externalCacheDir
                            ?.getFile("crash")
                            ?.listFiles(FileFilter { it.isFile })
                            ?.forEach {
                                result.add(FileDoc.fromFile(it))
                            }
                        val backupPath = AppConfig.backupPath
                        if (!backupPath.isNullOrEmpty()) {
                            val uri = Uri.parse(backupPath)
                            FileDoc.fromUri(uri, true)
                                .find("crash")
                                ?.list {
                                    !it.isDir
                                }?.let {
                                    result.addAll(it)
                                }
                        }
                        result.sortedByDescending { it.name }.distinctBy { it.name }
                    }
                    _logFlow.value = list
                } catch (e: Exception) {
                    appCtx.toastOnUi(e.localizedMessage)
                }
            }
        }

        fun readFile(fileDoc: FileDoc, success: (String) -> Unit) {
            viewModelScope.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        String(fileDoc.readBytes())
                    }
                    success.invoke(text)
                } catch (e: Exception) {
                    appCtx.toastOnUi(e.localizedMessage)
                }
            }
        }

        fun clearCrashLog() {
            viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        appCtx.externalCacheDir
                            ?.getFile("crash")
                            ?.let {
                                FileUtils.delete(it, false)
                            }
                        val backupPath = AppConfig.backupPath
                        if (!backupPath.isNullOrEmpty()) {
                            val uri = Uri.parse(backupPath)
                            FileDoc.fromUri(uri, true)
                                .find("crash")
                                ?.delete()
                        }
                    }
                } catch (e: Exception) {
                    appCtx.toastOnUi(e.localizedMessage)
                } finally {
                    initData()
                }
            }
        }

    }

}

@Composable
private fun CrashLogsContent(
    viewModel: CrashLogsDialog.CrashViewModel,
    onShowLogFile: (FileDoc) -> Unit,
    onDismiss: () -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val logFiles by viewModel.logFlow.collectAsState()
    val title = stringResource(R.string.crash_log)

    AppDialogFrame(
        title = title,
        scrollContent = false,
        content = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(logFiles, key = { it.name }) { fileDoc ->
                    LegadoMiuixCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onShowLogFile(fileDoc) },
                        color = style.fieldSurface,
                        contentColor = style.primaryText,
                        cornerRadius = style.actionRadius,
                        insidePadding = PaddingValues(
                            horizontal = 16.dp,
                            vertical = 13.dp
                        )
                    ) {
                        Text(
                            text = fileDoc.name,
                            color = style.primaryText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        actions = {
            LegadoMiuixActionButton(
                text = stringResource(R.string.clear),
                palette = palette,
                onClick = { viewModel.clearCrashLog() },
                danger = true,
                cornerRadius = style.actionRadius
            )
            Spacer(modifier = Modifier.width(8.dp))
            LegadoMiuixActionButton(
                text = stringResource(R.string.cancel),
                palette = palette,
                onClick = onDismiss,
                cornerRadius = style.actionRadius
            )
        }
    )
}
