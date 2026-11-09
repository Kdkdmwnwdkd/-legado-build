package com.shimmer.reader.ui.main.bookshelf.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shimmer.reader.R
import com.shimmer.reader.lib.theme.UiCorner
import com.shimmer.reader.lib.theme.accentColor
import com.shimmer.reader.lib.theme.composePanelRadius
import com.shimmer.reader.lib.theme.rememberThemeUiPalette
import com.shimmer.reader.lib.theme.secondaryTextColor
import com.shimmer.reader.lib.theme.titleTextColor
import com.shimmer.reader.lib.theme.titleTypeface
import com.shimmer.reader.lib.theme.uiTypeface
import kotlin.math.roundToInt

/**
 * 书架顶部那张小卡片要显示的东西。全部来自本地阅读记录，不联网。
 *
 * @param weekMinutes 最近七天每天读了多少分钟，下标 6 是今天
 * @param weekLabels 与 [weekMinutes] 一一对应的星期缩写，用于柱状图底下那行字
 */
@Immutable
data class ShelfBrief(
    val todayMinutes: Int,
    val goalMinutes: Int,
    val streakDays: Int,
    val weekMinutes: List<Int>,
    val weekLabels: List<String>,
    val recentTitle: String?,
    val recentProgress: String?,
    val recentBookUrl: String? = null
) {
    /** 今天的目标完成了几成，用来画进度条，超过 1 就按满格算。 */
    val goalRatio: Float
        get() = if (goalMinutes <= 0) 0f else (todayMinutes.toFloat() / goalMinutes).coerceIn(0f, 1f)

    val hasRecent: Boolean get() = !recentTitle.isNullOrBlank()
}

@Composable
fun BookshelfBriefCard(
    brief: ShelfBrief,
    modifier: Modifier = Modifier,
    onOpenRecent: () -> Unit,
    onOpenRecord: () -> Unit
) {
    val context = LocalContext.current
    val palette = rememberThemeUiPalette()
    val cardColor = Color(palette.cardColor)
    val accent = Color(context.accentColor)
    val titleColor = Color(context.titleTextColor)
    val subColor = Color(context.secondaryTextColor)
    val titleFamily = FontFamily(context.titleTypeface())
    val bodyFamily = FontFamily(context.uiTypeface())
    val radius = context.composePanelRadius()
    val borderColor = UiCorner.panelBorderColor(context)?.let { Color(it) }
        ?: accent.copy(alpha = 0.12f)
    val todayText = when {
        brief.todayMinutes <= 0 -> stringResource(R.string.shelf_brief_none_today)
        brief.todayMinutes < 60 -> stringResource(R.string.shelf_brief_minutes, brief.todayMinutes)
        else -> stringResource(
            R.string.shelf_brief_hours,
            brief.todayMinutes / 60,
            brief.todayMinutes % 60
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .background(cardColor, RoundedCornerShape(radius))
            .border(1.dp, borderColor, RoundedCornerShape(radius))
            .clickable(onClick = onOpenRecord)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.shelf_brief_today),
                    color = subColor,
                    fontSize = 12.sp,
                    fontFamily = bodyFamily
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = todayText,
                    color = titleColor,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = titleFamily
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (brief.streakDays > 0) {
                        stringResource(R.string.shelf_brief_streak, brief.streakDays)
                    } else {
                        stringResource(R.string.shelf_brief_streak_none)
                    },
                    color = if (brief.streakDays > 0) accent else subColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = bodyFamily
                )
                if (brief.goalMinutes > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.shelf_brief_goal, brief.goalMinutes),
                        color = subColor,
                        fontSize = 11.sp,
                        fontFamily = bodyFamily
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 进度条：底槽与填充都用主题色，避免在深色主题下发白。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(accent.copy(alpha = 0.16f), RoundedCornerShape(2.dp))
        ) {
            val fillFraction = brief.goalRatio
            if (fillFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fillFraction)
                        .height(4.dp)
                        .background(accent, RoundedCornerShape(2.dp))
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        WeekBars(
            minutes = brief.weekMinutes,
            labels = brief.weekLabels,
            accent = accent,
            subColor = subColor,
            family = bodyFamily
        )

        if (brief.hasRecent) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenRecent),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.shelf_brief_continue, brief.recentTitle.orEmpty()),
                        color = titleColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = bodyFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val progress = brief.recentProgress
                    if (!progress.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = progress,
                            color = subColor,
                            fontSize = 11.sp,
                            fontFamily = bodyFamily,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "›",
                    color = accent,
                    fontSize = 20.sp,
                    fontFamily = bodyFamily
                )
            }
        }
    }
}

/**
 * 七天柱状图。高度按本周最大值归一，今天那根用强调色，其余用淡色。
 */
@Composable
private fun WeekBars(
    minutes: List<Int>,
    labels: List<String>,
    accent: Color,
    subColor: Color,
    family: FontFamily
) {
    if (minutes.isEmpty()) return
    val peak = minutes.max().coerceAtLeast(1)
    val barArea = 22.dp
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        minutes.forEachIndexed { index, value ->
            val isToday = index == minutes.lastIndex
            val ratio = (value.toFloat() / peak).coerceIn(0f, 1f)
            // 有记录但很短的那天也留一点高度，否则看着像是完全没读。
            val height = if (value <= 0) 3.dp else (barArea * (0.22f + 0.78f * ratio))
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barArea),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(height)
                            .background(
                                color = when {
                                    value <= 0 -> subColor.copy(alpha = 0.18f)
                                    isToday -> accent
                                    else -> accent.copy(alpha = 0.45f)
                                },
                                shape = RoundedCornerShape(3.dp)
                            )
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = labels.getOrElse(index) { "" },
                    color = if (isToday) accent else subColor,
                    fontSize = 10.sp,
                    fontWeight = if (isToday) FontWeight.Medium else FontWeight.Normal,
                    fontFamily = family
                )
            }
        }
    }
}

/** 把毫秒换算成分钟，小于一分钟按一分钟记，避免刚读几句就显示 0。 */
fun readMillisToMinutes(millis: Long): Int {
    if (millis <= 0L) return 0
    val minutes = (millis / 60_000f).roundToInt()
    return minutes.coerceAtLeast(1)
}
