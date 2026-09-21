package io.legado.app.ui.autoTask

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.model.AutoTaskRunStatus
import io.legado.app.ui.widget.compose.AppManagementCard
import io.legado.app.ui.widget.compose.AppManagementLazyColumn
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AutoTaskDebugScreen(
    state: AutoTaskDebugState,
    onCancel: () -> Unit,
    onClearLogs: () -> Unit
) {
    val palette = rememberAppManagementPalette()
    val listState = rememberLazyListState()
    val logs = when (state) {
        is AutoTaskDebugState.Running -> state.logs
        is AutoTaskDebugState.Finished -> state.logs
        is AutoTaskDebugState.Cancelled -> state.logs
        else -> emptyList()
    }
    AppManagementLazyColumn(
        palette = palette,
        state = listState,
        showFastScroller = false,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item(key = "status") {
            DebugStatusCard(
                state = state,
                palette = palette,
                onCancel = onCancel,
                onClearLogs = onClearLogs
            )
        }
        if (logs.isEmpty()) {
            item(key = "empty-log") {
                Text(
                    text = stringResource(R.string.auto_task_debug_no_logs),
                    color = palette.settings.secondaryText,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 32.dp)
                )
            }
        } else {
            itemsIndexed(
                items = logs,
                key = { index, _ -> "log-$index" }
            ) { _, line ->
                AppManagementCard(
                    palette = palette,
                    drawPanelImage = false,
                    insidePadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = line,
                            color = palette.settings.primaryText,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            fontFamily = palette.settings.bodyFontFamily,
                            overflow = TextOverflow.Clip
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugStatusCard(
    state: AutoTaskDebugState,
    palette: AppManagementPalette,
    onCancel: () -> Unit,
    onClearLogs: () -> Unit
) {
    val taskName = when (state) {
        is AutoTaskDebugState.Running -> state.task.name
        is AutoTaskDebugState.Finished -> state.task.name
        is AutoTaskDebugState.Cancelled -> state.task.name
        else -> ""
    }
    AppManagementCard(
        palette = palette,
        drawPanelImage = false,
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = taskName.ifBlank { stringResource(R.string.auto_task_debug_title) },
            color = palette.settings.primaryText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = stateLabel(state),
            color = stateColor(state, palette),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 5.dp)
        )
        stateResult(state)?.let { resultText ->
            Text(
                text = resultText,
                color = palette.settings.secondaryText,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.End
        ) {
            if (state is AutoTaskDebugState.Running) {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.auto_task_debug_cancel),
                    palette = palette.miuix,
                    danger = true,
                    onClick = onCancel,
                    minHeight = 34.dp,
                    insidePadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                    cornerRadius = palette.miuix.actionRadius
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            LegadoMiuixActionButton(
                text = stringResource(R.string.auto_task_debug_clear),
                palette = palette.miuix,
                onClick = onClearLogs,
                minHeight = 34.dp,
                insidePadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                cornerRadius = palette.miuix.actionRadius
            )
        }
    }
}

@Composable
private fun stateLabel(state: AutoTaskDebugState): String = when (state) {
    AutoTaskDebugState.Idle -> stringResource(R.string.auto_task_debug_idle)
    is AutoTaskDebugState.Loading -> stringResource(R.string.auto_task_debug_loading)
    is AutoTaskDebugState.Running -> stringResource(R.string.auto_task_debug_running)
    is AutoTaskDebugState.Cancelled -> stringResource(R.string.auto_task_debug_cancelled)
    is AutoTaskDebugState.Missing -> stringResource(R.string.auto_task_debug_missing)
    is AutoTaskDebugState.Finished -> when (state.result.status) {
        AutoTaskRunStatus.SUCCESS -> stringResource(R.string.auto_task_debug_success)
        AutoTaskRunStatus.NO_ACTION -> stringResource(R.string.auto_task_debug_no_action)
        AutoTaskRunStatus.INVALID -> stringResource(R.string.auto_task_debug_invalid)
        AutoTaskRunStatus.FAILED -> stringResource(R.string.auto_task_debug_failed)
        AutoTaskRunStatus.TIMED_OUT -> stringResource(R.string.auto_task_debug_timed_out)
    }
}

@Composable
private fun stateResult(state: AutoTaskDebugState): String? {
    val result = (state as? AutoTaskDebugState.Finished)?.result ?: return null
    val detail = result.error ?: result.detail ?: result.summaries.joinToString(" | ")
    return buildString {
        append(stringResource(R.string.auto_task_debug_duration, result.durationMs))
        if (detail.isNotBlank()) {
            append("\n")
            append(stringResource(R.string.auto_task_debug_result, detail))
        }
    }
}

private fun stateColor(
    state: AutoTaskDebugState,
    palette: AppManagementPalette
) = when (state) {
    is AutoTaskDebugState.Finished -> when (state.result.status) {
        AutoTaskRunStatus.SUCCESS, AutoTaskRunStatus.NO_ACTION -> palette.settings.accent
        else -> palette.settings.danger
    }
    is AutoTaskDebugState.Cancelled -> palette.settings.secondaryText
    is AutoTaskDebugState.Running, is AutoTaskDebugState.Loading -> palette.settings.accent
    else -> palette.settings.secondaryText
}
