package com.shimmer.reader.model.webBook

import android.os.SystemClock
import com.shimmer.reader.constant.AppConst
import com.shimmer.reader.constant.AppLog
import com.shimmer.reader.constant.PreferKey
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.BookSourcePart
import com.shimmer.reader.data.entities.SearchBook
import com.shimmer.reader.exception.NoStackTraceException
import com.shimmer.reader.help.config.AppConfig
import com.shimmer.reader.help.search.LocalPreview
import com.shimmer.reader.help.source.ScheduledSource
import com.shimmer.reader.help.source.SearchSchedule
import com.shimmer.reader.ui.book.search.SearchScope
import com.shimmer.reader.utils.NetworkUtils
import com.shimmer.reader.utils.getPrefBoolean
import com.shimmer.reader.utils.mapParallelSafe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import splitties.init.appCtx
import java.util.concurrent.Executors
import kotlin.math.min

class SearchModel(private val scope: CoroutineScope, private val callBack: CallBack) {
    companion object {
        /** 连接阶段给 6 秒：域名死掉、DNS 解析不出的源不该占满整个超时窗口 */
        private const val CONNECT_TIMEOUT_MS = 6_000L
        /** 单次搜索请求的总时长上限，比外层的 withTimeout 略短，让 OkHttp 先给出明确错误 */
        private const val CALL_TIMEOUT_MS = 20_000L
        /** 单个书源（含解析）的总时长上限 */
        private const val SOURCE_TIMEOUT_MS = 25_000L
    }

    val threadCount = AppConfig.threadCount.coerceAtLeast(1)
    private var searchPool: ExecutorCoroutineDispatcher? = null
    private var mSearchId = 0L
    private var searchPage = 1
    private var searchKey: String = ""
    private var bookSourceParts = emptyList<BookSourcePart>()
    /** 按「同站错峰」排好顺序的搜索任务 */
    private var searchTasks = emptyList<ScheduledSource<BookSourcePart>>()
    private var searchBooks = arrayListOf<SearchBook>()
    private var searchJob: Job? = null
    private var workingState = MutableStateFlow(true)

    /** 首屏铺出来的缓存条目，网络结果到达后按源逐条替换 */
    private val previewBookUrls = HashSet<String>()

    /** 这轮有没有铺过缓存，没铺就不用做清理 */
    private var previewUsed = false


    private fun initSearchPool() {
        searchPool?.close()
        searchPool = Executors
            .newFixedThreadPool(min(threadCount, AppConst.MAX_SEARCH_THREAD))
            .asCoroutineDispatcher()
    }

    fun search(searchId: Long, key: String) {
        if (searchId != mSearchId) {
            if (key.isEmpty()) {
                return
            }
            searchKey = key
            if (mSearchId != 0L) {
                close()
            }
            searchBooks.clear()
            previewBookUrls.clear()
            previewUsed = false
            bookSourceParts = callBack.getSearchScope().getBookSourceParts()
            if (bookSourceParts.isEmpty()) {
                callBack.onSearchCancel(NoStackTraceException("启用书源为空"))
                return
            }
            searchTasks = scheduleSources(bookSourceParts)
            mSearchId = searchId
            searchPage = 1
            initSearchPool()
        } else {
            searchPage++
        }
        startSearch()
    }

