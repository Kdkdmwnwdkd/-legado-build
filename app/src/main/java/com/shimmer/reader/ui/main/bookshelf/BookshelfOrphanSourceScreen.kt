package com.shimmer.reader.ui.main.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shimmer.reader.R
import com.shimmer.reader.data.entities.Book
import com.shimmer.reader.help.book.orphanSourceStats
import com.shimmer.reader.ui.widget.compose.AppListSpacing
import com.shimmer.reader.ui.widget.compose.AppManagementCard
import com.shimmer.reader.ui.widget.compose.AppManagementScaffold
import com.shimmer.reader.ui.widget.compose.LegadoMiuixActionButton
import com.shimmer.reader.ui.widget.compose.rememberAppManagementPalette

/**
 * 书架里所有「书源已经不能用了」的书，集中在一页处理。
 *
 * 书架上的角标只解决「看得见」，书一多还是得翻页找。这一页按最近阅读时间倒序排，
 * 每条都能直接跳详情页换源；换完回来重查一次，修好的自己就不在列表里了。
 */
@Composable
internal fun BookshelfOrphanSourceScreen(
    books: List<Book>,
    loading: Boolean,
    onBack: () -> Unit,
    onChangeSource: (Book) -> Unit,
    onReadAnyway: (Book) -> Unit
) {
    val palette = rememberAppManagementPalette()
    AppManagementScaffold(
        title = stringResource(R.string.bookshelf_orphan_title),
        selectedCount = 0,
        totalCount = books.size,
        palette = palette,
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.settings.page)
        ) {
            when {
                loading -> LoadingContent()
                books.isEmpty() -> EmptyContent()
                else -> OrphanList(
                    books = books,
                    onChangeSource = onChangeSource,
                    onReadAnyway = onReadAnyway
                )
            }
        }
    }
}

@Composable
private fun OrphanList(
    books: List<Book>,
    onChangeSource: (Book) -> Unit,
    onReadAnyway: (Book) -> Unit
) {
    val palette = rememberAppManagementPalette()
    val stats = orphanSourceStats(books)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(AppListSpacing.Normal)
    ) {
        item(key = "summary") {
            AppManagementCard(palette = palette) {
                Text(
                    text = stringResource(
                        R.string.bookshelf_orphan_stats,
                        stats.bookCount,
                        stats.sourceCount
                    ),
                    color = palette.settings.primaryText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.bookshelf_orphan_summary),
                    color = palette.settings.secondaryText,
                    fontSize = 13.sp
                )
                if (stats.sourceCount == 1) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.bookshelf_orphan_one_source),
                        color = palette.settings.secondaryText,
                        fontSize = 13.sp
                    )
                }
            }
        }
        items(books, key = { "orphan:${it.bookUrl}" }) { book ->
            OrphanBookCard(
                book = book,
                onChangeSource = { onChangeSource(book) },
                onReadAnyway = { onReadAnyway(book) }
            )
        }
    }
}

@Composable
private fun OrphanBookCard(
    book: Book,
    onChangeSource: () -> Unit,
    onReadAnyway: () -> Unit
) {
    val palette = rememberAppManagementPalette()
    val progressTitle = book.durChapterTitle?.takeIf { it.isNotBlank() }
    AppManagementCard(
        palette = palette,
        onClick = onChangeSource
    ) {
        Text(
            text = book.name,
            color = palette.settings.primaryText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = listOf(book.author, book.originName)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            color = palette.settings.secondaryText,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = if (progressTitle != null) {
                stringResource(R.string.bookshelf_orphan_progress, progressTitle)
            } else {
                stringResource(R.string.bookshelf_orphan_no_progress)
            },
            color = palette.settings.secondaryText,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LegadoMiuixActionButton(
                text = stringResource(R.string.bookshelf_orphan_change_source),
                palette = palette.miuix,
                onClick = onChangeSource,
                primary = true,
                modifier = Modifier.weight(1f)
            )
            LegadoMiuixActionButton(
                text = stringResource(R.string.bookshelf_orphan_read_anyway),
                palette = palette.miuix,
                onClick = onReadAnyway,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun EmptyContent() {
    val palette = rememberAppManagementPalette()
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        AppManagementCard(
            palette = palette,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.bookshelf_orphan_empty),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp),
                color = palette.settings.secondaryText,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}
