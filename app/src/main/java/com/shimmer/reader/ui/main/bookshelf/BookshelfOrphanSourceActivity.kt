package com.shimmer.reader.ui.main.bookshelf

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import com.shimmer.reader.base.BaseActivity
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.Book
import com.shimmer.reader.databinding.ActivityBookshelfOrphanSourceBinding
import com.shimmer.reader.ui.book.info.BookInfoNavigator
import com.shimmer.reader.ui.widget.compose.LegadoComposeTheme
import com.shimmer.reader.utils.startActivityForBook
import com.shimmer.reader.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 失效书源清单：把书架上挂到已停用 / 已删除书源的书集中列出来。
 *
 * 用 Activity 而不是对话框，是为了让「换源」这条路走得通：换源在书籍详情页，
 * 这一页会被压到后台，回来 onResume 重查一次，处理过的书自然就不在列表里了。
 */
class BookshelfOrphanSourceActivity : BaseActivity<ActivityBookshelfOrphanSourceBinding>() {

    override val binding by viewBinding(ActivityBookshelfOrphanSourceBinding::inflate)
    private var booksState by mutableStateOf<List<Book>>(emptyList())
    private var loadingState by mutableStateOf(true)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeRoot.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeRoot.setContent {
            LegadoComposeTheme {
                BookshelfOrphanSourceScreen(
                    books = booksState,
                    loading = loadingState,
                    onBack = ::finish,
                    onChangeSource = { BookInfoNavigator.open(this@BookshelfOrphanSourceActivity, it) },
                    onReadAnyway = { startActivityForBook(it) }
                )
            }
        }
        reload()
    }

    override fun onResume() {
        super.onResume()
        // 换源、重新启用书源都可能发生在别的界面，回来必须重查一次。
        // 首次进入由 onActivityCreated 负责，这里跳过，免得同一个查询跑两遍。
        if (!loadingState) reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            booksState = withContext(IO) { appDb.bookDao.booksWithOrphanSource() }
            loadingState = false
        }
    }
}
