package com.shimmer.reader.ui.book.import.remote

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import com.shimmer.reader.R
import com.shimmer.reader.data.entities.Server
import com.shimmer.reader.ui.widget.compose.AppDialogFrame
import com.shimmer.reader.ui.widget.compose.AppDialogStyle
import com.shimmer.reader.ui.widget.compose.ComposeDialogFragment
import com.shimmer.reader.ui.widget.compose.AppDialogSize
import com.shimmer.reader.ui.widget.compose.LegadoComposeTheme
import com.shimmer.reader.ui.widget.compose.LegadoMiuixActionButton
import com.shimmer.reader.ui.widget.compose.rememberAppDialogStyle
import com.shimmer.reader.ui.widget.compose.toMiuixPalette
import com.shimmer.reader.utils.GSON

class ServerConfigDialog() : ComposeDialogFragment() {

    constructor(id: Long) : this() {
        arguments = Bundle().apply {
            putLong("id", id)
        }
    }

    private val viewModel by viewModels<ServerConfigViewModel>()

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoComposeTheme {
                    var name by remember { mutableStateOf("") }
                    var url by remember { mutableStateOf("") }
                    var username by remember { mutableStateOf("") }
                    var password by remember { mutableStateOf("") }
                    LaunchedEffect(Unit) {
                        viewModel.init(arguments?.getLong("id")) {
                            val server = viewModel.mServer
                            name = server?.name.orEmpty()
                            val config = server?.getConfigJsonObject()
                            url = config?.optString("url").orEmpty()
                            username = config?.optString("username").orEmpty()
                            password = config?.optString("password").orEmpty()
                        }
                    }
                    ServerConfigContent(
                        name = name,
                        url = url,
                        username = username,
                        password = password,
                        onNameChange = { name = it },
                        onUrlChange = { url = it },
                        onUsernameChange = { username = it },
                        onPasswordChange = { password = it },
                        onSave = {
                            viewModel.save(buildServer(name, url, username, password)) {
                                dismissAllowingStateLoss()
                            }
                        },
                        onCancel = { dismiss() }
                    )
                }
            }
        }
    }

    private fun buildServer(name: String, url: String, username: String, password: String): Server {
        val server = viewModel.mServer?.copy() ?: Server()
        server.name = name
        server.type = Server.TYPE.WEBDAV
        server.config = GSON.toJson(
            hashMapOf("url" to url, "username" to username, "password" to password)
        )
        return server
    }
}

@Composable
private fun ServerConfigContent(
    name: String,
    url: String,
    username: String,
    password: String,
    onNameChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    AppDialogFrame(
        title = stringResource(R.string.web_dav_set),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ServerField(stringResource(R.string.name), name, onNameChange, style)
                Spacer(modifier = Modifier.height(10.dp))
                ServerField("url", url, onUrlChange, style)
                Spacer(modifier = Modifier.height(10.dp))
                ServerField("username", username, onUsernameChange, style)
                Spacer(modifier = Modifier.height(10.dp))
                ServerField(
                    "password",
                    password,
                    onPasswordChange,
                    style,
                    password = true
                )
            }
        },
        actions = {
            LegadoMiuixActionButton(
                text = stringResource(R.string.cancel),
                palette = palette,
                onClick = onCancel,
                cornerRadius = style.actionRadius
            )
            LegadoMiuixActionButton(
                text = stringResource(R.string.ok),
                palette = palette,
                onClick = onSave,
                primary = true,
                cornerRadius = style.actionRadius
            )
        }
    )
}

@Composable
private fun ServerField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    style: AppDialogStyle,
    password: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        shape = RoundedCornerShape(style.actionRadius),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = style.primaryText,
            unfocusedTextColor = style.primaryText,
            focusedContainerColor = style.fieldSurface,
            unfocusedContainerColor = style.fieldSurface,
            cursorColor = style.accent,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            focusedLabelColor = style.accent,
            unfocusedLabelColor = style.secondaryText
        ),
        textStyle = LocalTextStyle.current.copy(
            color = style.primaryText,
            fontFamily = style.bodyFontFamily
        )
    )
}
