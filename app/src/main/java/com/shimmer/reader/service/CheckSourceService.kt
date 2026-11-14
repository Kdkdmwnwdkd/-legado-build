package com.shimmer.reader.service

import android.content.Intent
import android.os.SystemClock
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
import com.shimmer.reader.model.CheckSourceRunStore
import com.shimmer.reader.model.CheckSourceSummary
import com.shimmer.reader.model.CheckStage
import com.shimmer.reader.model.Debug
import com.shimmer.reader.model.webBook.WebBook
import com.shimmer.reader.ui.book.source.manage.CheckSourceProgressActivity
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
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executors
import javax.net.ssl.SSLException

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

    /** 本轮被自动停用的源上，已经加入书架的书名。收尾时告诉用户哪些书会打不开。 */
    private var affectedBookNames: List<String> = emptyList()

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdReadAloud)
            .setSmallIcon(R.drawable.ic_network_check)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.check_book_source))
            .setContentIntent(
                activityPendingIntent<CheckSourceProgressActivity>("activity")
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
        // 服务被系统回收等异常路径可能没走到 onCompletion，这里兜一次，
        // 否则进度页会永远停在「正在校验」
        CheckSourceRunStore.finish(cancelled = true, autoDisabled = summary.disabledSources)
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
            // 进度页要在第一个源开跑之前就把整批列出来，所以先取一遍轻量的
            // BookSourcePart；真正的 BookSource 仍然逐个查，避免一次把几千个源读进内存
            val parts = ids.mapNotNull { appDb.bookSourceDao.getBookSourcePart(it) }
            CheckSourceRunStore.begin(parts)
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
                affectedBookNames = emptyList()
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
                    enabledNow = it.enabled,
                    origin = it.bookSourceUrl
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
                if (finished && summary.disabledOrigins.isNotEmpty()) {
                    affectedBookNames = runCatching {
                        appDb.bookDao.namesByOrigins(summary.disabledOrigins)
                    }.getOrElse { emptyList() }
                }
                CheckSourceRunStore.finish(
                    cancelled = it != null,
                    autoDisabled = summary.disabledSources
                )
                stopSelf()
            }.collect()
        }
    }

    private suspend fun checkSource(source: BookSource) {
        val startedAt = SystemClock.elapsedRealtime()
        CheckSourceRunStore.markRunning(
            origin = source.bookSourceUrl,
            name = source.bookSourceName,
            type = source.bookSourceType
        )
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
            CheckSourceRunStore.markPassed(
                origin = source.bookSourceUrl,
                durationMs = SystemClock.elapsedRealtime() - startedAt
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
            CheckSourceRunStore.markFailed(
                origin = source.bookSourceUrl,
                reason = it.failureReason(),
                durationMs = SystemClock.elapsedRealtime() - startedAt
            )
        }
        source.respondTime = Debug.getRespondTime(source.bookSourceUrl)
    }

    /**
     * 给用户看的一句失败原因。
     *
     * 校验的失败信息有三种来源：我们自己打的分组名（「搜索失效」）、没有 message 的
     * 超时与脚本异常、以及从 HTTP 客户端冒出来的英文网络错误。第三种直接摆出来会是
     * 一整行 IP 和端口，对用户没有意义，这里换成一句人话；实在归不了类的才原样保留。
     */
    private fun Throwable.failureReason(): String {
        // 分组名本身已经是结论，优先用它
        if (this is NoStackTraceException) {
            localizedMessage?.takeIf { it.isNotBlank() }?.let {
                return it.replace(", ", "、")
            }
        }
        when (this) {
            is TimeoutCancellationException -> return "校验超时"
            is ScriptException, is WrappedException -> return "js失效"
        }
        networkReason()?.let { return it }
        val message = localizedMessage?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
        return message.take(80)
    }

    /** 顺着 cause 链找网络异常，取最具体的那一个。 */
    private fun Throwable.networkReason(): String? {
        var current: Throwable? = this
        val visited = HashSet<Throwable>()
        while (current != null && visited.add(current)) {
            when (current) {
                is UnknownHostException -> return "域名无法解析"
                is SocketTimeoutException -> return "连接源站超时"
                is SSLException -> return "证书校验失败"
                is ConnectException -> return "无法连接源站"
                is SocketException -> return "网络连接中断"
            }
            current = current.cause ?: (current as? WrappedException)?.wrappedException
        }
        return null
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
            CheckSourceRunStore.markStage(source.bookSourceUrl, CheckStage.DOMAIN)
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
            CheckSourceRunStore.markStage(source.bookSourceUrl, CheckStage.SEARCH)
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
            CheckSourceRunStore.markStage(source.bookSourceUrl, CheckStage.DISCOVERY)
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
                CheckSourceRunStore.markStage(source.bookSourceUrl, CheckStage.INFO)
                WebBook.getBookInfoAwait(source, book)
            }
            if (!CheckSource.shouldCheckCategory() || source.bookSourceType == BookSourceType.file) {
                return
            }
            //校验目录
            CheckSourceRunStore.markStage(source.bookSourceUrl, CheckStage.TOC)
            val toc = WebBook.getChapterListAwait(source, book).getOrThrow().asSequence()
                .filter { !(it.isVolume && it.url.startsWith(it.title)) }
                .take(2)
                .toList()
            val nextChapterUrl = toc.getOrNull(1)?.url ?: toc.first().url
            if (!CheckSource.shouldCheckContent()) {
                return
            }
            //校验正文
            CheckSourceRunStore.markStage(source.bookSourceUrl, CheckStage.CONTENT)
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
        // 停用会连带影响书架里正在读的书，这一点比「停用了几个源」更该让用户看到
        val affectedPart = if (affectedBookNames.isNotEmpty()) {
            getString(
                R.string.check_source_done_affected,
                affectedBookNames.size,
                affectedBookNames.take(2).joinToString("、")
            )
        } else {
            ""
        }
        return brief + failedPart + disabledPart + affectedPart
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
            .setContentIntent(activityPendingIntent<CheckSourceProgressActivity>("activity"))
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
