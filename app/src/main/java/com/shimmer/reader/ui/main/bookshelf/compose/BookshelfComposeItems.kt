package com.shimmer.reader.ui.main.bookshelf.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.shimmer.reader.R
import com.shimmer.reader.data.dao.BookShelfDisplay
import com.shimmer.reader.data.entities.BookGroup
import com.shimmer.reader.help.book.BookTagHelper
import com.shimmer.reader.help.config.AppConfig
import com.shimmer.reader.lib.theme.accentColor
import com.shimmer.reader.lib.theme.rememberThemeUiPalette
import com.shimmer.reader.lib.theme.titleTypeface
import com.shimmer.reader.lib.theme.titleTextColor
import com.shimmer.reader.utils.toTimeAgo

sealed interface BookshelfItemUi {
    val key: String
    val contentType: String
}

data class BookshelfFolderItemUi(
    val group: BookGroup
) : BookshelfItemUi {
    override val key: String = "folder:${group.groupId}"
    override val contentType: String = "folder"
}

data class BookshelfBookItemUi(
    val display: BookShelfDisplay,
    val isUpdating: Boolean,
    val unreadCount: Int,
    val hasNewChapter: Boolean,
    val tags: List<String>,
    val lastUpdateText: String?,
    /**
     * 这本书挂的书源已停用或被删除。
     *
     * 只影响书架上的显示和点击提示，书本身、阅读进度、缓存章节都不动：
     * 源关掉之后已经缓存过的章节还是能读的。
     */
    val sourceDisabled: Boolean = false
) : BookshelfItemUi {
    override val key: String = "book:${display.bookUrl}"
    override val contentType: String = "book"
}

fun buildBookshelfItems(
    groups: List<BookGroup>,
    books: List<BookShelfDisplay>,
    isRootGroup: Boolean,
    groupId: Long,
    isUpdating: (String) -> Boolean,
    disabledOrigins: Set<String> = emptySet()
): List<BookshelfItemUi> {
    val configuredTags = AppConfig.bookshelfGroupTags[groupId].orEmpty()
    val hiddenTags = AppConfig.bookshelfHiddenTags[groupId].orEmpty()
    val bookItems = books.map { book ->
        BookshelfBookItemUi(
            display = book,
            isUpdating = !book.isLocal && isUpdating(book.bookUrl),
            unreadCount = book.getUnreadChapterNum(),
            hasNewChapter = book.lastCheckCount > 0,
            tags = book.displayUserTags(configuredTags, hiddenTags)
                .take(4),
            lastUpdateText = if (AppConfig.showLastUpdateTime && !book.isLocal) {
                book.latestChapterTime.toTimeAgo()
            } else {
                null
            },
            sourceDisabled = book.origin in disabledOrigins
        )
    }
    if (!isRootGroup) {
        return bookItems
    }
    return groups.map(::BookshelfFolderItemUi) + bookItems
}

/**
 * 用最新的「不可用书源」集合给已有条目补标记。
 *
 * 书架会先把上次的条目快照铺到屏幕上再等数据库回填，快照里没有源的状态，
 * 直接用会有一瞬间的角标缺失。恢复快照时拿当前集合重算一遍，两者就能对上。
 */
fun markSourceDisabled(
    items: List<BookshelfItemUi>,
    disabledOrigins: Set<String>
): List<BookshelfItemUi> {
    if (items.isEmpty()) return items
    var changed = false
    val updated = items.map { item ->
        if (item is BookshelfBookItemUi) {
            val disabled = item.display.origin in disabledOrigins
            if (disabled != item.sourceDisabled) {
                changed = true
                item.copy(sourceDisabled = disabled)
            } else {
                item
            }
        } else {
            item
        }
    }
    return if (changed) updated else items
}

private fun BookShelfDisplay.displayUserTags(
    configuredTags: List<String>,
    hiddenTags: Set<String>
): List<String> {
    val userTags = BookTagHelper.parse(customTag)
    val visibleConfiguredTags = configuredTags
        .filterNot { configured -> hiddenTags.any { it.equals(configured, ignoreCase = true) } }
    val candidateTags = visibleConfiguredTags.ifEmpty { userTags }
    return candidateTags.filter { candidate ->
        userTags.any { it.equals(candidate, ignoreCase = true) }
    }.distinctBy { it.lowercase() }
}

