package com.shimmer.reader.ui.book.bookmark

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shimmer.reader.R
import com.shimmer.reader.data.entities.Bookmark
import com.shimmer.reader.lib.theme.accentColor
import com.shimmer.reader.lib.theme.backgroundColor
import com.shimmer.reader.ui.widget.compose.rememberAppSettingPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AllBookmarkScreen(
    bookmarks: List<Bookmark>,
    query: String,
    onItemClick: (Bookmark, Int) -> Unit,
    onItemLongClick: (Bookmark, Int) -> Unit
) {
    val context = LocalContext.current
    val palette = rememberAppSettingPalette()
    val headerBg = Color(context.backgroundColor)
    val headerTextColor = Color(context.accentColor)
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    // 书名、作者、章节名、摘录正文、备注都参与匹配，读书时记得的关键词不一定在哪一段
    val filtered = remember(bookmarks, query) { filterBookmarks(bookmarks, query) }
    val grouped = remember(filtered) {
        filtered.groupBy { "${it.bookName}\u0000${it.bookAuthor}" }
    }
    val indexMap = remember(filtered) {
        filtered.withIndex().associate { (index, item) -> item.time to index }
    }

    if (filtered.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.page),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(
                    if (bookmarks.isEmpty()) R.string.bookmark_empty else R.string.bookmark_search_empty
                ),
                color = palette.secondaryText,
                fontSize = 14.sp,
                modifier = Modifier.padding(24.dp)
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.page)
            .windowInsetsPadding(WindowInsets.navigationBars),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        grouped.forEach { (_, items) ->
            val first = items.first()
            val groupKey = "${first.bookName}\u0000${first.bookAuthor}"
            stickyHeader(key = "header_$groupKey") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(headerBg)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = bookLabel(first),
                        color = headerTextColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${items.size}",
                        color = headerTextColor,
                        fontSize = 12.sp
                    )
                }
            }

            itemsIndexed(items, key = { _, item ->
                "${item.time}_${item.bookName}_${item.chapterIndex}_${item.chapterPos}"
            }) { _, bookmark ->
                val globalIndex = indexMap[bookmark.time] ?: 0
                BookmarkItemRow(
                    bookmark = bookmark,
                    timeText = timeFormat.format(Date(bookmark.time)),
                    onClick = { onItemClick(bookmark, globalIndex) },
                    onLongClick = { onItemLongClick(bookmark, globalIndex) }
                )
            }
        }
    }
}

/** 列表显示与导出共用同一套匹配规则，免得看到的和导出的对不上 */
internal fun filterBookmarks(bookmarks: List<Bookmark>, query: String): List<Bookmark> {
    val key = query.trim()
    if (key.isEmpty()) return bookmarks
    return bookmarks.filter {
        it.bookName.contains(key, true) ||
            it.bookAuthor.contains(key, true) ||
            it.chapterName.contains(key, true) ||
            it.bookText.contains(key, true) ||
            it.content.contains(key, true)
    }
}

private fun bookLabel(bookmark: Bookmark): String {
    return if (bookmark.bookAuthor.isBlank()) {
        bookmark.bookName
    } else {
        "${bookmark.bookName}（${bookmark.bookAuthor}）"
    }
}

@Composable
private fun BookmarkItemRow(
    bookmark: Bookmark,
    timeText: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val palette = rememberAppSettingPalette()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(palette.row))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = bookmark.chapterName,
                color = palette.primaryText,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = timeText,
                color = palette.secondaryText,
                fontSize = 11.sp
            )
        }
        if (bookmark.bookText.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = bookmark.bookText,
                color = palette.primaryText,
                fontSize = 13.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (bookmark.content.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = bookmark.content,
                color = palette.secondaryText,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
