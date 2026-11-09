package com.shimmer.reader.model

import android.annotation.SuppressLint
import android.util.Log
import com.shimmer.reader.BuildConfig
import com.shimmer.reader.constant.AppPattern
import com.shimmer.reader.data.entities.*
import com.shimmer.reader.help.book.isWebFile
import com.shimmer.reader.help.coroutine.CompositeCoroutine
import com.shimmer.reader.help.source.sortUrls
import com.shimmer.reader.model.rss.Rss
import com.shimmer.reader.model.webBook.WebBook
import com.shimmer.reader.utils.HtmlFormatter
import com.shimmer.reader.utils.NetworkUtils
import com.shimmer.reader.utils.isAbsUrl
import com.shimmer.reader.utils.stackTraceStr
import kotlinx.coroutines.CoroutineScope
import java.text.SimpleDateFormat
import java.util.*
import com.shimmer.reader.R
import splitties.init.appCtx

object Debug {
    var callback: Callback? = null
    private var debugSource: String? = null
    private val tasks: CompositeCoroutine = CompositeCoroutine()
    val debugMessageMap = HashMap<String, String>()
    private val debugTimeMap = HashMap<String, Long>()
    private val debugFinalStateMap = HashMap<String, Boolean>()
    var isChecking: Boolean = false

    @SuppressLint("ConstantLocale")
    private val debugTimeFormat = SimpleDateFormat("[mm:ss.SSS]", Locale.getDefault())
    private var startTime: Long = System.currentTimeMillis()

    @Synchronized
    fun log(
        sourceUrl: String?,
        msg: String = "",
        print: Boolean = true,
        isHtml: Boolean = false,
        showTime: Boolean = true,
        state: Int = 1
    ) {
        if (BuildConfig.DEBUG) {
            Log.d("sourceDebug", msg)
        }
        //调试信息始终要执行
        callback?.let {
            if ((debugSource != sourceUrl || !print)) return
            var printMsg = msg
            if (isHtml) {
                printMsg = HtmlFormatter.format(msg)
            }
            if (showTime) {
                val time = debugTimeFormat.format(Date(System.currentTimeMillis() - startTime))
                printMsg = "$time $printMsg"
            }
            it.printLog(state, printMsg)
        }
        if (isChecking && sourceUrl != null && (msg).length < 30) {
            var printMsg = msg
            if (isHtml) {
                printMsg = HtmlFormatter.format(msg)
            }
            if (showTime && debugTimeMap[sourceUrl] != null) {
                val time =
                    debugTimeFormat.format(Date(System.currentTimeMillis() - debugTimeMap[sourceUrl]!!))
                printMsg = printMsg.replace(AppPattern.debugMessageSymbolRegex, "")

                debugMessageMap[sourceUrl] = "$time $printMsg"
            }
        }
    }

    @Synchronized
    fun log(msg: String?) {
        log(debugSource, msg ?: "", true)
    }

    fun cancelDebug(destroy: Boolean = false) {
        tasks.clear()

        if (destroy) {
            debugSource = null
            callback = null
        }
    }

    suspend fun <T> withDebugSource(sourceUrl: String?, callback: Callback?, block: suspend () -> T): T {
        val oldSource = debugSource
        val oldCallback = this.callback
        debugSource = sourceUrl
        this.callback = callback
        startTime = System.currentTimeMillis()
        return try {
            block()
        } finally {
            debugSource = oldSource
            this.callback = oldCallback
        }
    }

    fun startChecking(source: BookSource) {
        isChecking = true
        debugTimeMap[source.bookSourceUrl] = System.currentTimeMillis()
        debugMessageMap[source.bookSourceUrl] = "${debugTimeFormat.format(Date(0))} ${appCtx.getString(R.string.dbg_start_checking)}"
    }

    fun finishChecking() {
        isChecking = false
    }

    fun clearCheckingCache() {
        debugMessageMap.clear()
        debugTimeMap.clear()
        debugFinalStateMap.clear()
    }

    fun getRespondTime(sourceUrl: String): Long {
        return debugTimeMap[sourceUrl] ?: CheckSource.timeout
    }

    fun updateFinalMessage(sourceUrl: String, state: String, success: Boolean) {
        if (debugTimeMap[sourceUrl] != null && debugMessageMap[sourceUrl] != null) {
            val spendingTime = System.currentTimeMillis() - debugTimeMap[sourceUrl]!!
            debugTimeMap[sourceUrl] = if (success) spendingTime else CheckSource.timeout + spendingTime
            debugFinalStateMap[sourceUrl] = success
            val printTime = debugTimeFormat.format(Date(spendingTime))
            debugMessageMap[sourceUrl] = "$printTime $state"
        }
    }

    fun hasFinalMessage(sourceUrl: String): Boolean = debugFinalStateMap.containsKey(sourceUrl)

