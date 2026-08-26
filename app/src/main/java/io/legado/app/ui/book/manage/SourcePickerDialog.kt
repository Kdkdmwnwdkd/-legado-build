package io.legado.app.ui.book.manage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.ui.widget.number.NumberPickerDialog
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn

/**
 * 书源选择
 */
class SourcePickerDialog : ComposeDialogFragment() {

    override val widthFraction: Float = 1f
    override val dialogHeight: Int = ViewGroup.LayoutParams.MATCH_PARENT

    private val callback: Callback?
        get() = (parentFragment as? Callback) ?: activity as? Callback

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoComposeTheme {
                    var query by remember { mutableStateOf("") }
                    val sourceFlow = remember(query) {
                        when {
                            query.isEmpty() -> appDb.bookSourceDao.flowEnabled()
                            else -> appDb.bookSourceDao.flowSearchEnabled(query)
                        }.catch {
                            AppLog.put("书源选择界面获取书源数据失败\n${it.localizedMessage}", it)
                        }.flowOn(IO)
                    }
                    val sources by sourceFlow.collectAsStateWithLifecycle(initialValue = emptyList())

                    SourcePickerContent(
                        query = query,
                        onQueryChange = { query = it },
                        sources = sources,
                        onPickSource = { part ->
                            part.getBookSource()?.let { callback?.sourceOnClick(it) }
                            dismissAllowingStateLoss()
                        },
                        onConfigDelay = {
                            NumberPickerDialog(requireContext())
                                .setTitle(getString(R.string.change_source_delay))
                                .setMaxValue(9999)
                                .setMinValue(0)
                                .setValue(AppConfig.batchChangeSourceDelay)
                                .show {
                                    AppConfig.batchChangeSourceDelay = it
                                }
                        },
                        onCancel = { dismiss() }
                    )
                }
            }
        }
    }

    interface Callback {
        fun sourceOnClick(source: BookSource)
    }
}

@Composable
private fun SourcePickerContent(
    query: String,
    onQueryChange: (String) -> Unit,
    sources: List<BookSourcePart>,
    onPickSource: (BookSourcePart) -> Unit,
    onConfigDelay: () -> Unit,
    onCancel: () -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    AppDialogFrame(
        title = "选择书源",
        scrollContent = false,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.search_book_source)) },
                    singleLine = true,
                    shape = RoundedCornerShape(style.actionRadius),
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
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                ) {
                    items(sources, key = { it.bookSourceUrl }) { part ->
                        SourceRow(
                            displayText = part.getDisPlayNameGroup(),
                            onClick = { onPickSource(part) },
                            style = style
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                    }
                }
            }
        },
        actions = {
            LegadoMiuixActionButton(
                text = stringResource(R.string.change_source_delay),
                palette = palette,
                onClick = onConfigDelay,
                cornerRadius = style.actionRadius
            )
            LegadoMiuixActionButton(
                text = stringResource(R.string.cancel),
                palette = palette,
                onClick = onCancel,
                cornerRadius = style.actionRadius
            )
        }
    )
}

@Composable
private fun SourceRow(
    displayText: String,
    onClick: () -> Unit,
    style: AppDialogStyle
) {
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.actionRadius))
            .clickable { onClick() },
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 14.dp,
            vertical = 12.dp
        )
    ) {
        Text(
            text = displayText,
            color = style.primaryText,
            fontFamily = style.bodyFontFamily,
            fontSize = 14.sp
        )
    }
}
