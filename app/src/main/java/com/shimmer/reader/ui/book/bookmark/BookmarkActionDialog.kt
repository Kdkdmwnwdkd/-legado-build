package com.shimmer.reader.ui.book.bookmark

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shimmer.reader.R
import com.shimmer.reader.data.entities.Bookmark
import com.shimmer.reader.ui.widget.compose.AppDialogFrame
import com.shimmer.reader.ui.widget.compose.AppDialogSize
import com.shimmer.reader.ui.widget.compose.AppDialogStyle
import com.shimmer.reader.ui.widget.compose.ComposeDialogFragment
import com.shimmer.reader.ui.widget.compose.LegadoComposeTheme
import com.shimmer.reader.ui.widget.compose.LegadoMiuixActionButton
import com.shimmer.reader.ui.widget.compose.rememberAppDialogStyle
import com.shimmer.reader.ui.widget.compose.toMiuixPalette
import com.shimmer.reader.utils.longToastOnUi
import com.shimmer.reader.utils.sendToClip
import com.shimmer.reader.utils.share
import com.shimmer.reader.utils.showDialogFragment

/**
 * 长按书签弹出的操作面板。
 *
 * 书签详情里只有「编辑/保存/删除」，想把手边的一段话发给别人得先打开编辑框全选拷贝，
 * 手机上尤其别扭。这里把拷贝和分享提到第一层，编辑仍然保留。
 */
class BookmarkActionDialog() : ComposeDialogFragment() {

    constructor(bookmark: Bookmark, editPos: Int = -1) : this() {
        arguments = Bundle().apply {
            putInt("editPos", editPos)
            putParcelable("bookmark", bookmark)
        }
    }

    override val dialogSize: AppDialogSize = AppDialogSize.Confirm

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        @Suppress("DEPRECATION")
        val bookmark = arguments?.getParcelable<Bookmark>("bookmark")
        if (bookmark == null) {
            dismiss()
            return View(requireContext())
        }
        val editPos = arguments?.getInt("editPos", -1) ?: -1
        val excerpt = bookmark.bookText.trim()
        val note = bookmark.content.trim()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoComposeTheme {
                    BookmarkActions(
                        chapterName = bookmark.chapterName,
                        excerpt = excerpt,
                        note = note,
                        onCopy = {
                            val context = requireContext()
                            val text = bookmarkExcerptText(bookmark)
                            dismiss()
                            if (text.isBlank()) {
                                context.longToastOnUi(R.string.bookmark_empty_excerpt)
                            } else {
                                context.sendToClip(text)
                            }
                        },
                        onShare = {
                            val context = requireContext()
                            val text = bookmarkShareText(bookmark)
                            dismiss()
                            context.share(text, context.getString(R.string.bookmark_share_excerpt))
                        },
                        onEdit = {
                            dismiss()
                            openEditor(bookmark, editPos)
                        },
                        onCancel = { dismiss() }
                    )
                }
            }
        }
    }

    /** 关掉动作面板再打开编辑框，两个对话框不会同时压在界面上 */
    private fun openEditor(bookmark: Bookmark, editPos: Int) {
        val activity = activity as? AppCompatActivity ?: return
        activity.showDialogFragment(BookmarkDialog(bookmark, editPos))
    }
}

@Composable
private fun BookmarkActions(
    chapterName: String,
    excerpt: String,
    note: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onCancel: () -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val hasText = excerpt.isNotBlank() || note.isNotBlank()
    AppDialogFrame(
        title = chapterName.ifBlank { stringResource(R.string.bookmark) },
        scrollContent = false,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (excerpt.isNotBlank()) {
                    Text(
                        text = excerpt,
                        color = style.primaryText,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (note.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = note,
                        color = style.secondaryText,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (hasText) {
                    Spacer(modifier = Modifier.height(14.dp))
                }
                if (excerpt.isNotBlank() || note.isNotBlank()) {
                    BookmarkActionRow(stringResource(R.string.bookmark_copy_excerpt), style, onCopy)
                    Spacer(modifier = Modifier.height(8.dp))
                    BookmarkActionRow(stringResource(R.string.bookmark_share_excerpt), style, onShare)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                BookmarkActionRow(stringResource(R.string.bookmark_edit), style, onEdit)
            }
        },
        actions = {
            LegadoMiuixActionButton(
                text = stringResource(R.string.cancel),
                palette = palette,
                onClick = onCancel,
                cornerRadius = style.actionRadius
            )
        }
    )
}

@Composable
private fun BookmarkActionRow(text: String, style: AppDialogStyle, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.actionRadius))
            .background(style.fieldSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = style.primaryText,
            fontSize = 15.sp
        )
    }
}

/** 只要正文：摘录和备注各一段，中间空一行 */
internal fun bookmarkExcerptText(bookmark: Bookmark): String {
    val excerpt = bookmark.bookText.trim()
    val note = bookmark.content.trim()
    return buildString {
        if (excerpt.isNotEmpty()) append(excerpt)
        if (note.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append(note)
        }
    }
}

/** 分享给别人时带书名和章节，单独一段摘录看不出出处 */
internal fun bookmarkShareText(bookmark: Bookmark): String {
    return buildString {
        if (bookmark.bookName.isNotBlank()) {
            append("《").append(bookmark.bookName.trim()).append("》")
            if (bookmark.bookAuthor.isNotBlank()) {
                append(" · ").append(bookmark.bookAuthor.trim())
            }
            append("\n")
        }
        if (bookmark.chapterName.isNotBlank()) {
            append(bookmark.chapterName.trim()).append("\n\n")
        }
        append(bookmarkExcerptText(bookmark))
    }.trim()
}
