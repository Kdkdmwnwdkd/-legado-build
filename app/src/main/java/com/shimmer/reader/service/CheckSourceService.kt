package com.shimmer.reader.service

import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.script.ScriptException
import com.shimmer.reader.R
import com.shimmer.reader.base.BaseService
import com.shimmer.reader.constant.AppConst
import com.shimmer.reader.constant.AppPattern
import com.shimmer.reader.constant.BookSourceType
import com.shimmer.reader.constant.EventBus
import com.shimmer.reader.constant.IntentAction
import com.shimmer.reader.constant.NotificationId
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.Book
import com.shimmer.reader.data.entities.BookSource
import com.shimmer.reader.exception.ContentEmptyException
import com.shimmer.reader.exception.NoStackTraceException
import com.shimmer.reader.exception.TocEmptyException
import com.shimmer.reader.help.IntentData
import com.shimmer.reader.help.source.exploreKinds
import com.shimmer.reader.model.CheckSource
import com.shimmer.reader.model.CheckSourceSummary
import com.shimmer.reader.model.Debug
import com.shimmer.reader.model.webBook.WebBook
import com.shimmer.reader.ui.book.source.manage.BookSourceActivity
import com.shimmer.reader.utils.activityPendingIntent
import com.shimmer.reader.utils.onEachParallel
import com.shimmer.reader.utils.postEvent
import com.shimmer.reader.utils.servicePendingIntent
import com.shimmer.reader.utils.splitNotBlank
import com.shimmer.reader.utils.toastOnUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.mozilla.javascript.WrappedException
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors

/**
 * 校验书源
 */
class CheckSourceService : BaseService() {
    private var threadCount = checkThreadCount()
    private var searchCoroutine =
        Executors.newFixedThreadPool(threadCount).asCoroutineDispatcher()
    private var notificationMsg = appCtx.getString(R.string.service_starting)
    private var checkJob: Job? = null
    private var originSize = 0
    private var finishCount = 0

    /** 本轮校验的收尾统计，只用于结束后的提示，不参与判定。 */
    private val summary = CheckSourceSummary()

    /** 校验前每个源的开关状态，用来判断这一轮有没有把源关掉。 */
    private val enabledBefore = HashMap<String, Boolean>()

