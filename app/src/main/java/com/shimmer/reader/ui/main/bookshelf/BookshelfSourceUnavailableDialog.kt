package com.shimmer.reader.ui.main.bookshelf

import androidx.fragment.app.Fragment
import com.shimmer.reader.R
import com.shimmer.reader.data.dao.BookShelfDisplay
import com.shimmer.reader.data.entities.Book
import com.shimmer.reader.lib.dialogs.alert
import com.shimmer.reader.ui.book.info.BookInfoNavigator

/**
 * 书架点开一本「源已停用」的书时的提示。
 *
 * 不在这里直接调换源界面：换源要跑一次全源搜索，选中之后会换掉 bookUrl，
 * 书架上的分组、标签、阅读进度都挂在旧记录上，这一步得让用户自己确认。
 * 所以这里只把情况说清楚，给一条通往详情页换源的路，另外保留「仍然打开」——
 * 书源关掉之后已经缓存的章节还能读，不该因为源没了就连缓存也进不去。
 */
fun Fragment.showSourceUnavailableDialog(
    book: BookShelfDisplay,
    onOpenAnyway: () -> Unit
) {
    showSourceUnavailableDialog(
        name = book.name,
        originName = book.originName,
        onOpenAnyway = onOpenAnyway
    ) {
        BookInfoNavigator.open(requireContext(), book.toMinimalBook())
    }
}

fun Fragment.showSourceUnavailableDialog(
    book: Book,
    onOpenAnyway: () -> Unit
) {
    showSourceUnavailableDialog(
        name = book.name,
        originName = book.originName,
        onOpenAnyway = onOpenAnyway
    ) {
        BookInfoNavigator.open(requireContext(), book)
    }
}

private fun Fragment.showSourceUnavailableDialog(
    name: String,
    originName: String,
    onOpenAnyway: () -> Unit,
    goChangeSource: () -> Unit
) {
    alert(
        title = getString(R.string.bookshelf_source_disabled_title),
        message = getString(R.string.bookshelf_source_disabled_message, name, originName)
    ) {
        positiveButton(R.string.bookshelf_source_disabled_change_source) {
            goChangeSource()
        }
        negativeButton(R.string.bookshelf_source_disabled_open_anyway) {
            onOpenAnyway()
        }
    }
}