fun updateBookshelfItemUpdating(
    items: List<BookshelfItemUi>,
    bookUrl: String,
    isUpdating: (String) -> Boolean
): List<BookshelfItemUi> {
    var changed = false
    val updatedItems = items.map { item ->
        if (item is BookshelfBookItemUi && item.display.bookUrl == bookUrl) {
            val nextUpdating = !item.display.isLocal && isUpdating(bookUrl)
            if (nextUpdating != item.isUpdating) {
                changed = true
                item.copy(isUpdating = nextUpdating)
            } else {
                item
            }
        } else {
            item
        }
    }
    return if (changed) updatedItems else items
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookshelfGridItem(
    item: BookshelfItemUi,
    modifier: Modifier = Modifier,
    fragment: Fragment? = null,
    lifecycle: Lifecycle? = null,
    onClick: (BookshelfItemUi) -> Unit,
    onLongClick: (BookshelfItemUi) -> Unit
) {
    val context = LocalContext.current
    val themeSignature = rememberThemeUiPalette().signature
    val showBookName = AppConfig.showBookname
    val titleFontFamily = remember(context, themeSignature) {
        FontFamily(context.titleTypeface())
    }
    val titleColorArgb = context.titleTextColor
    val titleColor = remember(context, themeSignature, titleColorArgb) {
        Color(titleColorArgb)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onClick(item) },
                onLongClick = { onLongClick(item) }
            )
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.TopEnd
        ) {
            BookshelfCover(
                item = item,
                modifier = Modifier.fillMaxWidth(),
                fragment = fragment,
                lifecycle = lifecycle
            )
            if (item is BookshelfBookItemUi) {
                BookshelfStatusBadge(item)
            }
            BookshelfSourceDisabledBadge(
                item = item,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
            )
            if (showBookName == 2) {
                Text(
                    text = item.displayName,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = titleFontFamily,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (showBookName == 0) {
            Text(
                text = item.displayName,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                color = titleColor,
                fontSize = 12.sp,
                fontFamily = titleFontFamily,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BookshelfCover(
    item: BookshelfItemUi,
    modifier: Modifier,
    fragment: Fragment?,
    lifecycle: Lifecycle?
) {
    BookshelfComposeCover(
        item = item,
        modifier = modifier,
        fragment = fragment,
        lifecycle = lifecycle
    )
}

/**
 * 封面上角「源已停用」的小标。
 *
 * 放左上角：右上角是未读/更新中的角标，底部可能压着书名，只有这里不会撞。
 * 点它没有单独事件——整本书的点击会走提示框，那里才有换源入口。
 */
@Composable
internal fun BookshelfSourceDisabledBadge(
    item: BookshelfItemUi,
    modifier: Modifier = Modifier
) {
    if (item !is BookshelfBookItemUi || !item.sourceDisabled) return
    Text(
        text = stringResource(R.string.bookshelf_source_disabled_badge),
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Color.Black.copy(alpha = 0.62f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1
    )
}

@Composable
private fun BookshelfStatusBadge(item: BookshelfBookItemUi) {
    if (item.isUpdating) {
        CircularProgressIndicator(
            modifier = Modifier
                .padding(5.dp)
                .size(20.dp),
            strokeWidth = 2.dp,
            color = Color(LocalContext.current.accentColor)
        )
        return
    }
    if (!AppConfig.showUnread || item.unreadCount <= 0) return
    val badgeColor = if (item.hasNewChapter) {
        Color(LocalContext.current.accentColor)
    } else {
        Color.Black.copy(alpha = 0.55f)
    }
    Text(
        text = item.unreadCount.coerceAtMost(99999).toString(),
        modifier = Modifier
            .padding(5.dp)
            .clip(CircleShape)
            .background(badgeColor)
            .widthIn(min = 20.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 1
    )
}

private val BookshelfItemUi.displayName: String
    get() = when (this) {
        is BookshelfBookItemUi -> display.name
        is BookshelfFolderItemUi -> group.groupName
    }
