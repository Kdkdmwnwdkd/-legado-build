package com.shimmer.reader.ui.book.info

import android.content.Context
import android.text.InputType
import android.widget.LinearLayout
import com.shimmer.reader.R
import com.shimmer.reader.help.book.BookRemarkStore
import com.shimmer.reader.lib.dialogs.alert
import com.shimmer.reader.lib.theme.primaryTextColor
import com.shimmer.reader.lib.theme.secondaryTextColor
import com.shimmer.reader.lib.theme.view.ThemeEditText
import com.shimmer.reader.utils.dpToPx
import com.shimmer.reader.utils.toastOnUi

/**
 * 书籍备注的编辑弹框，详情页的两个入口（旧版 Activity 与 Compose 版）共用。
 */
object BookRemarkDialog {

    fun show(
        context: Context,
        bookName: String,
        bookUrl: String,
        onSaved: (String) -> Unit
    ) {
        if (bookUrl.isBlank()) return
        // 这两个是 Context 扩展属性，在 EditText 的 apply 块里要显式取
        val textColor = context.primaryTextColor
        val hintColor = context.secondaryTextColor
        val editText = ThemeEditText(context).apply {
            hint = context.getString(R.string.book_remark_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 8
            setTextColor(textColor)
            setHintTextColor(hintColor)
            setText(BookRemarkStore.get(bookUrl))
            setSelection(text?.length ?: 0)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(), 4.dpToPx(), 16.dpToPx(), 0)
            addView(
                editText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        // 书名里可能出现 % 之类的字符，不用格式化串，直接拼
        val title = context.getString(R.string.book_remark_edit_title) +
            bookName.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        context.alert(title) {
            customView { container }
            positiveButton(R.string.book_remark_save) {
                BookRemarkStore.set(bookUrl, editText.text?.toString())
                val saved = BookRemarkStore.get(bookUrl)
                onSaved(saved)
                context.toastOnUi(
                    if (saved.isBlank()) R.string.book_remark_cleared else R.string.book_remark_saved
                )
            }
            neutralButton(R.string.book_remark_clear) {
                BookRemarkStore.set(bookUrl, "")
                onSaved("")
                context.toastOnUi(R.string.book_remark_cleared)
            }
            negativeButton(R.string.cancel)
        }
    }
}
