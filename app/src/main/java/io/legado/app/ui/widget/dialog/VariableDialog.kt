package io.legado.app.ui.widget.dialog

import android.app.Application
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

class VariableDialog() : ComposeDialogFragment() {

    constructor(title: String, key: String, variable: String?, comment: String) : this() {
        arguments = Bundle().apply {
            putString("title", title)
            putString("key", key)
            putString("variable", variable)
            putString("comment", comment)
        }
    }

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    val callback get() = (parentFragment as? Callback) ?: (activity as? Callback)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val arguments = arguments ?: run {
            dismiss()
            return View(requireContext())
        }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoComposeTheme {
                    val key = arguments.getString("key").orEmpty()
                    val title = arguments.getString("title").orEmpty()
                    val comment = arguments.getString("comment").orEmpty()
                    var variable by remember {
                        mutableStateOf(arguments.getString("variable").orEmpty())
                    }
                    val style = rememberAppDialogStyle()
                    val palette = style.toMiuixPalette()
                    AppDialogFrame(
                        title = title,
                        content = {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                if (comment.isNotBlank()) {
                                    Text(text = comment, color = style.secondaryText)
                                    Spacer(modifier = Modifier.height(10.dp))
                                }
                                OutlinedTextField(
                                    value = variable,
                                    onValueChange = { variable = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(style.actionRadius),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = style.primaryText,
                                        unfocusedTextColor = style.primaryText,
                                        focusedContainerColor = style.fieldSurface,
                                        unfocusedContainerColor = style.fieldSurface,
                                        cursorColor = style.accent,
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent
                                    ),
                                    textStyle = LocalTextStyle.current.copy(
                                        color = style.primaryText,
                                        fontFamily = style.bodyFontFamily
                                    )
                                )
                            }
                        },
                        actions = {
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.cancel),
                                palette = palette,
                                onClick = { dismiss() },
                                cornerRadius = style.actionRadius
                            )
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.ok),
                                palette = palette,
                                onClick = {
                                    callback?.setVariable(key, variable)
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
    }

    class ViewModel(application: Application) : BaseViewModel(application)

    interface Callback {

        fun setVariable(key: String, variable: String?)

    }

}
