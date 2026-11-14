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
import com.shimmer.reader.help.source.ScheduledSource
import com.shimmer.reader.help.source.SearchSchedule
import com.shimmer.reader.ui.book.search.SearchScope
import com.shimmer.reader.utils.NetworkUtils
import com.shimmer.reader.utils.getPrefBoolean
import com.shimmer.reader.utils.mapParallelSafe
import kotlinx.coroutines.CoroutineScope
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
            }.mapParallelSafe(threadCount) {
                withTimeout(SOURCE_TIMEOUT_MS) {
                    WebBook.searchBookAwait(
                        it, searchKey, searchPage,
                        filter = { name, author, kind ->
                            !precision || name.contains(searchKey) ||
                                    author.contains(searchKey) ||
                                    kind?.contains(searchKey) == true
                        },
                        connectTimeoutMs = CONNECT_TIMEOUT_MS,
                        callTimeoutMs = CALL_TIMEOUT_MS
                    )
                }
            }.onEach { items ->
                for (book in items) {
                    book.releaseHtmlData()
                }
                hasMore = hasMore || items.isNotEmpty()
                appDb.searchBookDao.insert(*items.toTypedArray())
                mergeItems(items, precision)
                currentCoroutineContext().ensureActive()
                callBack.onSearchSuccess(searchBooks)
            }.onCompletion {
                if (it == null) callBack.onSearchFinish(searchBooks.isEmpty(), hasMore)
            }.catch {
                AppLog.put("书源搜索出错\n${it.localizedMessage}", it)
                callBack.onSearchCancel(it)
            }.collect()
        }
    }

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
        fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean)
        fun onSearchCancel(exception: Throwable? = null)
    }

}
