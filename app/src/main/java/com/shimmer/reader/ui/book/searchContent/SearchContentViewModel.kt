package com.shimmer.reader.ui.book.searchContent


import android.app.Application
import com.shimmer.reader.base.BaseViewModel
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.Book
import com.shimmer.reader.data.entities.BookChapter
import com.shimmer.reader.help.book.BookHelp
import com.shimmer.reader.help.book.ContentProcessor
import com.shimmer.reader.help.config.AppConfig
import com.shimmer.reader.model.webBook.WebBook
import com.shimmer.reader.utils.ChineseUtils
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

class SearchContentViewModel(application: Application) : BaseViewModel(application) {
    companion object {
        var replaceEnabled: Boolean = false
        var regexReplace: Boolean = false

        /** 单次联网补搜最多抓多少章，抓完还有剩余就再给一次入口，避免一口气把整本书请求完 */
        const val MAX_NETWORK_BATCH = 200

        /** 连续这么多章都取不到正文就认为书源在拦，主动停下来 */
        const val MAX_FAIL_STREAK = 8
    }
    var bookUrl: String = ""
    var book: Book? = null
    private var contentProcessor: ContentProcessor? = null
    var lastQuery: String = ""
    var searchResultCounts = 0
    val cacheChapterNames = hashSetOf<String>()
    val searchResultList: MutableList<SearchResult> = mutableListOf()

    /** 全书章节总数，用来告诉用户这次到底搜了多大范围 */
    var totalChapters: Int = 0

    /** 已经搜过（含联网补搜）的章节数 */
    var searchedChapters: Int = 0

    /** 没有缓存、等着联网补搜的章节 */
    val pendingChapters: MutableList<BookChapter> = mutableListOf()

    /** 联网补搜里取不到正文的章节数 */
    var networkFailedChapters: Int = 0

    fun initBook(bookUrl: String, success: () -> Unit) {
        this.bookUrl = bookUrl
        execute {
            book = appDb.bookDao.getBook(bookUrl)
            book?.let {
                contentProcessor = ContentProcessor.get(it.name, it.origin)
            }
        }.onSuccess {
            success.invoke()
        }
    }

    fun resetSearchState() {
        searchResultCounts = 0
        totalChapters = 0
        searchedChapters = 0
        networkFailedChapters = 0
        pendingChapters.clear()
        searchResultList.clear()
    }

    /**
     * 搜一章。rawContent 为空时读缓存，联网补搜直接把取到的正文传进来。
     */
    suspend fun searchChapter(
        query: String,
        chapter: BookChapter,
        rawContent: String? = null
    ): List<SearchResult> {
        val searchResultsWithinChapter: MutableList<SearchResult> = mutableListOf()
        val book = book ?: return searchResultsWithinChapter
        val chapterContent = rawContent
            ?: BookHelp.getContent(book, chapter)
            ?: return searchResultsWithinChapter
        currentCoroutineContext().ensureActive()
        chapter.title = when (AppConfig.chineseConverterType) {
            1 -> ChineseUtils.t2s(chapter.title)
            2 -> ChineseUtils.s2t(chapter.title)
            else -> chapter.title
        }
        currentCoroutineContext().ensureActive()
        val mContent = contentProcessor!!.getContent(
            book, chapter, chapterContent, useReplace = replaceEnabled
        ).toString()
        val positions = searchPosition(mContent, query)
        positions.forEachIndexed { index, position ->
            currentCoroutineContext().ensureActive()
            val construct = getResultAndQueryIndex(mContent, position, query)
            val result = SearchResult(
                resultCountWithinChapter = index,
                resultText = construct.second,
                chapterTitle = chapter.title,
                query = query,
                chapterIndex = chapter.index,
                queryIndexInResult = construct.first,
                queryIndexInChapter = position,
                isRegex = regexReplace
            )
            searchResultsWithinChapter.add(result)
        }
        searchResultCounts += searchResultsWithinChapter.size
        return searchResultsWithinChapter
    }

    /**
     * 联网取一章正文，只用于搜索。不落缓存：搜一遍整本书就把正文全存下来，磁盘和书源都受不了。
     * 取不到返回 null，由调用方计数并跳过。
     */
    suspend fun fetchChapterContent(chapter: BookChapter): String? {
        val book = book ?: return null
        val bookSource = appDb.bookSourceDao.getBookSource(book.origin) ?: return null
        val content = kotlin.runCatching {
            withTimeoutOrNull(30_000) {
                WebBook.getContentAwait(bookSource, book, chapter, needSave = false)
            }
        }.getOrNull()
        return content?.takeIf { it.isNotBlank() }
    }

    private suspend fun searchPosition(content: String, pattern: String): List<Int> {
        val position: MutableList<Int> = mutableListOf()
        if (regexReplace) { // 正则表达式搜索
            try {
                Regex(pattern).findAll(content).forEach { match ->
                    currentCoroutineContext().ensureActive()
                    position.add(match.range.first)
                }
            } catch (e: Exception) {
                return position
            }
        } else {
            var index = content.indexOf(pattern)
            while (index >= 0) {
                currentCoroutineContext().ensureActive()
                position.add(index)
                index = content.indexOf(pattern, index + pattern.length)
            }
        }
        return position
    }

    private fun getResultAndQueryIndex(
        content: String,
        queryIndexInContent: Int,
        query: String
    ): Pair<Int, String> {
        // 左右移动20个字符，构建关键词周边文字，在搜索结果里显示
        // 判断段落，只在关键词所在段落内分割
        // 利用标点符号分割完整的句
        // length和设置结合，自由调整周边文字长度
        val length = 20
        var po1 = queryIndexInContent - length
        var po2 = queryIndexInContent + query.length + length
        if (po1 < 0) {
            po1 = 0
        }
        if (po2 > content.length) {
            po2 = content.length
        }
        val queryIndexInResult = queryIndexInContent - po1
        val newText = content.substring(po1, po2)
        return queryIndexInResult to newText
    }

}
