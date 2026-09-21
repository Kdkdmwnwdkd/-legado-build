package io.legado.app.ui.autoTask

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.model.AutoTaskRule
import io.legado.app.ui.widget.compose.AppManagementLazyColumn
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AutoTaskScreen(
    rules: List<AutoTaskRule>,
    emptyText: String,
    selectedIds: Set<String>,
    isSelectMode: Boolean,
    reorderEnabled: Boolean,
    onReorder: (List<AutoTaskRule>) -> Unit,
    onToggleSelection: (AutoTaskRule) -> Unit,
    onToggleEnabled: (AutoTaskRule, Boolean) -> Unit,
    onEdit: (AutoTaskRule) -> Unit,
    onLogin: (AutoTaskRule) -> Unit,
    onRunNow: (AutoTaskRule) -> Unit,
    onShowLog: (AutoTaskRule) -> Unit,
    onDelete: (AutoTaskRule) -> Unit
) {
    val palette = rememberAppManagementPalette()
    val listState = rememberLazyListState()
    val snapshot = rules.toList()
    val signature = snapshot.joinToString("\u001F") {
        listOf(it.id, it.name, it.enable, it.cron, it.lastRunAt, it.lastError, it.sortOrder)
            .joinToString("\u001E")
    }
    var orderedRules by remember {
        mutableStateOf(snapshot, referentialEqualityPolicy())
    }
    LaunchedEffect(reorderEnabled, signature) {
        orderedRules = snapshot
    }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        if (from.index in orderedRules.indices && to.index in orderedRules.indices) {
            orderedRules = orderedRules.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
        }
    }

    AppManagementLazyColumn(
        palette = palette,
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        val displayed = if (reorderEnabled) orderedRules else snapshot
        if (displayed.isEmpty()) {
            item(key = "empty") {
                EmptyTaskState(
                    palette = palette,
                    text = emptyText
                )
            }
        } else {
            items(
                items = displayed,
                key = { it.id },
                contentType = { "autoTask" }
            ) { rule ->
                val row: @Composable ( (@Composable () -> Unit)? ) -> Unit = { dragHandle ->
                    AutoTaskItemRow(
                        rule = rule,
                        palette = palette,
                        selected = rule.id in selectedIds,
                        isSelectMode = isSelectMode,
                        dragHandle = dragHandle,
                        onToggleSelection = { onToggleSelection(rule) },
                        onToggleEnabled = { onToggleEnabled(rule, it) },
                        onEdit = { onEdit(rule) },
                        onLogin = { onLogin(rule) },
                        onRunNow = { onRunNow(rule) },
                        onShowLog = { onShowLog(rule) },
                        onDelete = { onDelete(rule) }
                    )
                }
                if (reorderEnabled) {
                    ReorderableItem(reorderState, key = rule.id) {
                        row {
                            Icon(
                                painter = painterResource(R.drawable.ic_drag_handle),
                                contentDescription = stringResource(R.string.sort),
                                tint = palette.settings.secondaryText,
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .size(22.dp)
                                    .draggableHandle(
                                        onDragStopped = { onReorder(orderedRules) }
                                    )
                            )
                        }
                    }
                } else {
                    row(null)
                }
            }
        }
    }
}

@Composable
private fun EmptyTaskState(palette: AppManagementPalette, text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 56.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = palette.settings.secondaryText,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AutoTaskItemRow(
    rule: AutoTaskRule,
    palette: AppManagementPalette,
    selected: Boolean,
    isSelectMode: Boolean,
    dragHandle: (@Composable () -> Unit)?,
    onToggleSelection: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onLogin: () -> Unit,
    onRunNow: () -> Unit,
    onShowLog: () -> Unit,
    onDelete: () -> Unit
) {
    AppManagementListRow(
        title = rule.name.ifBlank { rule.id },
        subtitle = stringResource(
            R.string.auto_task_item_summary,
            rule.cron?.trim().orEmpty().ifBlank { "-" },
            taskStatus(rule)
        ),
        palette = palette,
        selected = selected,
        selectionVisible = isSelectMode,
        animatedSelection = true,
        onToggleSelection = onToggleSelection,
        switchChecked = rule.enable,
        onSwitchChange = onToggleEnabled,
        titleMaxLines = 1,
        subtitleMaxLines = 2,
        minHeight = 56.dp,
        drawPanelImage = false,
        onClick = { if (isSelectMode) onToggleSelection() else onEdit() },
        onLongClick = onToggleSelection,
        onEdit = onEdit,
        moreActions = buildList {
            add(
                AppManagementMenuAction(
                    text = stringResource(R.string.login),
                    onClick = onLogin
                )
            )
            add(
                AppManagementMenuAction(
                    text = stringResource(R.string.auto_task_debug),
                    onClick = onRunNow
                )
            )
            add(
                AppManagementMenuAction(
                    text = stringResource(R.string.log),
                    onClick = onShowLog
                )
            )
            add(
                AppManagementMenuAction(
                    text = stringResource(R.string.delete),
                    danger = true,
                    onClick = onDelete
                )
            )
        },
        leadingContent = dragHandle
    )
}

@Composable
private fun taskStatus(rule: AutoTaskRule): String {
    return when {
        !rule.lastError.isNullOrBlank() -> stringResource(
            R.string.auto_task_last_error,
            rule.lastError.orEmpty()
        )
        rule.lastRunAt > 0L -> stringResource(
            R.string.auto_task_last_run,
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(rule.lastRunAt))
        )
        else -> stringResource(R.string.auto_task_not_run)
    }
}
