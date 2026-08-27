package io.legado.app.ui.widget.compose

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.compose.DisposableCompositionStrategy

/**
 * 书源查重结果对话框：按组展示重复的书源条目，每行可单独勾选。
 * Header 行（组标题）不可勾选；Item 行可勾选。
 * 返回值 BooleanArray 与扁平化行列表对齐，调用方过滤 header 对应的位置即可。
 */
class ComposeDuplicateBookSourcesDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    private var onPositive: ((BooleanArray) -> Unit)? = null
    private var onDismissAction: (() -> Unit)? = null

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissAction?.invoke()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        return ComposeView(requireContext()).apply {
            DisposableCompositionStrategy.installAndTrack(this, viewLifecycleOwner)
            setContent {
                DismissWhenCallbackMissing(
                    missing = onPositive == null,
                    dismiss = ::dismissAllowingStateLoss
                )
                val style = rememberAppDialogStyle()
                val rowLabels = remember {
                    args.getStringArrayList(ARG_ROW_LABELS)?.toList().orEmpty()
                }
                val rowSubs = remember {
                    args.getStringArrayList(ARG_ROW_SUBS)?.toList().orEmpty()
                }
                val headerPositions = remember {
                    args.getIntegerArrayList(ARG_HEADER_POSITIONS)?.toSet().orEmpty()
                }
                val initialChecked = remember(rowLabels) {
                    val arr = args.getBooleanArray(ARG_CHECKED) ?: booleanArrayOf()
                    List(rowLabels.size) { idx ->
                        if (idx in headerPositions) false else arr.getOrNull(idx) ?: false
                    }
                }
                val saveCheckedState = rowLabels.size <= MAX_SAVEABLE_MULTI_CHOICE_ITEMS
                val saveableChecked = if (saveCheckedState) {
                    rememberSaveable(rowLabels) { mutableStateOf(initialChecked) }
                } else {
                    null
                }
                val localChecked = if (saveCheckedState) {
                    null
                } else {
                    remember(rowLabels) {
                        mutableStateListOf<Boolean>().apply { addAll(initialChecked) }
                    }
                }
                val positiveTextTemplate = args.getString(ARG_POSITIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.ok) }
                val negativeText = args.getString(ARG_NEGATIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.cancel) }
                val canSubmit = onPositive != null

                @Composable
                fun currentCheckedList(): List<Boolean> {
                    return saveableChecked?.value ?: localChecked ?: initialChecked
                }

                val checkedCount = currentCheckedList().filterIndexed { i, v ->
                    i !in headerPositions && v
                }.size

                val positiveText = if ("%d" in positiveTextTemplate) {
                    String.format(positiveTextTemplate, checkedCount)
                } else {
                    "$positiveText ($checkedCount)"
                }

                AppDialogFrame(
                    title = args.getString(ARG_TITLE).orEmpty(),
                    message = args.getString(ARG_MESSAGE),
                    scrollContent = false,
                    content = {
                        val palette = style.toMiuixPalette()
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            itemsIndexed(rowLabels) { index, label ->
                                val isHeader = index in headerPositions
                                if (isHeader) {
                                    GroupHeader(
                                        title = label,
                                        subTitle = rowSubs.getOrNull(index).takeUnless { it.isNullOrEmpty() }
                                    )
                                } else {
                                    val checked = currentCheckedList().getOrNull(index) ?: false
                                    LegadoMiuixChoiceRow(
                                        text = label,
                                        description = rowSubs.getOrNull(index).takeUnless { it.isNullOrEmpty() },
                                        selected = checked,
                                        palette = palette,
                                        onClick = {
                                            if (index in rowLabels.indices) {
                                                val next = !checked
                                                val state = saveableChecked
                                                if (state != null) {
                                                    state.value = state.value.toMutableList().apply {
                                                        this[index] = next
                                                    }
                                                } else {
                                                    localChecked?.let { list ->
                                                        if (index in list.indices) {
                                                            list[index] = next
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        minHeight = 52.dp
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        val palette = style.toMiuixPalette()
                        LegadoMiuixActionButton(
                            text = negativeText,
                            palette = palette,
                            onClick = { dismissAllowingStateLoss() },
                            cornerRadius = style.actionRadius
                        )
                        if (canSubmit) {
                            Spacer(modifier = Modifier.width(8.dp))
                            LegadoMiuixActionButton(
                                text = positiveText,
                                palette = palette,
                                onClick = {
                                    val result = BooleanArray(rowLabels.size) { index ->
                                        if (index in headerPositions) false
                                        else currentCheckedList().getOrNull(index) ?: false
                                    }
                                    dismissAllowingStateLoss()
                                    onPositive?.invoke(result)
                                },
                                primary = checkedCount > 0,
                                cornerRadius = style.actionRadius,
                                danger = true
                            )
                        }
                    }
                )
            }
        }
    }

    companion object {
        fun create(
            title: String,
            rowLabels: List<String>,
            rowSubs: List<String>,
            headerPositions: Set<Int>,
            checked: BooleanArray,
            message: String? = null,
            positiveText: String,
            negativeText: String,
            onDismissAction: (() -> Unit)? = null,
            onPositive: ((BooleanArray) -> Unit)? = null
        ): ComposeDuplicateBookSourcesDialog {
            return ComposeDuplicateBookSourcesDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putStringArrayList(ARG_ROW_LABELS, ArrayList(rowLabels))
                    putStringArrayList(ARG_ROW_SUBS, ArrayList(rowSubs))
                    putIntegerArrayList(ARG_HEADER_POSITIONS, ArrayList(headerPositions.toList()))
                    putBooleanArray(ARG_CHECKED, checked.copyOf())
                    putString(ARG_MESSAGE, message)
                    putString(ARG_POSITIVE_TEXT, positiveText)
                    putString(ARG_NEGATIVE_TEXT, negativeText)
                }
                this.onPositive = onPositive
                this.onDismissAction = onDismissAction
            }
        }

        private const val ARG_TITLE = "title"
        private const val ARG_ROW_LABELS = "rowLabels"
        private const val ARG_ROW_SUBS = "rowSubs"
        private const val ARG_HEADER_POSITIONS = "headerPositions"
        private const val ARG_CHECKED = "checked"
        private const val ARG_MESSAGE = "message"
        private const val ARG_POSITIVE_TEXT = "positiveText"
        private const val ARG_NEGATIVE_TEXT = "negativeText"

        private const val MAX_SAVEABLE_MULTI_CHOICE_ITEMS = 500
    }
}

/**
 * 组标题：卡片背景 + 加粗序号/重复Key + 重复数量副文本
 */
@Composable
private fun GroupHeader(
    title: String,
    subTitle: String?
) {
    Column(modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp, bottom = 2.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(
                    androidx.compose.material3.MaterialTheme.colorScheme
                        .surfaceVariant.copy(alpha = 0.5f)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subTitle.isNullOrBlank()) {
                    Text(
                        text = subTitle,
                        fontSize = 12.sp,
                        color = androidx.compose.material3.MaterialTheme.colorScheme
                            .onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
