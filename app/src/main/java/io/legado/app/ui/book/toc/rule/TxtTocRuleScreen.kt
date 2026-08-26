package io.legado.app.ui.book.toc.rule

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.ui.widget.compose.AppManagementLazyColumn
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
internal fun TxtTocRuleScreen(
    rules: List<TxtTocRule>,
    selectedIds: Set<Long>,
    isSelectMode: Boolean,
    onReorder: (List<TxtTocRule>) -> Unit,
    onToggleSelect: (TxtTocRule) -> Unit,
    onToggleEnable: (TxtTocRule, Boolean) -> Unit,
    onEdit: (TxtTocRule) -> Unit,
    ruleMenuActions: (TxtTocRule) -> List<AppManagementMenuAction>,
    modifier: Modifier = Modifier
) {
    val palette = rememberAppManagementPalette()
    val lazyListState = rememberLazyListState()
    val rulesSnapshot = rules.toList()
    val rulesSignature = rulesSnapshot.joinToString(separator = "\u001F") { it.toString() }
    var orderedRules by remember {
        mutableStateOf(rulesSnapshot, referentialEqualityPolicy())
    }
    LaunchedEffect(rulesSignature) {
        orderedRules = rulesSnapshot
    }
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        orderedRules = orderedRules.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
    }

    AppManagementLazyColumn(
        palette = palette,
        state = lazyListState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        items(
            items = orderedRules,
            key = { rule -> rule.id },
            contentType = { "txtTocRule" }
        ) { rule ->
            ReorderableItem(reorderState, key = rule.id) {
                TxtTocRuleItemRow(
                    name = rule.name,
                    example = rule.example,
                    enabled = rule.enable,
                    isSelected = selectedIds.contains(rule.id),
                    isSelectMode = isSelectMode,
                    palette = palette,
                    onToggleSelect = { onToggleSelect(rule) },
                    onToggleEnable = { enabled -> onToggleEnable(rule, enabled) },
                    onEdit = { onEdit(rule) },
                    moreActions = ruleMenuActions(rule),
                    dragHandle = {
                        Icon(
                            painter = painterResource(R.drawable.ic_drag_handle),
                            contentDescription = stringResource(R.string.sort),
                            tint = palette.settings.secondaryText,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(22.dp)
                                .draggableHandle(onDragStopped = { onReorder(orderedRules) })
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun TxtTocRuleItemRow(
    name: String,
    example: String?,
    enabled: Boolean,
    isSelected: Boolean,
    isSelectMode: Boolean,
    palette: AppManagementPalette,
    onToggleSelect: () -> Unit,
    onToggleEnable: (Boolean) -> Unit,
    onEdit: () -> Unit,
    moreActions: List<AppManagementMenuAction>,
    dragHandle: @Composable () -> Unit
) {
    AppManagementListRow(
        title = name,
        subtitle = example,
        palette = palette,
        selected = isSelected,
        selectionVisible = isSelectMode,
        animatedSelection = true,
        reserveSelectionSlot = false,
        onToggleSelection = onToggleSelect,
        switchChecked = enabled,
        onSwitchChange = onToggleEnable,
        titleMaxLines = 1,
        subtitleMaxLines = 1,
        minHeight = 56.dp,
        drawPanelImage = false,
        onClick = {
            if (isSelectMode) onToggleSelect() else onEdit()
        },
        onLongClick = onToggleSelect,
        onEdit = onEdit,
        moreActions = moreActions,
        leadingContent = dragHandle
    )
}
