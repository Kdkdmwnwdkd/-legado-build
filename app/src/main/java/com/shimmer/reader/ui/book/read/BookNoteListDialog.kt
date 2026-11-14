package com.shimmer.reader.ui.book.read

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.Book
import com.shimmer.reader.data.entities.Bookmark
import com.shimmer.reader.ui.widget.compose.AppDialogSize
import com.shimmer.reader.ui.widget.compose.AppDialogStyle
import com.shimmer.reader.ui.widget.compose.ComposeDialogFragment
import com.shimmer.reader.ui.widget.compose.rememberAppDialogStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本书的摘录列表。
 *
 * 阅读页里加书签时，摘录正文就是选中的那段文字，想法写在备注里；这里把它们按章节
 * 顺序摊开，点一条直接跳回原文。以前要看自己划过的句子，得退出阅读、进书签页、
 * 再从一整摞跨书的记录里找，路径太长。
 */
class BookNoteListDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    private val bookName: String by lazy {
        requireArguments().getString(ARG_NAME).orEmpty()
    }

    private val bookAuthor: String by lazy {
        requireArguments().getString(ARG_AUTHOR).orEmpty()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                val scope = rememberCoroutineScope()
                val notes by appDb.bookmarkDao.flowByBook(bookName, bookAuthor)
                    .collectAsState(initial = emptyList())
                BookNoteListContent(
                    bookLabel = if (bookAuthor.isBlank()) bookName else "$bookName（$bookAuthor）",
                    notes = notes,
                    style = style,
                    onOpen = { note ->
                        (activity as? ReadBookActivity)?.openBookNote(note)
                        dismissAllowingStateLoss()
                    },
                    onDelete = { note ->
                        scope.launch {
                            withContext(Dispatchers.IO) { appDb.bookmarkDao.delete(note) }
                        }
                    },
                    onClose = { dismissAllowingStateLoss() }
                )
            }
        }
    }

    companion object {
        private const val ARG_NAME = "bookName"
        private const val ARG_AUTHOR = "bookAuthor"

        fun newInstance(book: Book): BookNoteListDialog {
            return BookNoteListDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_NAME, book.name)
                    putString(ARG_AUTHOR, book.author)
                }
            }
        }
    }
}

@Composable
private fun BookNoteListContent(
    bookLabel: String,
    notes: List<Bookmark>,
    style: AppDialogStyle,
    onOpen: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
    onClose: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<Bookmark?>(null) }
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = bookLabel,
                color = style.primaryText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (notes.isEmpty()) "还没有摘录" else "${notes.size} 条",
                color = style.secondaryText,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(6.dp))
        if (notes.isEmpty()) {
            Text(
                text = "阅读时长按选中文字，点「书签」就能把原文和想法一起留下来。",
                color = style.secondaryText,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 18.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                items(notes, key = { it.time }) { note ->
                    NoteRow(
                        note = note,
                        timeText = timeFormat.format(Date(note.time)),
                        style = style,
                        onOpen = { onOpen(note) },
                        onDelete = { pendingDelete = note }
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onClose) {
                Text("关闭", color = style.accent)
            }
        }
    }

    pendingDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条摘录？") },
            text = {
                Text(
                    text = note.bookText.take(80).ifBlank { note.chapterName },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(note)
                    pendingDelete = null
                }) {
                    Text("删除", color = style.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun NoteRow(
    note: Bookmark,
    timeText: String,
    style: AppDialogStyle,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = note.chapterName.ifBlank { "未知章节" },
                color = style.accent,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = timeText,
                color = style.secondaryText,
                fontSize = 11.sp
            )
        }
        if (note.bookText.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = note.bookText,
                color = style.primaryText,
                fontSize = 14.sp,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (note.content.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = note.content,
                color = style.secondaryText,
                fontSize = 13.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = "删除",
                color = style.danger,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}