    private fun startSearch() {
        val precision = appCtx.getPrefBoolean(PreferKey.precisionSearch)
        var hasMore = false
        searchJob = scope.launch(searchPool!!) {
            // 先把上次的结果铺出来，首屏不再是一个空列表加转圈
            if (searchPage == 1) injectLocalPreview()
            flow {
                val startAt = SystemClock.elapsedRealtime()
                for (task in searchTasks) {
                    // 同一个站点上的第 2 个源往后挪一点，别让十几个源同时挤同一个站
                    val wait = task.delayMs - (SystemClock.elapsedRealtime() - startAt)
                    if (wait > 0) delay(wait)
                    task.source.getBookSource()?.let {
                        emit(it)
                    }
                    workingState.first { it }
                }
            }.onStart {
                callBack.onSearchStart()
            }.mapParallelSafe(threadCount) { part ->
                // 单源失败不该中断整轮搜索；这里自己吞掉异常，把「哪个源、有没有结果」
                // 原样带出去，后面既要用它清理缓存，也要用它决定要不要刷新列表
                val books = try {
                    withTimeout(SOURCE_TIMEOUT_MS) {
                        WebBook.searchBookAwait(
                            part, searchKey, searchPage,
                            filter = { name, author, kind ->
                                !precision || name.contains(searchKey) ||
                                        author.contains(searchKey) ||
                                        kind?.contains(searchKey) == true
                            },
                            connectTimeoutMs = CONNECT_TIMEOUT_MS,
                            callTimeoutMs = CALL_TIMEOUT_MS
                        )
                    }
                } catch (e: Throwable) {
                    currentCoroutineContext().ensureActive()
                    null
                }
                SourceResult(part.bookSourceUrl, books)
            }.onEach { result ->
                val items = result.books
                if (items == null) {
                    // 这个源这次没搜出来：把它上次留下的缓存条目一并撤掉，
                    // 否则用户点进去才发现打不开
                    if (dropPreviewOf(result.origin)) {
                        callBack.onSearchSuccess(searchBooks)
                    }
                } else {
                    for (book in items) {
                        book.releaseHtmlData()
                    }
                    hasMore = hasMore || items.isNotEmpty()
                    if (items.isNotEmpty()) appDb.searchBookDao.insert(*items.toTypedArray())
                    val dropped = dropPreviewOf(result.origin)
                    mergeItems(items, precision)
                    currentCoroutineContext().ensureActive()
                    if (items.isNotEmpty() || dropped) callBack.onSearchSuccess(searchBooks)
                }
            }.onCompletion {
                if (it == null) callBack.onSearchFinish(searchBooks.isEmpty(), hasMore)
            }.catch {
                AppLog.put("书源搜索出错\n${it.localizedMessage}", it)
                callBack.onSearchCancel(it)
            }.collect()
        }
    }

    /**
     * 搜索开始前先用本地记录铺一屏。
     *
     * 同一个词搜第二次时，上次的结果立刻显示出来，网络结果再逐条替换，
     * 首屏不再是一个空列表加转圈。只铺启用中的书源：已停用的源上次能搜到，
     * 不代表现在还能打开。
     */
    private suspend fun injectLocalPreview() {
        if (!AppConfig.searchLocalPreview) return
        if (searchKey.isBlank()) return
        val allowed = bookSourceParts.mapTo(HashSet()) { it.bookSourceUrl }
        // 合并前多取一些：同一个书名在多个源各有记录，合并后会少一截
        val cached = withContext(Dispatchers.IO) {
            runCatching {
                appDb.searchBookDao.searchLocalPreview(searchKey, LocalPreview.DEFAULT_LIMIT * 4)
            }.getOrElse { emptyList() }
        }.filter { it.origin in allowed }
        if (cached.isEmpty()) return
        val preview = LocalPreview.merge(cached, LocalPreview.DEFAULT_LIMIT)
        if (preview.isEmpty()) return
        preview.forEach { previewBookUrls.add(it.bookUrl) }
        previewUsed = true
        searchBooks.addAll(preview)
        currentCoroutineContext().ensureActive()
        callBack.onSearchPreview(searchBooks)
    }

    /**
     * 某个源本轮已经跑完，撤掉它上次留下的缓存条目。
     *
     * @return 是否真的撤掉了东西，用来决定要不要刷新界面
     */
    private fun dropPreviewOf(origin: String): Boolean {
        if (!previewUsed || previewBookUrls.isEmpty()) return false
        val removed = searchBooks.removeAll { it.origin == origin && it.bookUrl in previewBookUrls }
        if (removed) {
            previewBookUrls.removeAll { url -> searchBooks.none { it.bookUrl == url } }
        }
        return removed
    }

    /** 单个源的搜索结果，books 为 null 表示这个源这次没跑通 */
    private data class SourceResult(val origin: String, val books: List<SearchBook>?)

