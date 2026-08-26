package io.legado.app.ui.book.bookmark

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Bookmark
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.compose.rememberAppSettingPalette

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AllBookmarkScreen(
    bookmarks: List<Bookmark>,
    onItemClick: (Bookmark, Int) -> Unit,
    onItemLongClick: (Bookmark, Int) -> Unit
) {
    val context = LocalContext.current
    val palette = rememberAppSettingPalette()
    val headerBg = Color(context.backgroundColor)
    val headerTextColor = Color(context.accentColor)

    val grouped = remember(bookmarks) {
        bookmarks.groupBy { "${it.bookName}\u0000${it.bookAuthor}" }
    }
    val indexMap = remember(bookmarks) {
        bookmarks.withIndex().associate { (index, item) -> item.time to index }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.page)
            .windowInsetsPadding(WindowInsets.navigationBars),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        grouped.forEach { (_, items) ->
            val headerText = items.firstOrNull()
                ?.let { "${it.bookName}(${it.bookAuthor})" }
                .orEmpty()
            val groupKey = items.firstOrNull()
                ?.let { "${it.bookName}\u0000${it.bookAuthor}" }
                .orEmpty()

            stickyHeader(key = "header_$groupKey") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(headerBg)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = headerText,
                        color = headerTextColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            itemsIndexed(items, key = { _, item ->
                "${item.time}_${item.bookName}_${item.chapterIndex}_${item.chapterPos}"
            }) { _, bookmark ->
                val globalIndex = indexMap[bookmark.time] ?: 0
                BookmarkItemRow(
                    bookmark = bookmark,
                    onClick = { onItemClick(bookmark, globalIndex) },
                    onLongClick = { onItemLongClick(bookmark, globalIndex) }
                )
            }
        }
    }
}

@Composable
private fun BookmarkItemRow(
    bookmark: Bookmark,
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
        Text(
            text = bookmark.chapterName,
            color = palette.primaryText,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (bookmark.bookText.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = bookmark.bookText,
                color = palette.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (bookmark.content.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = bookmark.content,
                color = palette.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
