package com.shimmer.reader.ui.association

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shimmer.reader.R
import com.shimmer.reader.help.config.AppearanceKitManager
import com.shimmer.reader.help.config.ImportResult
import com.shimmer.reader.ui.widget.compose.AppDialogFrame
import com.shimmer.reader.ui.widget.compose.ComposeDialogFragment
import com.shimmer.reader.ui.widget.compose.AppDialogSize
import com.shimmer.reader.ui.widget.compose.LegadoMiuixActionButton
import com.shimmer.reader.ui.widget.compose.rememberAppDialogStyle
import com.shimmer.reader.ui.widget.compose.toMiuixPalette
import com.shimmer.reader.utils.externalFiles
import com.shimmer.reader.utils.getFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ImportRedThemeDialog() : ComposeDialogFragment() {

    constructor(uri: Uri, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putParcelable("uri", uri)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        if (arguments?.getBoolean("finishOnDismiss") == true) {
            activity?.finish()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ImportRedThemeContent()
            }
        }
    }

    @Composable
    private fun ImportRedThemeContent() {
        val style = rememberAppDialogStyle()
        val palette = style.toMiuixPalette()
        var message by remember { mutableStateOf(getString(R.string.loading)) }
        var finished by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            val uri = arguments?.getParcelable<Uri>("uri")
            if (uri == null) {
                message = getString(R.string.wrong_format)
                finished = true
                return@LaunchedEffect
            }
            runCatching {
                importRedTheme(uri)
            }.onSuccess { result ->
                message = "${getString(R.string.import_str)}: ${result.total}"
            }.onFailure {
                message = it.localizedMessage ?: getString(R.string.wrong_format)
            }
            finished = true
        }

        AppDialogFrame(
            title = stringResource(R.string.import_theme),
            content = {
                Text(
                    text = message,
                    color = style.primaryText,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp)
                )
            },
            actions = {
                LegadoMiuixActionButton(
                    text = stringResource(if (finished) R.string.confirm else R.string.cancel),
                    palette = palette,
                    cornerRadius = style.actionRadius,
                    onClick = { dismissAllowingStateLoss() }
                )
            }
        )
    }

    private suspend fun importRedTheme(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val tempDir = requireContext().externalFiles.getFile("themePackageImports").apply { mkdirs() }
        val file = File(tempDir, "import_${System.currentTimeMillis()}.red")
        try {
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalArgumentException(getString(R.string.wrong_format))
            AppearanceKitManager.importPackage(file)
        } finally {
            file.delete()
        }
    }
}
