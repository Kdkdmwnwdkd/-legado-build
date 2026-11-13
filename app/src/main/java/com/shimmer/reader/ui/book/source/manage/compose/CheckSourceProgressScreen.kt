package com.shimmer.reader.ui.book.source.manage.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shimmer.reader.R
import com.shimmer.reader.constant.BookSourceType
import com.shimmer.reader.help.config.AppConfig
import com.shimmer.reader.lib.theme.backgroundColor
import com.shimmer.reader.lib.theme.primaryColor
import com.shimmer.reader.lib.theme.titleTextColor
import com.shimmer.reader.model.CheckItem
import com.shimmer.reader.model.CheckItemStatus
import com.shimmer.reader.model.CheckRun
import com.shimmer.reader.model.CheckRunStatus
import com.shimmer.reader.model.CheckStage
import com.shimmer.reader.ui.widget.compose.AppManagementPalette
import com.shimmer.reader.ui.widget.compose.LegadoComposeTheme
import com.shimmer.reader.ui.widget.compose.LegadoMiuixActionButton
import com.shimmer.reader.ui.widget.compose.LegadoMiuixCard
import com.shimmer.reader.ui.widget.compose.rememberAppManagementPalette
import kotlinx.coroutines.delay

private enum class CheckFilter {
    ALL,
    PASSED,
    FAILED,
    PENDING,
}

/** 通过用绿、失败用红，与阅读页里「这本书可读/不可读」的直觉保持一致。 */
private val PassedColor = Color(0xFF2E9E5B)
private val FailedColor = Color(0xFFD9534F)