    /** 是否跑完了全程。用户中途取消时为 false，结束时不该报「完成」。 */
    private var finished = false

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdReadAloud)
            .setSmallIcon(R.drawable.ic_network_check)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.check_book_source))
            .setContentIntent(
                activityPendingIntent<BookSourceActivity>("activity")
            )
            .addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<CheckSourceService>(IntentAction.stop)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> IntentData.get<List<String>>("checkSourceSelectedIds")?.let {
                check(it)
            }

            IntentAction.resume -> upNotification()
            IntentAction.stop -> stopSelf()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        Debug.finishChecking()
        searchCoroutine.close()
        if (finished) {
            notifyFinished()
        } else {
            notificationManager.cancel(NotificationId.CheckSourceService)
        }
        postEvent(EventBus.CHECK_SOURCE_DONE, resultText())
    }

    private fun check(ids: List<String>) {
        if (checkJob?.isActive == true) {
            toastOnUi(R.string.toast_source_checking)
            return
        }
        Debug.clearCheckingCache()
        checkJob = lifecycleScope.launch(searchCoroutine) {
            flow {
                for (origin in ids) {
                    appDb.bookSourceDao.getBookSource(origin)?.let {
                        // 校验会按结果改 enabled，先记下原状态，才能算出「本轮被停用」
                        enabledBefore[it.bookSourceUrl] = it.enabled
                        emit(it)
                    }
                }
            }.onStart {
                originSize = ids.size
                finishCount = 0
                summary.reset()
                enabledBefore.clear()
                finished = false
                notificationMsg = getString(R.string.progress_show, "", 0, originSize)
                upNotification()
            }.onEachParallel(threadCount) {
                checkSource(it)
            }.onEach {
                finishCount++
                summary.record(
                    invalidGroups = it.invalidGroupList(),
                    wasEnabled = enabledBefore[it.bookSourceUrl] ?: true,
                    enabledNow = it.enabled
                )
                notificationMsg = getString(
                    R.string.progress_show,
                    it.bookSourceName,
                    finishCount,
                    originSize
                )
                upNotification()
                appDb.bookSourceDao.update(it)
            }.onCompletion {
                // 被取消时 onCompletion 收到 CancellationException，此时不算跑完
                if (it == null) finished = true
                stopSelf()
            }.collect()
        }
    }

    private suspend fun checkSource(source: BookSource) {
        kotlin.runCatching {
            withTimeout(CheckSource.timeout) {
                doCheckSource(source)
            }
        }.onSuccess {
            Debug.updateFinalMessage(
                source.bookSourceUrl,
                getString(R.string.dbg_check_success),
                success = true
            )
        }.onFailure {
            currentCoroutineContext().ensureActive()
            when (it) {
                is TimeoutCancellationException -> source.addGroup("校验超时")
                is ScriptException, is WrappedException -> source.addGroup("js失效")
                !is NoStackTraceException -> source.addGroup("网站失效")
            }
            if (CheckSource.wSourceComment) {
                source.addErrorComment(it)
            }
            // 检测失败的书源自动禁用
            source.enabled = false
            Debug.updateFinalMessage(
                source.bookSourceUrl,
                getString(R.string.dbg_check_failed, it.localizedMessage),
                success = false
            )
        }
        source.respondTime = Debug.getRespondTime(source.bookSourceUrl)
    }

    private suspend fun isDomainReachable(domain: String): Boolean {
        return kotlin.runCatching {
            withTimeout(5000) {
                val url = URI(domain.substringBefore("#"))
                val port = url.port.takeIf { it > 0 } ?: if (url.scheme.equals("https", true)) 443 else 80
                // 优先用 HTTP 检测，比 Socket 更准确反映书源可用性
                kotlin.runCatching {
                    val connection = (URL(url.toString()).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3000
                        readTimeout = 3000
                        instanceFollowRedirects = true
                        requestMethod = "HEAD"
                    }
                    connection.responseCode in 200..499
                }.getOrElse {
                    // HTTP 失败时回退到 Socket 检测
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(url.host, port), 3000)
                        true
                    }
                }
            }
        }.getOrDefault(false)
    }

    private suspend fun doCheckSource(source: BookSource) {
        Debug.startChecking(source)
        source.updateGroups {
            removeInvalidGroups()
        }
        if (CheckSource.wSourceComment) {
            source.updateGroups {
                removeErrorComment()
            }
        }
        //检测源地址可访问性
        if (CheckSource.checkDomain) {
            val domain = source.bookSourceUrl
            if (!domain.startsWith("http", ignoreCase = true)) {
                throw NoStackTraceException("源地址不是http链接")
            }
            else if (isDomainReachable(domain)) {
                source.removeGroupSafe("域名失效")
            } else {
                source.addGroupSafe("域名失效")
                throw NoStackTraceException("源地址不可访问")
            }
        }
        if (CheckSource.quickMode) {
            checkSourceBranchesFast(source)
        } else {
            checkSearchBranch(source)
            checkDiscoveryBranch(source)
        }
        val finalCheckMessage = source.getInvalidGroupNames()
        if (finalCheckMessage.isNotBlank()) {
            throw NoStackTraceException(finalCheckMessage)
        }
    }

    private suspend fun checkSourceBranchesFast(source: BookSource) = supervisorScope {
        listOf(
            async { kotlin.runCatching { checkSearchBranch(source) } },
            async { kotlin.runCatching { checkDiscoveryBranch(source) } }
        ).awaitAll().firstOrNull { it.isFailure }?.exceptionOrNull()?.let {
            throw it
        }
    }

    private suspend fun checkSearchBranch(source: BookSource) {
        //校验搜索书籍
        if (CheckSource.checkSearch) {
            val searchWord = source.getCheckKeyword(CheckSource.keyword)
            if (!source.searchUrl.isNullOrBlank()) {
                source.removeGroupSafe("搜索链接规则为空")
                val searchBooks = WebBook.searchBookAwait(
                    source,
                    searchWord,
                    shouldBreak = { it > 0 }
                )
                if (searchBooks.isEmpty()) {
                    source.addGroupSafe("搜索失效")
                } else {
                    source.removeGroupSafe("搜索失效")
                    checkBook(searchBooks.first().toBook(), source)
                }
            } else {
                source.addGroupSafe("搜索链接规则为空")
            }
        }
    }

    private suspend fun checkDiscoveryBranch(source: BookSource) {
        //校验发现书籍
        if (CheckSource.checkDiscovery && !source.exploreUrl.isNullOrBlank()) {
            val url = source.exploreKinds().firstOrNull {
                !it.url.isNullOrBlank()
            }?.url
            if (url.isNullOrBlank()) {
                source.addGroupSafe("发现规则为空")
            } else {
                source.removeGroupSafe("发现规则为空")
                val exploreBooks = WebBook.exploreBookAwait(
                    source,
                    url,
                    shouldBreak = { it > 0 }
                )
                if (exploreBooks.isEmpty()) {
                    source.addGroupSafe("发现失效")
                } else {
                    source.removeGroupSafe("发现失效")
                    checkBook(exploreBooks.first().toBook(), source, false)
                }
            }
        }
    }

    /**
     *校验书源的详情目录正文
     */
    private suspend fun checkBook(book: Book, source: BookSource, isSearchBook: Boolean = true) {
        kotlin.runCatching {
            if (!CheckSource.shouldCheckInfo()) {
                return
            }
            //校验详情
            if (book.tocUrl.isBlank()) {
                WebBook.getBookInfoAwait(source, book)
            }
            if (!CheckSource.shouldCheckCategory() || source.bookSourceType == BookSourceType.file) {
                return
            }
            //校验目录
            val toc = WebBook.getChapterListAwait(source, book).getOrThrow().asSequence()
                .filter { !(it.isVolume && it.url.startsWith(it.title)) }
                .take(2)
                .toList()
            val nextChapterUrl = toc.getOrNull(1)?.url ?: toc.first().url
            if (!CheckSource.shouldCheckContent()) {
                return
            }
            //校验正文
            WebBook.getContentAwait(
                bookSource = source,
                book = book,
                bookChapter = toc.first(),
                nextChapterUrl = nextChapterUrl,
                needSave = false
            )
        }.onFailure {
            val bookType = if (isSearchBook) "搜索" else "发现"
            when (it) {
                is ContentEmptyException -> source.addGroupSafe("${bookType}正文失效")
                is TocEmptyException -> source.addGroupSafe("${bookType}目录失效")
                else -> throw it
            }
        }.onSuccess {
            val bookType = if (isSearchBook) "搜索" else "发现"
            source.removeGroupSafe("${bookType}目录失效")
            source.removeGroupSafe("${bookType}正文失效")
        }
    }

    private inline fun BookSource.updateGroups(block: BookSource.() -> Unit) {
        synchronized(this) {
            block()
        }
    }

    private fun BookSource.addGroupSafe(group: String) {
        updateGroups {
            addGroup(group)
        }
    }

    private fun BookSource.removeGroupSafe(group: String) {
        updateGroups {
            removeGroup(group)
        }
    }

    /**
     * 本次校验的收尾文案。没跑完、或一个源都没校验到的时候返回空串，
     * 调用方据此决定要不要提示，避免把「取消」说成「完成」。
     */
    private fun resultText(): String {
        if (!finished || summary.checked == 0) return ""
        val detail = summary.failureText(
            itemFormat = { tag, count ->
                getString(R.string.check_source_done_item, tag, count)
            },
            separator = getString(R.string.check_source_done_separator),
            restFormat = { count -> getString(R.string.check_source_done_more, count) }
        )
        val brief = getString(
            R.string.check_source_done_summary,
            summary.checked,
            summary.usable,
            summary.failedSources
        )
        val failedPart = if (detail.isEmpty()) {
            ""
        } else {
            getString(R.string.check_source_done_detail, detail)
        }
        // 校验失败会自动关掉源开关，这件事必须说，否则用户会以为源还在用
        val disabledPart = if (summary.disabledSources > 0) {
            getString(R.string.check_source_done_disabled, summary.disabledSources)
        } else {
            ""
        }
        return brief + failedPart + disabledPart
    }

    /**
     * 结束后的常驻通知：收起进度条，留一条可点开的结果摘要。
     *
     * 改动前这里是在 onDestroy 里直接 cancel，用户等十几分钟只看到通知消失，
     * 至于哪些源坏了、坏在哪一段，只能自己去分组里翻。
     */
    private fun notifyFinished() {
        val text = resultText()
        if (text.isEmpty()) {
            notificationManager.cancel(NotificationId.CheckSourceService)
            return
        }
        val notification = NotificationCompat.Builder(this, AppConst.channelIdReadAloud)
            .setSmallIcon(R.drawable.ic_network_check)
            .setContentTitle(getString(R.string.check_source_done_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(activityPendingIntent<BookSourceActivity>("activity"))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        notificationManager.notify(NotificationId.CheckSourceService, notification)
    }

    /**
     * 本轮结束时仍留在源上的失效分组。
     *
     * 校验开始时 [BookSource.removeInvalidGroups] 会清空失效分组，所以这里读到的
     * 必然是这一轮加上去的，不会把上一轮的结果算进来。
     */
    private fun BookSource.invalidGroupList(): List<String> {
        return bookSourceGroup
            ?.splitNotBlank(AppPattern.splitGroupRegex)
            ?.filter { "失效" in it || it == "校验超时" }
            ?.distinct()
            ?: emptyList()
    }

    private fun upNotification() {
        notificationBuilder.setContentText(notificationMsg)
        notificationBuilder.setProgress(originSize, finishCount, false)
        postEvent(EventBus.CHECK_SOURCE, notificationMsg)
        notificationManager.notify(NotificationId.CheckSourceService, notificationBuilder.build())
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        notificationBuilder.setContentText(notificationMsg)
        notificationBuilder.setProgress(originSize, finishCount, false)
        postEvent(EventBus.CHECK_SOURCE, notificationMsg)
        startForeground(NotificationId.CheckSourceService, notificationBuilder.build())
    }

    private fun checkThreadCount(): Int {
        return CheckSource.normalizedThreadCount()
    }

}
