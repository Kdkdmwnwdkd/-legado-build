package com.shimmer.reader.ui.main.bookshelf.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.shimmer.reader.lib.theme.accentColor
import com.shimmer.reader.lib.theme.secondaryTextColor
import com.shimmer.reader.lib.theme.titleTextColor
import com.shimmer.reader.lib.theme.uiTypeface
import kotlin.math.roundToInt

/**
 * 书架顶部要显示的东西。全部来自本地阅读记录，不联网。
 *
 * 今日/本周/连续天数这些统计只保留在数据层：书架已经不展示它们了，
 * 「我的 → 阅读记录」里有同一份数据且更完整。这里继续保留字段，
 * 是为了不动 [ShelfBriefLoader] 的取数逻辑。
 *
 * @param weekMinutes 最近七天每天读了多少分钟，下标 6 是今天
 * @param weekLabels 与 [weekMinutes] 一一对应的星期缩写
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
    /** 今天的目标完成了几成，超过 1 就按满格算。 */
    val goalRatio: Float
        get() = if (goalMinutes <= 0) 0f else (todayMinutes.toFloat() / goalMinutes).coerceIn(0f, 1f)

    val hasRecent: Boolean get() = !recentTitle.isNullOrBlank()
}

/**
 * 书架顶部的一条「继续阅读」。
 *
 * 以前这里是一张卡片，上面压着今日时长、连续天数、目标进度和七天柱状图。
 * 那些数字在「我的 → 阅读记录」里都有，而且更全，书架上再放一遍等于同一份
 * 数据看两遍，所以卡片撤掉了，只留继续阅读这一条——它是指向"上次看到哪儿"
 * 的入口，阅读记录页没有。
 */
@Composable
fun BookshelfBriefCard(
    brief: ShelfBrief,
    modifier: Modifier = Modifier,
    onOpenRecent: () -> Unit
) {
    if (!brief.hasRecent) return

    val context = LocalContext.current
    val accent = Color(context.accentColor)
    val titleColor = Color(context.titleTextColor)
    val subColor = Color(context.secondaryTextColor)
    val bodyFamily = FontFamily(context.uiTypeface())

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenRecent)
            .padding(horizontal = 18.dp, vertical = 10.dp),
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

/** 把毫秒换算成分钟，小于一分钟按一分钟记，避免刚读几句就显示 0。 */
fun readMillisToMinutes(millis: Long): Int {
    if (millis <= 0L) return 0
    val minutes = (millis / 60_000f).roundToInt()
    return minutes.coerceAtLeast(1)
}
