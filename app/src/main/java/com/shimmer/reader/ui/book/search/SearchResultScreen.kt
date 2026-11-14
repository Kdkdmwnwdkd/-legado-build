package com.shimmer.reader.ui.book.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import com.shimmer.reader.data.entities.SearchBook
import com.shimmer.reader.help.config.AppConfig
import com.shimmer.reader.ui.main.bookshelf.compose.BookshelfListItemStyle
import com.shimmer.reader.ui.main.bookshelf.compose.rememberBookshelfListRenderConfig
import com.shimmer.reader.ui.widget.compose.ComposeLazyListFastScroller
import com.shimmer.reader.ui.widget.compose.LegadoComposeTheme
import com.shimmer.reader.ui.widget.compose.SearchBookListItem
import com.shimmer.reader.ui.widget.compose.SearchBookPreviewOverlay
import com.shimmer.reader.ui.widget.compose.SearchBookPreviewState
import com.shimmer.reader.utils.stableSearchBookKey

/**
 * 搜索结果列表的 Compose 实现，复用与发现页一致的 [BookListCardSurface] 卡片骨架与
 * [rememberBookshelfListRenderConfig] 主题色板，使搜索结果与发现/书架视觉统一、随主题走。
 */
@Composable
fun SearchResultScreen(
    books: List<SearchBook>,
    isLoading: Boolean,
    hasMore: Boolean,
    scrollToTopSignal: Int,
    bookshelfTick: Int,
    isInBookshelf: (SearchBook) -> Boolean,
    isPreview: Boolean,
    lifecycle: Lifecycle,
    onBookClick: (SearchBook) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    LegadoComposeTheme {
        val renderConfig = rememberBookshelfListRenderConfig()
        val rounded = AppConfig.bookshelfListItemStyle == BookshelfListItemStyle.RoundedCard
        val listState = rememberLazyListState()
        var previewState by remember { mutableStateOf<SearchBookPreviewState?>(null) }

        val shouldLoadMore by remember(books, hasMore, isLoading) {
            derivedStateOf {
                if (!hasMore || isLoading || books.isEmpty()) return@derivedStateOf false
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                last >= books.lastIndex - 2
            }
        }
        LaunchedEffect(shouldLoadMore) {
            if (shouldLoadMore) onLoadMore()
        }
        LaunchedEffect(scrollToTopSignal) {
            if (scrollToTopSignal > 0) listState.scrollToItem(0)
        }

        Box(modifier = modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 86.dp),
                verticalArrangement = Arrangement.spacedBy(if (rounded) 4.dp else 2.dp)
            ) {
                if (isPreview) {
                    item(key = "__preview_hint__") {
                        Text(
                            text = "下面是上次搜到的结果，正在重新搜索…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 6.dp)
                        )
                    }
                }
                itemsIndexed(
                    items = books,
                    key = { _, book -> book.stableSearchBookKey() }
                ) { _, book ->
                    val inBookshelf = remember(book.bookUrl, bookshelfTick) { isInBookshelf(book) }
                    SearchBookListItem(
                        book = book,
                        inBookshelf = inBookshelf,
                        rounded = rounded,
                        renderConfig = renderConfig,
                        lifecycle = lifecycle,
                        showOriginCount = true,
                        onClick = { onBookClick(book) },
                        onPreview = { bounds ->
                            previewState = SearchBookPreviewState(book, bounds)
                        }
                    )
                }
            }
            ComposeLazyListFastScroller(
                state = listState,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
            SearchBookPreviewOverlay(
                state = previewState,
                renderConfig = renderConfig,
                fragment = null,
                lifecycle = lifecycle,
                onDismissed = { previewState = null },
                onOpen = { book ->
                    previewState = null
                    onBookClick(book)
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
