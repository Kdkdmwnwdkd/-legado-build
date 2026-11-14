package com.shimmer.reader.ui.book.bookmark

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import com.shimmer.reader.R
import com.shimmer.reader.base.VMBaseActivity
import com.shimmer.reader.constant.AppLog
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.Bookmark
import com.shimmer.reader.databinding.ActivityAllBookmarkBinding
import com.shimmer.reader.ui.file.HandleFileContract
import com.shimmer.reader.utils.showDialogFragment
import com.shimmer.reader.utils.startActivityForBook
import com.shimmer.reader.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 所有书签
 */
class AllBookmarkActivity : VMBaseActivity<ActivityAllBookmarkBinding, AllBookmarkViewModel>() {

    override val viewModel by viewModels<AllBookmarkViewModel>()
    override val binding by viewBinding(ActivityAllBookmarkBinding::inflate)
    private val bookmarksState = mutableStateOf<List<Bookmark>>(emptyList())
    private val queryState = mutableStateOf("")

    /** 导出跟着搜索框走：搜到什么就导出什么，搜索框空着就是全部 */
    private fun currentBookmarks() = filterBookmarks(bookmarksState.value, queryState.value)

    private val exportDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                1 -> viewModel.exportBookmark(uri, currentBookmarks())
                2 -> viewModel.exportBookmarkMd(uri, currentBookmarks())
                3 -> viewModel.exportBookmarkObsidian(uri, currentBookmarks())
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeContent()
        binding.searchView.maxWidth = Int.MAX_VALUE
        binding.searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                binding.searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                queryState.value = newText.orEmpty()
                return true
            }
        })
        lifecycleScope.launch {
            appDb.bookmarkDao.flowAll().catch {
                AppLog.put("所有书签界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect { bookmarks ->
                bookmarksState.value = bookmarks
            }
        }
    }

    private fun initComposeContent() {
        val container = binding.recyclerView.parent as? ViewGroup ?: return
        val index = container.indexOfChild(binding.recyclerView)
        val params = binding.recyclerView.layoutParams
        container.removeView(binding.recyclerView)
        val cv = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = params
            setContent {
                AllBookmarkScreen(
                    bookmarks = bookmarksState.value,
                    query = queryState.value,
                    onItemClick = ::onItemClick,
                    onItemLongClick = ::onItemLongClick
                )
            }
        }
        container.addView(cv, index)
    }

    private fun onItemClick(bookmark: Bookmark, position: Int) {
        lifecycleScope.launch {
            val book = withContext(IO) {
                appDb.bookDao.getBook(bookmark.bookName, bookmark.bookAuthor)
            }
            if (book == null) {
                showDialogFragment(BookmarkDialog(bookmark, position))
            } else {
                startActivityForBook(book) {
                    putExtra("index", bookmark.chapterIndex)
                    putExtra("chapterPos", bookmark.chapterPos)
                }
            }
        }
    }

    /** 长按先给「拷贝/分享」，想改内容再进编辑 */
    private fun onItemLongClick(bookmark: Bookmark, position: Int) {
        showDialogFragment(BookmarkActionDialog(bookmark, position))
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.bookmark, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_export -> exportDir.launch {
                requestCode = 1
            }
            R.id.menu_export_md -> exportDir.launch {
                requestCode = 2
            }
            R.id.menu_export_obsidian -> exportDir.launch {
                requestCode = 3
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

}