    private suspend fun mergeItems(newDataS: List<SearchBook>, precision: Boolean) {
        if (newDataS.isNotEmpty()) {
            val copyData = ArrayList(searchBooks)
            val equalData = arrayListOf<SearchBook>()
            val containsData = arrayListOf<SearchBook>()
            val tagsData = arrayListOf<SearchBook>()
            val otherData = arrayListOf<SearchBook>()
            copyData.forEach {
                currentCoroutineContext().ensureActive()
                if (it.name == searchKey || it.author == searchKey) {
                    equalData.add(it)
                } else if (it.kind?.contains(searchKey) == true) {
                    tagsData.add(it)
                } else if (it.name.contains(searchKey) || it.author.contains(searchKey)) {
                    containsData.add(it)
                } else {
                    otherData.add(it)
                }
            }
            newDataS.forEach { nBook ->
                currentCoroutineContext().ensureActive()
                if (nBook.name == searchKey || nBook.author == searchKey) {
                    var hasSame = false
                    equalData.forEach { pBook ->
                        currentCoroutineContext().ensureActive()
                        if (pBook.name == nBook.name && pBook.author == nBook.author) {
                            pBook.addOrigin(nBook.origin)
                            hasSame = true
                        }
                    }
                    if (!hasSame) {
                        equalData.add(nBook)
                    }
                } else if (nBook.kind?.contains(searchKey) == true) {
                    var hasSame = false
                    tagsData.forEach { pBook ->
                        currentCoroutineContext().ensureActive()
                        if (pBook.name == nBook.name && pBook.author == nBook.author) {
                            pBook.addOrigin(nBook.origin)
                            hasSame = true
                        }
                    }
                    if (!hasSame) {
                        tagsData.add(nBook)
                    }
                } else if (nBook.name.contains(searchKey) || nBook.author.contains(searchKey)) {
                    var hasSame = false
                    containsData.forEach { pBook ->
                        currentCoroutineContext().ensureActive()
                        if (pBook.name == nBook.name && pBook.author == nBook.author) {
                            pBook.addOrigin(nBook.origin)
                            hasSame = true
                        }
                    }
                    if (!hasSame) {
                        containsData.add(nBook)
                    }
                } else if (!precision) {
                    var hasSame = false
                    otherData.forEach { pBook ->
                        currentCoroutineContext().ensureActive()
                        if (pBook.name == nBook.name && pBook.author == nBook.author) {
                            pBook.addOrigin(nBook.origin)
                            hasSame = true
                        }
                    }
                    if (!hasSame) {
                        otherData.add(nBook)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            equalData.sortByDescending { it.origins.size }
            equalData.addAll(tagsData.sortedByDescending { it.origins.size })
            equalData.addAll(containsData.sortedByDescending { it.origins.size })
            if (!precision) {
                equalData.addAll(otherData)
            }
            currentCoroutineContext().ensureActive()
            searchBooks = equalData
        }
    }

    /**
     * 搜索源发放顺序。
     *
     * 关掉「错峰搜索」时完全按原来的顺序、不等待，行为与之前一致。
     */
    private fun scheduleSources(
        sources: List<BookSourcePart>
    ): List<ScheduledSource<BookSourcePart>> {
        if (!AppConfig.staggerSearch) {
            return sources.map { ScheduledSource(it, 0L, "") }
        }
        return SearchSchedule.order(
            sources = sources,
            urlOf = { it.bookSourceUrl },
            respondTimeOf = { it.respondTime },
            hostOf = NetworkUtils::getSubDomainOrNull,
        )
    }

    fun pause() {
        workingState.value = false
    }

    fun resume() {
        workingState.value = true
    }

    fun cancelSearch() {
        close()
        callBack.onSearchCancel()
    }

    fun close() {
        searchJob?.cancel()
        searchPool?.close()
        searchPool = null
        mSearchId = 0L
    }

    interface CallBack {
        fun getSearchScope(): SearchScope
        fun onSearchStart()
        fun onSearchSuccess(searchBooks: List<SearchBook>)

        /** 先铺出来的一屏本地记录，随后会被网络结果逐步替换 */
        fun onSearchPreview(searchBooks: List<SearchBook>) {}
        fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean)
        fun onSearchCancel(exception: Throwable? = null)
    }

}