    suspend fun startDebug(scope: CoroutineScope, rssSource: RssSource) {
        cancelDebug()
        debugSource = rssSource.sourceUrl
        log(debugSource, appCtx.getString(R.string.dbg_start_parse))
        val sort = rssSource.sortUrls().first()
        Rss.getArticles(scope, sort.first, sort.second, rssSource, 1)
            .onSuccess {
                if (it.first.isEmpty()) {
                    log(debugSource, appCtx.getString(R.string.dbg_list_empty))
                    log(debugSource, appCtx.getString(R.string.dbg_parse_done), state = 1000)
                } else {
                    val ruleContent = rssSource.ruleContent
                    if (!rssSource.ruleArticles.isNullOrBlank() && rssSource.ruleDescription.isNullOrBlank()) {
                        log(debugSource, appCtx.getString(R.string.dbg_list_parse_done))
                        log(debugSource, showTime = false)
                        if (ruleContent.isNullOrEmpty()) {
                            log(debugSource, appCtx.getString(R.string.dbg_content_rule_empty), state = 1000)
                        } else {
                            rssContentDebug(scope, it.first[0], ruleContent, rssSource)
                        }
                    } else {
                        log(debugSource, appCtx.getString(R.string.dbg_desc_rule_skip))
                        log(debugSource, appCtx.getString(R.string.dbg_parse_done), state = 1000)
                    }
                }
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
    }

    fun startDebug(scope: CoroutineScope, rssSource: RssSource, key: String) {
        cancelDebug()
        debugSource = rssSource.sourceUrl
        startTime = System.currentTimeMillis()
        when {
            key.contains("::") -> {
                val name = key.substringBefore("::")
                val url = key.substringAfter("::")
                log(debugSource, appCtx.getString(R.string.dbg_explore_category, url))
                log(debugSource, appCtx.getString(R.string.dbg_start_parse_category))
                sortDebug(scope, rssSource, name, url)
            }

            key.isAbsUrl() -> {
                val ruleContent = rssSource.ruleContent
                if (!rssSource.ruleArticles.isNullOrBlank() && rssSource.ruleDescription.isNullOrBlank()) {
                    if (ruleContent.isNullOrEmpty()) {
                        log(debugSource, appCtx.getString(R.string.dbg_content_rule_empty), state = 1000)
                    } else {
                        val rssArticle = RssArticle()
                        rssArticle.origin = rssSource.sourceUrl
                        rssArticle.link = key
                        log(debugSource, appCtx.getString(R.string.dbg_visit_content, key))
                        rssContentDebug(scope, rssArticle, ruleContent, rssSource)
                    }
                } else {
                    log(debugSource, appCtx.getString(R.string.dbg_desc_rule_skip))
                    log(debugSource, appCtx.getString(R.string.dbg_parse_done), state = 1000)
                }
            }

            else -> {
                val searchUrl = rssSource.searchUrl
                if (searchUrl.isNullOrEmpty()) {
                    log(debugSource, appCtx.getString(R.string.dbg_search_url_empty), state = -1)
                    return
                }
                log(debugSource, appCtx.getString(R.string.dbg_start_search_keyword, key))
                log(debugSource, appCtx.getString(R.string.dbg_start_parse_search))
                sortDebug(scope, rssSource, "搜索", searchUrl, key)
            }
        }
    }

    private fun sortDebug(scope: CoroutineScope, rssSource: RssSource, name: String, url: String, key: String? = null) {
        Rss.getArticles(scope, name, url, rssSource, 1, key)
            .onSuccess {
                if (it.first.isEmpty()) {
                    log(debugSource, appCtx.getString(R.string.dbg_list_empty))
                    log(debugSource, appCtx.getString(R.string.dbg_parse_done), state = 1000)
                } else {
                    val ruleContent = rssSource.ruleContent
                    if (!rssSource.ruleArticles.isNullOrBlank() && rssSource.ruleDescription.isNullOrBlank()) {
                        log(debugSource, appCtx.getString(R.string.dbg_list_parse_done))
                        log(debugSource, showTime = false)
                        if (ruleContent.isNullOrEmpty()) {
                            log(debugSource, appCtx.getString(R.string.dbg_content_rule_empty), state = 1000)
                        } else {
                            rssContentDebug(scope, it.first[0], ruleContent, rssSource)
                        }
                    } else {
                        log(debugSource, appCtx.getString(R.string.dbg_desc_rule_skip))
                        log(debugSource, appCtx.getString(R.string.dbg_parse_done), state = 1000)
                    }
                }
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
    }

    private fun rssContentDebug(
        scope: CoroutineScope,
        rssArticle: RssArticle,
        ruleContent: String,
        rssSource: RssSource
    ) {
        log(debugSource, appCtx.getString(R.string.dbg_start_parse_content))
        Rss.getContent(scope, rssArticle, ruleContent, rssSource)
            .onSuccess {
                log(debugSource, it)
                log(debugSource, appCtx.getString(R.string.dbg_content_parse_done), state = 1000)
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
    }

    fun startDebug(scope: CoroutineScope, bookSource: BookSource, key: String) {
        cancelDebug()
        debugSource = bookSource.bookSourceUrl
        startTime = System.currentTimeMillis()
        when {
            key.isAbsUrl() -> {
                val book = Book()
                book.origin = bookSource.bookSourceUrl
                book.bookUrl = key
                log(debugSource, appCtx.getString(R.string.dbg_visit_detail, key))
                infoDebug(scope, bookSource, book)
            }

            key.contains("::") -> {
                val url = key.substringAfter("::")
                log(debugSource, appCtx.getString(R.string.dbg_visit_explore, url))
                exploreDebug(scope, bookSource, url)
            }

            key.startsWith("++") -> {
                val url = key.substring(2)
                val book = Book()
                book.origin = bookSource.bookSourceUrl
                book.tocUrl = url
                log(debugSource, appCtx.getString(R.string.dbg_visit_toc, url))
                tocDebug(scope, bookSource, book)
            }

            key.startsWith("--") -> {
                val url = key.substring(2)
                val book = Book()
                book.origin = bookSource.bookSourceUrl
                log(debugSource, appCtx.getString(R.string.dbg_visit_text, url))
                val chapter = BookChapter()
                chapter.title = appCtx.getString(R.string.dbg_chapter_title)
                chapter.url = url
                contentDebug(scope, bookSource, book, chapter, null)
            }

            else -> {
                log(debugSource, appCtx.getString(R.string.dbg_start_search_keyword, key))
                searchDebug(scope, bookSource, key)
            }
        }
    }

    private fun exploreDebug(scope: CoroutineScope, bookSource: BookSource, url: String) {
        log(debugSource, appCtx.getString(R.string.dbg_start_parse_explore))
        val explore = WebBook.exploreBook(scope, bookSource, url, 1)
            .onSuccess { exploreBooks ->
                if (exploreBooks.isNotEmpty()) {
                    log(debugSource, appCtx.getString(R.string.dbg_explore_parse_done))
                    log(debugSource, showTime = false)
                    infoDebug(scope, bookSource, exploreBooks[0].toBook())
                } else {
                    log(debugSource, appCtx.getString(R.string.dbg_no_books), state = -1)
                }
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
        tasks.add(explore)
    }

    private fun searchDebug(scope: CoroutineScope, bookSource: BookSource, key: String) {
        log(debugSource, appCtx.getString(R.string.dbg_start_parse_search))
        val search = WebBook.searchBook(scope, bookSource, key, 1)
            .onSuccess { searchBooks ->
                if (searchBooks.isNotEmpty()) {
                    log(debugSource, appCtx.getString(R.string.dbg_search_parse_done))
                    log(debugSource, showTime = false)
                    infoDebug(scope, bookSource, searchBooks[0].toBook())
                } else {
                    log(debugSource, appCtx.getString(R.string.dbg_no_books), state = -1)
                }
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
        tasks.add(search)
    }

    private fun infoDebug(scope: CoroutineScope, bookSource: BookSource, book: Book) {
        if (book.tocUrl.isNotBlank()) {
            log(debugSource, appCtx.getString(R.string.dbg_toc_link_got))
            log(debugSource, showTime = false)
            tocDebug(scope, bookSource, book)
            return
        }
        log(debugSource, appCtx.getString(R.string.dbg_start_parse_detail))
        val info = WebBook.getBookInfo(scope, bookSource, book)
            .onSuccess {
                log(debugSource, appCtx.getString(R.string.dbg_detail_parse_done))
                log(debugSource, showTime = false)
                if (!book.isWebFile) {
                    tocDebug(scope, bookSource, book)
                } else {
                    log(debugSource, appCtx.getString(R.string.dbg_web_file_skip_toc), state = 1000)
                }
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
        tasks.add(info)
    }

    private fun tocDebug(scope: CoroutineScope, bookSource: BookSource, book: Book) {
        log(debugSource, appCtx.getString(R.string.dbg_start_parse_toc))
        val chapterList = WebBook.getChapterList(scope, bookSource, book)
            .onSuccess { chapters ->
                log(debugSource, appCtx.getString(R.string.dbg_toc_parse_done))
                log(debugSource, showTime = false)
                val toc = chapters.filter { !(it.isVolume && it.url.startsWith(it.title)) }
                if (toc.isEmpty()) {
                    log(debugSource, appCtx.getString(R.string.dbg_no_chapters))
                    return@onSuccess
                }
                val nextChapterUrl = toc.getOrNull(1)?.url ?: toc.first().url
                contentDebug(scope, bookSource, book, toc.first(), nextChapterUrl)
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
        tasks.add(chapterList)
    }

    private fun contentDebug(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        nextChapterUrl: String?
    ) {
        log(debugSource, appCtx.getString(R.string.dbg_start_parse_text))
        val content = WebBook.getContent(
            scope = scope,
            bookSource = bookSource,
            book = book,
            bookChapter = bookChapter,
            nextChapterUrl = nextChapterUrl,
            needSave = false
        ).onSuccess {
            log(debugSource, appCtx.getString(R.string.dbg_text_parse_done), state = 1000)
        }.onError {
            log(debugSource, it.stackTraceStr, state = -1)
        }
        tasks.add(content)
    }

    interface Callback {
        fun printLog(state: Int, msg: String)
    }
}
