package com.shimmer.reader.ui.book.searchContent

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.core.view.allViews
import androidx.lifecycle.lifecycleScope
import com.shimmer.reader.R
import com.shimmer.reader.base.VMBaseActivity
import com.shimmer.reader.constant.AppLog
import com.shimmer.reader.constant.EventBus
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.Book
import com.shimmer.reader.data.entities.BookChapter
import com.shimmer.reader.databinding.ActivitySearchContentBinding
import com.shimmer.reader.help.IntentData
import com.shimmer.reader.help.book.BookHelp
import com.shimmer.reader.help.book.isLocal
import com.shimmer.reader.lib.theme.primaryTextColor
import com.shimmer.reader.ui.widget.recycler.UpLinearLayoutManager
import com.shimmer.reader.ui.widget.recycler.VerticalDivider
import com.shimmer.reader.utils.applyNavigationBarMargin
import com.shimmer.reader.utils.applyTint
import com.shimmer.reader.utils.invisible
import com.shimmer.reader.utils.observeEvent
import com.shimmer.reader.utils.postEvent
import com.shimmer.reader.utils.showSoftInput
import com.shimmer.reader.utils.toastOnUi
import com.shimmer.reader.utils.viewbindingdelegate.viewBinding
import com.shimmer.reader.utils.visible
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class SearchContentActivity :
    VMBaseActivity<ActivitySearchContentBinding, SearchContentViewModel>(),
    SearchContentAdapter.Callback {

    override val binding by viewBinding(ActivitySearchContentBinding::inflate)
    override val viewModel by viewModels<SearchContentViewModel>()
    private val adapter by lazy { SearchContentAdapter(this, this) }
    private val mLayoutManager by lazy { UpLinearLayoutManager(this) }
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }
    private var durChapterIndex = 0
    private var searchJob: Job? = null
    private var initJob: Job? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.llSearchBaseInfo.setBackgroundResource(R.drawable.bg_search_glass_panel)
        binding.llSearchBaseInfo.applyNavigationBarMargin()
        binding.tvCurrentSearchInfo.setTextColor(primaryTextColor)
        binding.ivSearchContentTop.setColorFilter(primaryTextColor)
        binding.ivSearchContentBottom.setColorFilter(primaryTextColor)
        val searchResultList = IntentData.get<List<SearchResult>>("searchResultList")
        val position = intent.getIntExtra("searchResultIndex", 0)
        val noSearchResult = searchResultList == null
        initSearchView(noSearchResult)
        initRecyclerView()
        initView()
        val bookUrl = intent.getStringExtra("bookUrl") ?: return
        viewModel.initBook(bookUrl) {
            initSearchResultList(searchResultList, position)
            initBook(noSearchResult)
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.content_search, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_enable_replace)?.isChecked = SearchContentViewModel.replaceEnabled
        menu.findItem(R.id.menu_enable_regex)?.isChecked = SearchContentViewModel.regexReplace
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_enable_replace -> {
                SearchContentViewModel.replaceEnabled = !SearchContentViewModel.replaceEnabled
                item.isChecked = SearchContentViewModel.replaceEnabled
            }
            R.id.menu_enable_regex -> {
                SearchContentViewModel.regexReplace = !SearchContentViewModel.regexReplace
                item.isChecked = SearchContentViewModel.regexReplace
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initSearchResultList(list: List<SearchResult>?, position: Int) {
        list ?: return
        viewModel.searchResultList.addAll(list)
        viewModel.searchResultCounts = list.size
        adapter.setItems(list)
        binding.recyclerView.scrollToPosition(position)
    }

    private fun initSearchView(requestFocus: Boolean) {
        searchView.applyTint(primaryTextColor)
        searchView.isSubmitButtonEnabled = true
        searchView.queryHint = getString(R.string.search)
        if (requestFocus) searchView.isIconified = false
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                startContentSearch(query.trim())
                searchView.clearFocus()
                return false
            }

            override fun onQueryTextChange(newText: String): Boolean {
                return false
            }
        })
    }

    private fun initRecyclerView() {
        binding.recyclerView.layoutManager = mLayoutManager
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.adapter = adapter
    }

    private fun initView() {
        binding.ivSearchContentTop.setOnClickListener {
            mLayoutManager.scrollToPositionWithOffset(0, 0)
        }
        binding.ivSearchContentBottom.setOnClickListener {
            if (adapter.itemCount > 0) {
                mLayoutManager.scrollToPositionWithOffset(adapter.itemCount - 1, 0)
            }
        }
        binding.tvCurrentSearchInfo.setOnClickListener {
            searchView.allViews.forEach { view ->
                if (view is EditText) {
                    view.showSoftInput()
                    return@setOnClickListener
                }
            }
        }
        binding.fbStop.setOnClickListener {
            searchJob?.cancel()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initBook(submit: Boolean = true) {
        binding.tvCurrentSearchInfo.text =
            this.getString(R.string.search_content_size) + ": ${viewModel.searchResultCounts}"
        viewModel.book?.let {
            initCacheFileNames(it)
            durChapterIndex = it.durChapterIndex
            intent.getStringExtra("searchWord")?.let { searchWord ->
                searchView.setQuery(searchWord, submit)
            }
        }
    }

    private fun initCacheFileNames(book: Book) {
        initJob = lifecycleScope.launch {
            withContext(IO) {
                viewModel.cacheChapterNames.addAll(BookHelp.getChapterFiles(book))
            }
            adapter.notifyItemRangeChanged(0, adapter.itemCount, true)
        }
    }

    override fun observeLiveBus() {
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
            viewModel.book?.bookUrl?.let { bookUrl ->
                if (book.bookUrl == bookUrl) {
                    viewModel.cacheChapterNames.addAll(BookHelp.getChapterCacheFileNames(book, chapter))
                    adapter.notifyItemChanged(chapter.index, true)
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    fun startContentSearch(query: String) {
        // 按章节搜索内容
        if (query.isBlank()) return
        searchJob?.cancel()
        adapter.clearItems()
        viewModel.resetSearchState()
        viewModel.lastQuery = query
        setSearchLoading(true)
        searchJob = lifecycleScope.launch(IO) {
            try {
                initJob?.join()
                val chapters = appDb.bookChapterDao.getChapterList(viewModel.bookUrl)
                viewModel.totalChapters = chapters.size
                chapters.forEach { bookChapter ->
                    ensureActive()
                    if (isChapterSearchable(bookChapter)) {
                        val searchResults = viewModel.searchChapter(query, bookChapter)
                        viewModel.searchedChapters++
                        publishResults(searchResults)
                    } else {
                        viewModel.pendingChapters.add(bookChapter)
                    }
                    if (viewModel.searchedChapters % 5 == 0) postSearchInfo()
                }
                finishSearchPass()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("全文搜索出错\n${e.localizedMessage}", e)
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    setSearchLoading(false)
                }
            }
        }
    }

    /**
     * 联网补搜没缓存的章节。一次最多 [SearchContentViewModel.MAX_NETWORK_BATCH] 章，
     * 搜完还有剩余就在列表底部再给一次入口，不做无限请求。
     */
    private fun startNetworkSearch() {
        val query = viewModel.lastQuery
        if (query.isBlank() || viewModel.pendingChapters.isEmpty()) return
        searchJob?.cancel()
        setSearchLoading(true)
        searchJob = lifecycleScope.launch(IO) {
            val batch = viewModel.pendingChapters.take(SearchContentViewModel.MAX_NETWORK_BATCH)
            val done = mutableListOf<BookChapter>()
            var failStreak = 0
            var stoppedBySource = false
            try {
                for ((index, chapter) in batch.withIndex()) {
                    ensureActive()
                    val content = viewModel.fetchChapterContent(chapter)
                    if (content == null) {
                        failStreak++
                        viewModel.networkFailedChapters++
                        if (failStreak >= SearchContentViewModel.MAX_FAIL_STREAK) {
                            stoppedBySource = true
                            break
                        }
                    } else {
                        failStreak = 0
                        done.add(chapter)
                        val results = viewModel.searchChapter(query, chapter, content)
                        viewModel.searchedChapters++
                        publishResults(results)
                    }
                    if (index % 3 == 0) postSearchInfo()
                    // 章节之间留点间隔，别把书源打成压测
                    delay(NETWORK_INTERVAL_MS)
                }
                viewModel.pendingChapters.removeAll(done)
                withContext(Dispatchers.Main) {
                    setSearchLoading(false)
                    adapter.setItems(viewModel.searchResultList)
                    appendTrailingItem()
                    postSearchInfo()
                    if (stoppedBySource) {
                        toastOnUi(R.string.search_content_net_stopped)
                    } else if (viewModel.networkFailedChapters > 0) {
                        toastOnUi(
                            getString(
                                R.string.search_content_net_failed,
                                viewModel.networkFailedChapters
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("全文搜索联网补搜出错\n${e.localizedMessage}", e)
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    setSearchLoading(false)
                }
            }
        }
    }

    private fun isChapterSearchable(chapter: BookChapter): Boolean {
        if (isLocalBook) return true
        val book = viewModel.book ?: return false
        return BookHelp.getChapterCacheFileNames(book, chapter)
            .any(viewModel.cacheChapterNames::contains)
    }

    private suspend fun publishResults(results: List<SearchResult>) {
        if (results.isEmpty()) return
        viewModel.searchResultList.addAll(results)
        withContext(Dispatchers.Main) {
            adapter.addItems(results)
            postSearchInfo()
        }
    }

    private suspend fun finishSearchPass() {
        withContext(Dispatchers.Main) {
            setSearchLoading(false)
            appendTrailingItem()
            postSearchInfo()
        }
    }

    /** 列表末尾那一行：还能不能补搜、或者是不是真的一条都没有 */
    private fun appendTrailingItem() {
        val pending = viewModel.pendingChapters.size
        if (pending > 0) {
            val stringId = if (viewModel.searchResultList.isEmpty()) {
                R.string.search_content_uncached_empty
            } else {
                R.string.search_content_uncached_hint
            }
            adapter.addItem(
                SearchResult(
                    resultText = getString(stringId, pending),
                    isAction = true
                )
            )
        } else if (viewModel.searchResultList.isEmpty()) {
            adapter.addItem(SearchResult(resultText = getString(R.string.search_content_empty)))
        }
    }

    private fun setSearchLoading(loading: Boolean) {
        binding.refreshProgressBar.isAutoLoading = loading
        if (loading) binding.fbStop.visible() else binding.fbStop.invisible()
    }

    private fun postSearchInfo() {
        val text = getString(
            R.string.search_content_scope,
            viewModel.searchResultCounts,
            viewModel.searchedChapters,
            viewModel.totalChapters
        )
        binding.tvCurrentSearchInfo.post {
            binding.tvCurrentSearchInfo.text = text
        }
    }

    private val isLocalBook: Boolean
        get() = viewModel.book?.isLocal == true

    override fun openSearchResult(searchResult: SearchResult, index: Int) {
        if (searchResult.isAction) {
            startNetworkSearch()
            return
        }
        searchJob?.cancel()
        postEvent(EventBus.SEARCH_RESULT, viewModel.searchResultList as List<SearchResult>)
        val searchData = Intent()
        val key = System.currentTimeMillis()
        IntentData.put("searchResult$key", searchResult)
        IntentData.put("searchResultList$key", viewModel.searchResultList)
        searchData.putExtra("key", key)
        searchData.putExtra("index", index)
        setResult(RESULT_OK, searchData)
        finish()
    }

    override fun durChapterIndex(): Int {
        return durChapterIndex
    }

}

/** 联网补搜时两章之间的间隔，别把书源当压测工具用 */
private const val NETWORK_INTERVAL_MS = 250L