@Composable
fun CheckSourceProgressScreen(
    run: CheckRun,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onOpenManage: () -> Unit,
) {
    LegadoComposeTheme {
        val palette = rememberAppManagementPalette()
        val context = LocalContext.current
        var filter by remember { mutableStateOf(CheckFilter.ALL) }
        var now by remember { mutableStateOf(System.currentTimeMillis()) }

        // 用时每秒走一格。只在跑的时候起定时器，跑完就停，页面不会一直在重组
        LaunchedEffect(run.status) {
            if (run.status != CheckRunStatus.RUNNING) return@LaunchedEffect
            while (true) {
                now = System.currentTimeMillis()
                delay(1000)
            }
        }

        val stats = remember(run) { CheckStats.of(run) }
        val shown = remember(run, filter) {
            when (filter) {
                CheckFilter.ALL -> run.items
                CheckFilter.PASSED -> run.items.filter { it.status == CheckItemStatus.PASSED }
                CheckFilter.FAILED -> run.items.filter { it.status == CheckItemStatus.FAILED }
                CheckFilter.PENDING -> run.items.filter {
                    it.status == CheckItemStatus.WAITING ||
                        it.status == CheckItemStatus.RUNNING ||
                        it.status == CheckItemStatus.CANCELLED
                }
            }
        }

        val topBarColor = if (AppConfig.immersiveManageBar) {
            context.backgroundColor
        } else {
            context.primaryColor
        }
        val topBarContent = Color(context.titleTextColor)

        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(topBarColor))
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(48.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.back),
                    tint = topBarContent,
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(onClick = onBack)
                        .padding(12.dp)
                )
                Text(
                    text = stringResource(R.string.check_source_progress_title),
                    color = topBarContent,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                if (run.status == CheckRunStatus.RUNNING) {
                    Text(
                        text = stringResource(R.string.cancel),
                        color = topBarContent,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .clickable(onClick = onCancel)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    ProgressCard(
                        run = run,
                        stats = stats,
                        now = now,
                        palette = palette
                    )
                }
                if (stats.total > 0) {
                    item {
                        FilterRow(
                            filter = filter,
                            stats = stats,
                            palette = palette,
                            onSelect = { filter = it }
                        )
                    }
                }
                if (shown.isEmpty()) {
                    item {
                        Text(
                            text = if (run.status == CheckRunStatus.IDLE) {
                                stringResource(R.string.check_source_progress_idle_hint)
                            } else {
                                stringResource(R.string.check_source_progress_empty)
                            },
                            color = palette.settings.secondaryText,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 24.dp)
                        )
                    }
                } else {
                    items(shown, key = { it.origin }) { item ->
                        SourceItemRow(
                            item = item,
                            palette = palette
                        )
                    }
                }
                if (run.settled) {
                    item {
                        Column(Modifier.padding(top = 6.dp)) {
                            if (run.autoDisabled > 0) {
                                Text(
                                    text = stringResource(
                                        R.string.check_source_progress_auto_disabled,
                                        run.autoDisabled
                                    ),
                                    color = palette.settings.secondaryText,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.check_source_progress_back_to_manage),
                                palette = palette.miuix,
                                onClick = onOpenManage,
                                primary = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(
    run: CheckRun,
    stats: CheckStats,
    now: Long,
    palette: AppManagementPalette,
) {
    val running = run.status == CheckRunStatus.RUNNING
    LegadoMiuixCard(
        modifier = Modifier.fillMaxWidth(),
        color = palette.miuix.surfaceVariant,
        contentColor = palette.miuix.primaryText,
        cornerRadius = palette.miuix.panelRadius ?: 12.dp,
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = when (run.status) {
                CheckRunStatus.RUNNING -> stringResource(R.string.check_source_progress_running)
                CheckRunStatus.FINISHED -> stringResource(R.string.check_source_progress_finished)
                CheckRunStatus.CANCELLED -> stringResource(R.string.check_source_progress_cancelled)
                CheckRunStatus.IDLE -> stringResource(R.string.check_source_progress_idle)
            },
            color = palette.miuix.primaryText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    R.string.check_source_progress_count,
                    stats.done,
                    stats.total
                ),
                color = palette.miuix.secondaryText,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(
                    R.string.check_source_progress_elapsed,
                    formatDuration(elapsedOf(run, now))
                ),
                color = palette.miuix.secondaryText,
                fontSize = 13.sp
            )
        }
        ProgressBar(
            fraction = if (running) stats.progress else if (stats.total > 0) 1f else 0f,
            color = if (run.status == CheckRunStatus.CANCELLED) {
                palette.miuix.secondaryText
            } else {
                palette.miuix.accent
            },
            track = palette.settings.divider,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatCell(
                label = stringResource(R.string.check_source_stat_passed),
                value = stats.passed,
                color = PassedColor,
                palette = palette,
                modifier = Modifier.weight(1f)
            )
            StatCell(
                label = stringResource(R.string.check_source_stat_failed),
                value = stats.failed,
                color = FailedColor,
                palette = palette,
                modifier = Modifier.weight(1f)
            )
            StatCell(
                label = stringResource(R.string.check_source_stat_waiting),
                value = stats.remaining,
                color = palette.miuix.secondaryText,
                palette = palette,
                modifier = Modifier.weight(1f)
            )
            if (stats.cancelled > 0) {
                StatCell(
                    label = stringResource(R.string.check_source_stat_cancelled),
                    value = stats.cancelled,
                    color = palette.miuix.secondaryText,
                    palette = palette,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StatCell(
    label: String,
    value: Int,
    color: Color,
    palette: AppManagementPalette,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            color = color,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = label,
            color = palette.miuix.secondaryText,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun ProgressBar(
    fraction: Float,
    color: Color,
    track: Color,
    modifier: Modifier = Modifier,
) {
    val safe = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(6.dp)
            .background(track, RoundedCornerShape(3.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(safe)
                .height(6.dp)
                .background(color, RoundedCornerShape(3.dp))
        )
    }
}

@Composable
private fun FilterRow(
    filter: CheckFilter,
    stats: CheckStats,
    palette: AppManagementPalette,
    onSelect: (CheckFilter) -> Unit,
) {
    val entries = listOf(
        Triple(CheckFilter.ALL, stringResource(R.string.check_source_filter_all), stats.total),
        Triple(CheckFilter.PASSED, stringResource(R.string.check_source_stat_passed), stats.passed),
        Triple(CheckFilter.FAILED, stringResource(R.string.check_source_stat_failed), stats.failed),
        Triple(CheckFilter.PENDING, stringResource(R.string.check_source_stat_waiting), stats.remaining),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        entries.forEach { (value, label, count) ->
            val selected = filter == value
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (selected) palette.miuix.accent else palette.miuix.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect(value) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$label $count",
                    color = if (selected) palette.miuix.onAccent else palette.miuix.primaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SourceItemRow(
    item: CheckItem,
    palette: AppManagementPalette,
) {
    LegadoMiuixCard(
        modifier = Modifier.fillMaxWidth(),
        color = palette.settings.page,
        contentColor = palette.miuix.primaryText,
        cornerRadius = 10.dp,
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.name.ifBlank { item.origin },
                color = palette.miuix.primaryText,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            val typeLabel = sourceTypeLabel(item.type)
            if (typeLabel.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = typeLabel,
                    color = palette.miuix.secondaryText,
                    fontSize = 11.sp
                )
            }
        }
        Text(
            text = statusLine(item),
            color = when (item.status) {
                CheckItemStatus.PASSED -> PassedColor
                CheckItemStatus.FAILED -> FailedColor
                else -> palette.miuix.secondaryText
            },
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun statusLine(item: CheckItem): String {
    return when (item.status) {
        CheckItemStatus.WAITING -> stringResource(R.string.check_source_item_waiting)
        CheckItemStatus.RUNNING -> stringResource(
            R.string.check_source_item_running,
            stageLabel(item.stage)
        )
        CheckItemStatus.PASSED -> stringResource(
            R.string.check_source_item_passed,
            formatDuration(item.durationMs)
        )
        CheckItemStatus.FAILED -> item.reason.ifBlank {
            stringResource(R.string.check_source_stat_failed)
        }
        CheckItemStatus.CANCELLED -> stringResource(R.string.check_source_stat_cancelled)
    }
}

@Composable
private fun stageLabel(stage: CheckStage): String {
    return when (stage) {
        CheckStage.PREPARE -> stringResource(R.string.check_source_stage_prepare)
        CheckStage.DOMAIN -> stringResource(R.string.domain)
        CheckStage.SEARCH -> stringResource(R.string.search)
        CheckStage.DISCOVERY -> stringResource(R.string.discovery)
        CheckStage.INFO -> stringResource(R.string.source_tab_info)
        CheckStage.TOC -> stringResource(R.string.chapter_list)
        CheckStage.CONTENT -> stringResource(R.string.main_body)
    }
}

@Composable
private fun sourceTypeLabel(type: Int): String {
    return when (type) {
        BookSourceType.default -> stringResource(R.string.check_source_type_text)
        BookSourceType.audio -> stringResource(R.string.check_source_type_audio)
        BookSourceType.image -> stringResource(R.string.check_source_type_image)
        BookSourceType.file -> stringResource(R.string.check_source_type_file)
        BookSourceType.video -> stringResource(R.string.check_source_type_video)
        else -> ""
    }
}

/** 卡片和筛选条共用的计数，算一次给两处用，避免每次重组都扫一遍列表。 */
internal data class CheckStats(
    val total: Int,
    val done: Int,
    val passed: Int,
    val failed: Int,
    val cancelled: Int,
    val remaining: Int,
    val progress: Float,
) {
    companion object {
        fun of(run: CheckRun): CheckStats {
            var passed = 0
            var failed = 0
            var cancelled = 0
            run.items.forEach {
                when (it.status) {
                    CheckItemStatus.PASSED -> passed++
                    CheckItemStatus.FAILED -> failed++
                    CheckItemStatus.CANCELLED -> cancelled++
                    else -> Unit
                }
            }
            val total = run.items.size
            val done = passed + failed + cancelled
            return CheckStats(
                total = total,
                done = done,
                passed = passed,
                failed = failed,
                cancelled = cancelled,
                remaining = (total - done).coerceAtLeast(0),
                progress = if (total <= 0) 0f else done.toFloat() / total
            )
        }
    }
}

private fun elapsedOf(run: CheckRun, now: Long): Long {
    if (run.startedAt <= 0L) return 0L
    val end = if (run.endedAt > 0L) run.endedAt else now
    return (end - run.startedAt).coerceAtLeast(0L)
}

internal fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
