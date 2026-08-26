@file:Suppress("DEPRECATION")

package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.PowerManager
import android.os.Looper
import android.provider.Settings
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.annotation.CallSuper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.data.appDb
import io.legado.app.help.MediaHelp
import io.legado.app.help.CoverDisplayResolver
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.ai.AiReadAloudBgmService
import io.legado.app.help.ai.AiReadAloudRoleService
import io.legado.app.help.ai.AiReadAloudRoleState
import io.legado.app.help.book.characterBookKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.readaloud.ReadAloudPlaybackState
import io.legado.app.help.readaloud.ReadAloudProgressState
import io.legado.app.help.readaloud.ReadAloudSpeakerLoudnessManager
import io.legado.app.help.readaloud.ReadAloudSpeechPlanItem
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.readaloud.ReadAloudSpeechPlanner
import io.legado.app.help.readaloud.bgm.ReadAloudBgmPlayer
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.receiver.MediaButtonReceiver
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.ReadAloudAppCapsuleHost
import io.legado.app.ui.book.read.ReadAloudFloatingWindowLayout
import io.legado.app.ui.book.read.ReadAloudPlayerPanel
import io.legado.app.ui.book.read.ReadAloudSystemFloatingWindow
import io.legado.app.ui.book.read.page.entities.ReadAloudCue
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.buildReadAloudCues
import io.legado.app.ui.book.read.page.entities.indexForChapterPosition
import io.legado.app.utils.LogUtils
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.broadcastPendingIntent
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeSharedPreferences
import io.legado.app.utils.postEvent
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.audioManager
import splitties.systemservices.notificationManager
import splitties.systemservices.powerManager
import splitties.systemservices.telephonyManager
import splitties.systemservices.wifiManager
import java.util.concurrent.atomic.AtomicLong

/**
 * 朗读服务
 */
abstract class BaseReadAloudService : BaseService(),
    AudioManager.OnAudioFocusChangeListener {

    companion object {
        @JvmStatic
        var isRun = false
            private set

        @JvmStatic
        var pause = true
            private set

        @JvmStatic
        var timeMinute: Int = 0
            private set

        fun isPlay(): Boolean {
            return isRun && !pause
        }

        private const val TAG = "BaseReadAloudService"
        private const val READ_FROM_POSITION_TIMEOUT_MILLIS = 15_000L

        private var suppressNextStopEvent = false

    }

    private val useWakeLock = appCtx.getPrefBoolean(PreferKey.readAloudWakeLock, false)
    private val wakeLock by lazy {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "legado:ReadAloudService")
            .apply {
                this.setReferenceCounted(false)
            }
    }
    private val wifiLock by lazy {
        @Suppress("DEPRECATION")
        wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "legado:AudioPlayService")
            ?.apply {
                setReferenceCounted(false)
            }
    }
    private val bgmPlayer: ReadAloudBgmPlayer by lazy {
        ReadAloudBgmPlayer(this, lifecycleScope)
    }
    private val mFocusRequest: AudioFocusRequestCompat by lazy {
        MediaHelp.buildAudioFocusRequestCompat(this)
    }
    private val mediaSessionCompat by lazy {
        MediaSessionCompat(this, "readAloud")
    }
    private val phoneStateListener by lazy {
        ReadAloudPhoneStateListener()
    }
    internal var contentList = emptyList<String>()
    internal var readAloudCues = emptyList<ReadAloudCue>()
    internal var speechRoutes = emptyList<SpeechRoute?>()
    internal var speechItems = emptyList<ReadAloudSpeechPlanItem>()
    internal var readAloudPlanKey = ""
    internal var nowSpeak: Int = 0
    internal var readAloudNumber: Int = 0
    internal var textChapter: TextChapter? = null
    internal var pageIndex = 0
    @Volatile
    private var activeSessionId = 0L
    private val sessionSeed = AtomicLong(0L)
    @Volatile
    private var playbackBookUrl = ""
    @Volatile
    private var playbackChapterIndex = -1
    @Volatile
    private var playbackChapterUrl = ""
    protected val currentReadAloudSessionId: Long
        get() = activeSessionId
    private var needResumeOnAudioFocusGain = false
    private var needResumeOnCallStateIdle = false
    private var registeredPhoneStateListener = false
    private var dsJob: Job? = null
    private val nextRolePrewarmLock = Any()
    private val nextRolePrewarmJobs = linkedMapOf<String, Job>()
    private var rolePlaybackBlocked = false
    private var blockedPageIndex = 0
    private var blockedStartPos = 0
    private var upNotificationJob: Coroutine<*>? = null
    @Volatile
    private var floatingPlaybackState = ReadAloudPlaybackState()
    private var floatingWindow: ReadAloudSystemFloatingWindow? = null
    private var floatingWindowPermissionRequested = false
    private var readAloudPanelActive = false
    private var floatingChapterPreviewKey = ""
    private var floatingChapterPreview = emptyList<ReadAloudPlayerPanel.ChapterPreviewUi>()
    private var floatingTextCueKey = ""
    private var floatingTextCues = emptyList<ReadAloudPlayerPanel.TextCueUi>()
    private var skipDestroyProgressUpload = false
    private var cover: Bitmap =
        BitmapFactory.decodeResource(appCtx.resources, R.drawable.icon_read_book)
    var pageChanged = false
    private var toLast = false
    var paragraphStartPos = 0
    var readAloudByPage = false
        private set

    private fun beginReadAloudSession(bookUrl: String?, chapter: TextChapter?): Long {
        val sessionId = sessionSeed.incrementAndGet()
        activeSessionId = sessionId
        playbackBookUrl = bookUrl.orEmpty()
        playbackChapterIndex = chapter?.chapter?.index ?: -1
        playbackChapterUrl = chapter?.chapter?.url.orEmpty()
        return sessionId
    }

    private fun isActiveSession(
        sessionId: Long,
        bookUrl: String?,
        chapter: TextChapter?
    ): Boolean {
        return sessionId == activeSessionId &&
                playbackBookUrl == bookUrl.orEmpty() &&
                playbackChapterIndex == (chapter?.chapter?.index ?: -1) &&
                playbackChapterUrl == chapter?.chapter?.url.orEmpty()
    }

    protected fun isCurrentReadAloudSession(sessionId: Long): Boolean =
        sessionId == activeSessionId

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                pauseReadAloud()
            }
        }
    }

    internal fun syncReadAloudPositionToCue() {
        val cue = readAloudCues.getOrNull(nowSpeak)
        if (cue != null) {
            readAloudNumber = cue.chapterPosition
            pageIndex = cue.pageIndex
        }
        paragraphStartPos = 0
    }

    internal fun moveToNextCue(): Boolean {
        val nextIndex = findReadableCueIndex(nowSpeak + 1, 1)
        if (nextIndex >= 0) {
            syncToCueIndex(nextIndex)
            return true
        }
        return false
    }

    internal fun moveToPrevCue(): Boolean {
        val prevIndex = findReadableCueIndex(nowSpeak - 1, -1)
        if (prevIndex >= 0) {
            syncToCueIndex(prevIndex)
            return true
        }
        return false
    }

    internal fun syncToCueIndex(cueIndex: Int): Boolean {
        if (cueIndex !in contentList.indices) {
            return false
        }
        nowSpeak = cueIndex
        syncReadAloudPositionToCue()
        if (contentList.size - nowSpeak <= 3) {
            queueNextChapterRoleCache()
        }
        return true
    }

    private fun findReadableCueIndex(startIndex: Int, direction: Int): Int {
        var index = startIndex
        while (index in contentList.indices) {
            if (!contentList[index].matches(AppPattern.notReadAloudRegex)) {
                return index
            }
            index += direction
        }
        return -1
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        isRun = true
        pause = false
        readAloudPanelActive = ReadAloudAppCapsuleHost.isReadBookPanelVisible()
        observeLiveBus()
        initMediaSession()
        initBroadcastReceiver()
        initPhoneStateListener()
        floatingWindow = ReadAloudSystemFloatingWindow(
            context = this,
            lifecycleOwner = this,
            onPlayPause = {
                if (pause) ReadAloud.resume(this) else ReadAloud.pause(this)
            },
            onCueSelect = { cueIndex, chapterPosition, expectedChapterIndex ->
                ReadAloud.moveToCue(
                    context = this,
                    cueIndex = cueIndex,
                    chapterPosition = chapterPosition,
                    expectedChapterIndex = expectedChapterIndex,
                    play = true
                )
            },
            onChapterSelect = { chapterIndex ->
                ReadAloud.selectChapter(this, chapterIndex, continuePlayback = isPlay())
            },
            onExpand = ::openReadAloudPanelFromFloatingWindow,
            onClose = { ReadAloud.stop(this) }
        )
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        setTimer(AppConfig.ttsTimer)
        if (AppConfig.ttsTimer > 0) {
            toastOnUi("朗读定时 ${AppConfig.ttsTimer} 分钟")
        }
        execute {
            ImageLoader
                .loadBitmap(this@BaseReadAloudService, ReadBook.book?.getDisplayCover())
                .submit()
                .get()
        }.onSuccess {
            if (it.width > 16 && it.height > 16) {
                cover = it
                upReadAloudNotification()
            }
        }
    }

    fun observeLiveBus() {
        observeEvent<Bundle>(EventBus.READ_ALOUD_PLAY) {
            val play = it.getBoolean("play")
            val pageIndex = it.getInt("pageIndex")
            val startPos = it.getInt("startPos")
            newReadAloud(play, pageIndex, startPos)
        }
        observeEvent<Bundle>(EventBus.READ_ALOUD_CONFIG_CHANGED) {
            when (it.getString(EventBus.READ_ALOUD_CONFIG_SCOPE)) {
                EventBus.READ_ALOUD_CONFIG_SCOPE_AUDIO -> {
                    bgmPlayer.refreshConfig()
                    onReadAloudAudioConfigChanged()
                }
                EventBus.READ_ALOUD_CONFIG_SCOPE_ENGINE -> restartOrRebuildReadAloud()
                EventBus.READ_ALOUD_CONFIG_SCOPE_SPEECH -> rebuildCurrentReadAloud()
            }
        }
        observeEvent<Boolean>(EventBus.READ_ALOUD_PANEL_ACTIVE) { active ->
            readAloudPanelActive = active
            updateFloatingWindowSuppression()
        }
        observeEvent<Boolean>(EventBus.APP_FOREGROUND_CHANGED) {
            updateFloatingWindowSuppression()
        }
        observeEvent<String>(EventBus.RECREATE) {
            floatingWindow?.refreshTheme()
            syncReadAloudFloatingWindow(requestPermission = false)
        }
        observeSharedPreferences { _, key ->
            when (key) {
                PreferKey.ignoreAudioFocus,
                PreferKey.pauseReadAloudWhilePhoneCalls -> {
                    initPhoneStateListener()
                }
                PreferKey.showReadAloudFloatingBall -> {
                    syncReadAloudFloatingWindow(requestPermission = true)
                }
            }
        }
    }

    protected open fun onReadAloudAudioConfigChanged() = Unit

    protected fun speakerLoudnessInfo(cueIndex: Int = nowSpeak): ReadAloudSpeakerLoudnessManager.LoudnessInfo {
        return ReadAloudSpeakerLoudnessManager.infoFor(
            speechItems.getOrNull(cueIndex),
            speechRoutes.getOrNull(cueIndex)
        )
    }

    private fun restartOrRebuildReadAloud() {
        val targetClass = ReadAloud.resolveReadAloudClass()
        if (targetClass == this::class.java) {
            rebuildCurrentReadAloud()
            return
        }
        val (targetPageIndex, targetStartPos) = currentReadAloudStart() ?: return
        val shouldPlay = !pause
        playStop()
        postReadAloudPlaybackPhase(
            ReadAloudPlaybackState.PHASE_PREPARING,
            message = "刷新朗读引擎",
            playing = false,
            buffering = true
        )
        ReadAloud.refreshReadAloudClass()
        ReadAloud.play(appCtx, play = shouldPlay, pageIndex = targetPageIndex, startPos = targetStartPos)
        suppressNextStopEvent = true
        stopSelf()
    }

    private fun rebuildCurrentReadAloud() {
        val (targetPageIndex, targetStartPos) = currentReadAloudStart() ?: return
        val shouldPlay = !pause
        playStop()
        newReadAloud(shouldPlay, targetPageIndex, targetStartPos)
    }

    private fun currentReadAloudStart(): Pair<Int, Int>? {
        val chapter = textChapter ?: return null
        val targetPageIndex = pageIndex
        val targetStartPos = (readAloudNumber - chapter.getReadLength(targetPageIndex) + paragraphStartPos)
            .coerceAtLeast(0)
        return targetPageIndex to targetStartPos
    }

    override fun onDestroy() {
        floatingWindow?.dispose()
        floatingWindow = null
        super.onDestroy()
        if (useWakeLock) {
            if (wakeLock.isHeld) wakeLock.release()
            wifiLock?.let { if (it.isHeld) it.release() }
        }
        isRun = false
        pause = true
        abandonFocus()
        unregisterReceiver(broadcastReceiver)
        postReadAloudPlaybackPhase(ReadAloudPlaybackState.PHASE_STOPPED)
        bgmPlayer.release()
        if (suppressNextStopEvent) {
            suppressNextStopEvent = false
        } else {
            postEvent(EventBus.ALOUD_STATE, Status.STOP)
        }
        notificationManager.cancel(NotificationId.ReadAloudService)
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_STOPPED)
        mediaSessionCompat.release()
        synchronized(nextRolePrewarmLock) {
            nextRolePrewarmJobs.values.forEach { it.cancel() }
            nextRolePrewarmJobs.clear()
        }
        if (!skipDestroyProgressUpload) {
            ReadBook.uploadProgress()
        }
        unregisterPhoneStateListener(phoneStateListener)
        upNotificationJob?.invokeOnCompletion {
            notificationManager.cancel(NotificationId.ReadAloudService)
        }
    }

    override fun shouldStopOnTaskRemoved(): Boolean {
        if (isRun) {
            LogUtils.d("BaseReadAloudService", "keep read aloud service after task removed")
            return false
        }
        return true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stopping = intent?.action == IntentAction.stop
        when (intent?.action) {
            IntentAction.play -> newReadAloud(
                intent.getBooleanExtra("play", true),
                intent.getIntExtra("pageIndex", ReadBook.durPageIndex),
                intent.getIntExtra("startPos", 0)
            )

            IntentAction.pause -> pauseReadAloud()
            IntentAction.resume -> resumeReadAloud()
            IntentAction.upTtsSpeechRate -> upSpeechRate(true)
            IntentAction.prevParagraph -> prevP()
            IntentAction.nextParagraph -> nextP()
            IntentAction.moveTo -> moveToCue(
                cueIndex = intent.getIntExtra("cueIndex", -1),
                chapterPosition = intent.getIntExtra("chapterPosition", -1),
                expectedChapterIndex = intent.getIntExtra("expectedChapterIndex", -1),
                play = intent.getBooleanExtra("play", isPlay())
            )
            IntentAction.playFromPosition -> playFromPosition(
                bookUrl = intent.getStringExtra("bookUrl").orEmpty(),
                chapterIndex = intent.getIntExtra("chapterIndex", -1),
                chapterUrl = intent.getStringExtra("chapterUrl").orEmpty(),
                chapterPosition = intent.getIntExtra("chapterPosition", -1)
            )
            IntentAction.prev -> prevChapter(
                continuePlayback = intent.getBooleanExtra("continuePlayback", true)
            )
            IntentAction.next -> nextChapter(
                continuePlayback = intent.getBooleanExtra("continuePlayback", true)
            )
            IntentAction.selectChapter -> selectChapter(
                chapterIndex = intent.getIntExtra("chapterIndex", -1),
                continuePlayback = intent.getBooleanExtra("continuePlayback", true)
            )
            IntentAction.addTimer -> addTimer()
            IntentAction.setTimer -> setTimer(intent.getIntExtra("minute", 0))
            IntentAction.stop -> {
                skipDestroyProgressUpload =
                    intent.getStringExtra(IntentAction.stopReason) == IntentAction.stopReasonBookSwitch
                stopSelf()
            }
        }
        if (stopping) {
            floatingWindow?.remove()
        } else {
            syncReadAloudFloatingWindow(requestPermission = true)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        floatingWindow?.onConfigurationChanged()
        syncReadAloudFloatingWindow(requestPermission = false)
    }

    private fun newReadAloud(play: Boolean, pageIndex: Int, startPos: Int) {
        val book = ReadBook.book
        val targetChapter = ReadBook.curTextChapter
        val bookUrl = book?.bookUrl
        val characterBookKey = book?.characterBookKey()
        val sessionId = beginReadAloudSession(bookUrl, targetChapter)
        execute(executeContext = IO) {
            rolePlaybackBlocked = false
            blockedPageIndex = pageIndex
            blockedStartPos = startPos
            this@BaseReadAloudService.pageIndex = pageIndex
            textChapter = targetChapter
            val textChapter = targetChapter ?: run {
                launch(Main) {
                    markReadAloudStartUnavailable(pageIndex, startPos, "章节加载中")
                }
                return@execute
            }
            if (!textChapter.isCompleted) {
                launch(Main) {
                    markReadAloudStartUnavailable(pageIndex, startPos, "章节加载中")
                }
                return@execute
            }
            readAloudByPage = getPrefBoolean(PreferKey.readAloudByPage)
            val baseCues = textChapter.buildReadAloudCues(readAloudByPage)
            postReadAloudPlaybackPhase(
                if (play) ReadAloudPlaybackState.PHASE_PREPARING else ReadAloudPlaybackState.PHASE_PAUSED,
                message = if (play) "准备朗读" else ""
            )
            val roleContentList = baseCues.map { it.text }
            var roleCacheKey: String? = null
            if (AppConfig.aiReadAloudRoleEnabled) {
                val roleResult = AiReadAloudRoleService.ensurePlayableCache(
                    ReadBook.book,
                    textChapter,
                    roleContentList
                )
                if (roleResult.status != AiReadAloudRoleState.STATUS_SUCCESS || roleResult.segmentCount <= 0) {
                    launch(Main) {
                        markReadAloudStartBlocked(pageIndex, startPos, roleResult.error.ifBlank { roleResult.message })
                    }
                    return@execute
                }
                roleCacheKey = roleResult.cacheKey
                if (!isActiveSession(sessionId, bookUrl, textChapter) ||
                    !isStillCurrentChapter(bookUrl, textChapter)
                ) {
                    return@execute
                }
                queueNextChapterRoleCache()
            }
            val speechPlan = ReadAloudSpeechPlanner.build(
                bookUrl = characterBookKey,
                chapter = textChapter,
                baseCues = baseCues,
                multiRoleEnabled = AppConfig.aiReadAloudRoleEnabled,
                roleCacheKey = roleCacheKey
            )
            if (!isActiveSession(sessionId, bookUrl, textChapter) ||
                !isStillCurrentChapter(bookUrl, textChapter)
            ) {
                return@execute
            }
            rolePlaybackBlocked = false
            readAloudCues = speechPlan.cues
            speechRoutes = speechPlan.routes
            speechItems = speechPlan.items
            readAloudPlanKey = ReadAloudSpeechPlanner.planKey(
                bookUrl = characterBookKey,
                chapter = textChapter,
                cues = speechPlan.cues,
                roleCacheKey = roleCacheKey
            )
            contentList = readAloudCues.map { it.text }
            if (AppConfig.aiReadAloudBgmEnabled) {
                launch(IO) {
                    AiReadAloudBgmService.ensureChapterAssignments(
                        ReadBook.book,
                        textChapter,
                        speechPlan.cues
                    )
                }
            }
            queueNextChapterRoleCache()
            if (contentList.isNotEmpty()) {
                val startChapterPosition = textChapter.getReadLength(pageIndex) + startPos
                nowSpeak = if (toLast) {
                    contentList.lastIndex
                } else {
                    readAloudCues.indexForChapterPosition(startChapterPosition)
                }.coerceIn(0, contentList.lastIndex)
                syncReadAloudPositionToCue()
                paragraphStartPos = (startChapterPosition - readAloudNumber)
                    .coerceIn(0, contentList.getOrNull(nowSpeak)?.length ?: 0)
                this@BaseReadAloudService.pageIndex =
                    textChapter.getPageIndexByCharIndex(startChapterPosition)
                    .coerceAtLeast(0)
            }
            if (toLast) {
                toLast = false
            }
            launch(Main) {
                if (!isActiveSession(sessionId, bookUrl, textChapter)) return@launch
                if (play) play() else pageChanged = true
            }
        }.onError {
            AppLog.put("启动朗读出错\n${it.localizedMessage}", it, true)
        }
    }

    private fun isStillCurrentChapter(bookUrl: String?, chapter: TextChapter): Boolean {
        val current = ReadBook.curTextChapter ?: return false
        return current.chapter.index == chapter.chapter.index &&
                current.chapter.url == chapter.chapter.url &&
                ReadBook.book?.bookUrl == bookUrl
    }

    private fun markReadAloudStartUnavailable(pageIndex: Int, startPos: Int, message: String) {
        rolePlaybackBlocked = false
        blockedPageIndex = pageIndex
        blockedStartPos = startPos
        readAloudCues = emptyList()
        speechRoutes = emptyList()
        speechItems = emptyList()
        contentList = emptyList()
        readAloudPlanKey = ""
        pause = true
        pageChanged = true
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PAUSED)
        postReadAloudPlaybackPhase(
            ReadAloudPlaybackState.PHASE_PAUSED,
            message = message,
            playing = false
        )
        postEvent(EventBus.ALOUD_STATE, Status.PAUSE)
    }

    private fun markReadAloudStartBlocked(pageIndex: Int, startPos: Int, message: String = "") {
        rolePlaybackBlocked = true
        blockedPageIndex = pageIndex
        blockedStartPos = startPos
        readAloudCues = emptyList()
        speechRoutes = emptyList()
        speechItems = emptyList()
        contentList = emptyList()
        readAloudPlanKey = ""
        pause = true
        pageChanged = true
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PAUSED)
        postReadAloudPlaybackPhase(
            ReadAloudPlaybackState.PHASE_PAUSED,
            message = message.ifBlank { "等待角色分配" }
        )
        postEvent(EventBus.ALOUD_STATE, Status.PAUSE)
    }

    private fun prepareChapterTransition(continuePlayback: Boolean = true) {
        playStop()
        rolePlaybackBlocked = false
        nowSpeak = 0
        readAloudNumber = 0
        paragraphStartPos = 0
        pageIndex = 0
        readAloudCues = emptyList()
        speechRoutes = emptyList()
        speechItems = emptyList()
        contentList = emptyList()
        readAloudPlanKey = ""
        pause = !continuePlayback
        needResumeOnAudioFocusGain = false
        needResumeOnCallStateIdle = false
        upReadAloudNotification()
        upMediaSessionPlaybackState(
            if (continuePlayback) PlaybackStateCompat.STATE_PLAYING
            else PlaybackStateCompat.STATE_PAUSED
        )
        postEvent(
            EventBus.ALOUD_STATE,
            if (continuePlayback) Status.PLAY else Status.PAUSE
        )
    }

    private fun queueNextChapterRoleCache() {
        if (!AppConfig.aiReadAloudRoleEnabled && !AppConfig.aiReadAloudBgmEnabled) return
        val book = ReadBook.book ?: return
        val nextIndex = ReadBook.durChapterIndex + 1
        if (nextIndex >= ReadBook.chapterSize) return
        val key = "${book.bookUrl}:$nextIndex"
        synchronized(nextRolePrewarmLock) {
            nextRolePrewarmJobs.entries.removeAll { !it.value.isActive }
            if (nextRolePrewarmJobs[key]?.isActive == true) return
        }
        val job = lifecycleScope.launch(IO) {
            runCatching {
                prewarmNextChapterRoleCache(book.bookUrl, nextIndex)
            }.onFailure {
                if (it !is CancellationException) {
                    AppLog.putDebug("下一章角色预分配失败: ${it.localizedMessage ?: it.javaClass.simpleName}")
                }
            }
        }
        job.invokeOnCompletion {
            synchronized(nextRolePrewarmLock) {
                if (nextRolePrewarmJobs[key] === job) {
                    nextRolePrewarmJobs.remove(key)
                }
            }
        }
        synchronized(nextRolePrewarmLock) {
            nextRolePrewarmJobs[key] = job
            while (nextRolePrewarmJobs.size > 3) {
                val firstKey = nextRolePrewarmJobs.keys.firstOrNull() ?: break
                nextRolePrewarmJobs.remove(firstKey)?.cancel()
            }
        }
    }

    private suspend fun prewarmNextChapterRoleCache(bookUrl: String, chapterIndex: Int) {
        if (!AppConfig.aiReadAloudRoleEnabled && !AppConfig.aiReadAloudBgmEnabled) return
        var nextChapter: TextChapter? = null
        repeat(6) { attempt ->
            if (ReadBook.book?.bookUrl != bookUrl || ReadBook.durChapterIndex + 1 != chapterIndex) return
            nextChapter = ReadBook.nextTextChapter?.takeIf { it.isCompleted }
            if (nextChapter != null) return@repeat
            if (attempt < 5) delay(1200)
        }
        val readyChapter = nextChapter ?: return
        val cues = readyChapter.buildReadAloudCues(readAloudByPage)
        if (cues.isEmpty()) return
        val roleResult = if (AppConfig.aiReadAloudRoleEnabled) {
            AiReadAloudRoleService.ensureCache(
                book = ReadBook.book,
                textChapter = readyChapter,
                paragraphs = cues.map { it.text },
                stage = AiReadAloudRoleState.STAGE_NEXT
            )
        } else {
            null
        }
        if (AppConfig.aiReadAloudBgmEnabled) {
            val speechPlan = ReadAloudSpeechPlanner.build(
                bookUrl = ReadBook.book?.characterBookKey(),
                chapter = readyChapter,
                baseCues = cues,
                multiRoleEnabled = AppConfig.aiReadAloudRoleEnabled,
                roleCacheKey = roleResult?.cacheKey
            )
            AiReadAloudBgmService.ensureChapterAssignments(
                ReadBook.book,
                readyChapter,
                speechPlan.cues
            )
        }
    }

    @SuppressLint("WakelockTimeout")
    open fun play() {
        if (useWakeLock) {
            if (!wakeLock.isHeld) wakeLock.acquire()
            wifiLock?.let { if (!it.isHeld) it.acquire() }
        }
        isRun = true
        pause = false
        needResumeOnAudioFocusGain = false
        needResumeOnCallStateIdle = false
        upReadAloudNotification()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        postEvent(EventBus.ALOUD_STATE, Status.PLAY)
    }

    abstract fun playStop()

    @CallSuper
    open fun pauseReadAloud(abandonFocus: Boolean = true) {
        if (useWakeLock) {
            if (wakeLock.isHeld) wakeLock.release()
            wifiLock?.let { if (it.isHeld) it.release() }
        }
        pause = true
        if (abandonFocus) {
            abandonFocus()
        }
        upReadAloudNotification()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PAUSED)
        postReadAloudPlaybackPhase(ReadAloudPlaybackState.PHASE_PAUSED)
        postEvent(EventBus.ALOUD_STATE, Status.PAUSE)
        ReadBook.uploadProgress()
        doDs()
    }

    @SuppressLint("WakelockTimeout")
    @CallSuper
    open fun resumeReadAloud() {
        if (resumeBlockedReadAloudIfNeeded()) return
        resumeReadAloudInternal()
    }

    protected fun resumeBlockedReadAloudIfNeeded(): Boolean {
        if (!rolePlaybackBlocked) return false
        newReadAloud(true, blockedPageIndex, blockedStartPos)
        return true
    }

    private fun resumeReadAloudInternal() {
        pause = false
        needResumeOnAudioFocusGain = false
        needResumeOnCallStateIdle = false
        upReadAloudNotification()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        postReadAloudPlaybackPhase(ReadAloudPlaybackState.PHASE_PREPARING)
        postEvent(EventBus.ALOUD_STATE, Status.PLAY)
    }

    abstract fun upSpeechRate(reset: Boolean = false)

    fun upTtsProgress(progress: Int) {
        postEvent(
            EventBus.READ_ALOUD_PROGRESS,
            ReadAloudProgressState(
                bookUrl = playbackBookUrl,
                chapterIndex = playbackChapterIndex,
                chapterUrl = playbackChapterUrl,
                chapterPosition = progress.coerceAtLeast(0),
                cueIndex = nowSpeak,
                sessionId = activeSessionId,
                planKey = readAloudPlanKey
            )
        )
    }

    protected fun postReadAloudPlaybackPhase(
        phase: String,
        cueIndex: Int = nowSpeak,
        message: String = "",
        playing: Boolean? = null,
        buffering: Boolean = phase == ReadAloudPlaybackState.PHASE_BUFFERING
    ) {
        val isPlaying = playing ?: when (phase) {
            ReadAloudPlaybackState.PHASE_PLAYING -> isPlay()
            ReadAloudPlaybackState.PHASE_PAUSED,
            ReadAloudPlaybackState.PHASE_STOPPED,
            ReadAloudPlaybackState.PHASE_ERROR -> false
            else -> null
        }
        val cue = readAloudCues.getOrNull(cueIndex)
        val state = ReadAloudPlaybackState(
            phase = phase,
            bookUrl = playbackBookUrl,
            chapterIndex = playbackChapterIndex,
            chapterUrl = playbackChapterUrl,
            cueIndex = cueIndex,
            cueCount = readAloudCues.size,
            cueChapterPosition = cue?.chapterPosition ?: -1,
            cueKey = cue?.key.orEmpty(),
            cueText = cue?.text.orEmpty(),
            planKey = readAloudPlanKey,
            message = message,
            playing = isPlaying,
            buffering = buffering,
            serviceRunning = isRun,
            sessionId = activeSessionId
        )
        floatingPlaybackState = state
        bgmPlayer.onPlaybackState(state)
        postEvent(
            EventBus.READ_ALOUD_PLAYBACK_STATE,
            state
        )
        lifecycleScope.launch(Main.immediate) {
            syncReadAloudFloatingWindow(requestPermission = false)
        }
    }

    private fun syncReadAloudFloatingWindow(requestPermission: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            lifecycleScope.launch(Main) {
                syncReadAloudFloatingWindow(requestPermission)
            }
            return
        }
        val window = floatingWindow ?: return
        if (!AppConfig.showReadAloudFloatingBall) {
            window.remove()
            return
        }
        if (!canDrawReadAloudFloatingWindow()) {
            window.remove()
            if (requestPermission) requestReadAloudFloatingWindowPermission()
            return
        }
        floatingWindowPermissionRequested = false
        val book = ReadBook.book
        val coverDisplay = book?.let { CoverDisplayResolver.resolve(it) }
        val playbackState = floatingPlaybackState
        val currentCueIndex = playbackState.cueIndex.coerceIn(
            0,
            readAloudCues.lastIndex.coerceAtLeast(0)
        )
        val chapterIndex = playbackState.chapterIndex
            .takeIf { it >= 0 }
            ?: ReadBook.durChapterIndex
        val chapterCount = ReadBook.chapterSize.coerceAtLeast(chapterIndex + 1)
        val chapterKey = "${book?.bookUrl.orEmpty()}:$chapterIndex"
        val textCues = buildFloatingTextCues(chapterKey, chapterIndex)
        val currentCue = readAloudCues.getOrNull(currentCueIndex)
        window.setSuppressed(shouldSuppressFloatingWindow())
        window.showOrUpdate(
            ReadAloudPlayerPanel.PlayerUiState(
                bookName = book?.name.orEmpty(),
                author = book?.author.orEmpty(),
                coverUrl = coverDisplay?.path,
                sourceOrigin = coverDisplay?.sourceOrigin,
                coverForcePath = coverDisplay?.forcePath ?: false,
                coverAllowNameOverlay = coverDisplay?.allowNameOverlay,
                chapterTitle = ReadBook.curTextChapter?.chapter?.title.orEmpty(),
                chapterIndexText = "${chapterIndex + 1}/$chapterCount",
                chapterIndex = chapterIndex,
                chapterCount = chapterCount,
                chapterPreview = buildFloatingChapterPreview(
                    bookUrl = book?.bookUrl,
                    currentIndex = chapterIndex,
                    chapterCount = chapterCount
                ),
                playing = playbackState.playing ?: isPlay(),
                playbackPhase = playbackState.phase,
                playbackBusy = playbackState.busy && !pause,
                serviceRunning = isRun && book != null,
                paragraphText = currentCue?.text.orEmpty(),
                paragraphIndex = currentCueIndex + 1,
                paragraphCount = textCues.size,
                textCues = textCues,
                currentCueIndex = currentCueIndex,
                chapterKey = chapterKey,
                paragraphKey = currentCue?.key.orEmpty(),
                foregroundActive = true,
                expanded = false,
                readMenuVisible = false
            )
        )
    }

    private fun updateFloatingWindowSuppression() {
        val suppressed = shouldSuppressFloatingWindow()
        LogUtils.d(
            TAG,
            "floating suppression panel=$readAloudPanelActive " +
                    "appForeground=${LifecycleHelp.isAppForeground()} suppressed=$suppressed"
        )
        floatingWindow?.setSuppressed(suppressed)
    }

    private fun shouldSuppressFloatingWindow(): Boolean {
        return ReadAloudFloatingWindowLayout.shouldSuppress(
            panelVisible = readAloudPanelActive,
            appForeground = LifecycleHelp.isAppForeground()
        )
    }

    private fun buildFloatingTextCues(
        chapterKey: String,
        chapterIndex: Int
    ): List<ReadAloudPlayerPanel.TextCueUi> {
        val first = readAloudCues.firstOrNull()
        val last = readAloudCues.lastOrNull()
        val key = listOf(
            chapterKey,
            activeSessionId.toString(),
            readAloudPlanKey,
            readAloudCues.size.toString(),
            first?.key.orEmpty(),
            last?.key.orEmpty()
        ).joinToString("|")
        if (floatingTextCueKey == key) return floatingTextCues
        floatingTextCues = readAloudCues.mapIndexed { index, cue ->
            ReadAloudPlayerPanel.TextCueUi(
                index = index + 1,
                text = cue.text,
                current = false,
                key = "$chapterKey:${cue.chapterPosition}:${cue.key}",
                sequence = chapterIndex * 100_000 + index,
                chapterPosition = cue.chapterPosition
            )
        }
        floatingTextCueKey = key
        return floatingTextCues
    }

    private fun buildFloatingChapterPreview(
        bookUrl: String?,
        currentIndex: Int,
        chapterCount: Int
    ): List<ReadAloudPlayerPanel.ChapterPreviewUi> {
        if (bookUrl.isNullOrBlank() || chapterCount <= 0) return emptyList()
        val key = "$bookUrl:$currentIndex:$chapterCount"
        if (floatingChapterPreviewKey == key) return floatingChapterPreview
        val start = (currentIndex - 8).coerceAtLeast(0)
        val end = (currentIndex + 9).coerceAtMost(chapterCount - 1)
        floatingChapterPreview = runCatching {
            appDb.bookChapterDao.getChapterList(bookUrl, start, end).map { chapter ->
                ReadAloudPlayerPanel.ChapterPreviewUi(
                    index = chapter.index,
                    title = chapter.title.ifBlank { "未命名章节" },
                    indexText = if (chapter.isVolume) "卷" else "${chapter.index + 1}/$chapterCount",
                    current = chapter.index == currentIndex,
                    volume = chapter.isVolume,
                    key = "$bookUrl:${chapter.index}:${chapter.title}"
                )
            }
        }.getOrDefault(emptyList())
        floatingChapterPreviewKey = key
        return floatingChapterPreview
    }

    private fun requestReadAloudFloatingWindowPermission() {
        if (floatingWindowPermissionRequested) return
        floatingWindowPermissionRequested = true
        PermissionsCompat.Builder()
            .addPermissions(Permissions.SYSTEM_ALERT_WINDOW)
            .rationale(R.string.read_aloud_floating_ball_permission_rationale)
            .onGranted {
                floatingWindowPermissionRequested = false
                if (lifecycleScope.isActive) {
                    syncReadAloudFloatingWindow(requestPermission = false)
                }
            }
            .onDenied {
                floatingWindowPermissionRequested = false
                AppConfig.showReadAloudFloatingBall = false
            }
            .onError {
                floatingWindowPermissionRequested = false
                AppConfig.showReadAloudFloatingBall = false
            }
            .request()
    }

    private fun canDrawReadAloudFloatingWindow(): Boolean {
        return android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(this)
    }

    private fun openReadAloudPanelFromFloatingWindow() {
        val book = ReadBook.book ?: return
        runCatching {
            ReadAloudAppCapsuleHost.requestReadAloudPanelOpen(book.bookUrl)
            startActivityForBook(book) {
                putExtra("openReadAloudPanel", true)
            }
        }.onFailure {
            LogUtils.d(TAG, "open read aloud panel from floating window failed: ${it.localizedMessage}")
        }
    }

    protected open fun moveToCue(
        cueIndex: Int,
        chapterPosition: Int,
        expectedChapterIndex: Int,
        play: Boolean
    ) {
        if (expectedChapterIndex >= 0 && expectedChapterIndex != ReadBook.durChapterIndex) return
        val targetIndex = when {
            chapterPosition >= 0 -> readAloudCues.indexForChapterPosition(chapterPosition)
            cueIndex in contentList.indices -> cueIndex
            else -> -1
        }
        if (targetIndex !in contentList.indices) return
        playStop()
        syncToCueIndex(targetIndex)
        upTtsProgress(readAloudNumber + 1)
        if (play) {
            play()
        } else {
            pageChanged = true
            postReadAloudPlaybackPhase(ReadAloudPlaybackState.PHASE_PAUSED, playing = false)
        }
    }

    private fun playFromPosition(
        bookUrl: String,
        chapterIndex: Int,
        chapterUrl: String,
        chapterPosition: Int
    ) {
        val book = ReadBook.book ?: return
        if (book.bookUrl != bookUrl ||
            chapterIndex !in 0 until ReadBook.chapterSize ||
            chapterPosition < 0
        ) {
            if (contentList.isEmpty()) stopSelf()
            return
        }

        // Invalidate any asynchronous preparation from the old playback session before
        // changing the global ReadBook chapter state.
        val pendingSessionId = beginReadAloudSession(bookUrl, null)
        prepareChapterTransition(continuePlayback = true)

        fun failPendingRequest(message: String) {
            if (activeSessionId != pendingSessionId) return
            markReadAloudStartUnavailable(0, 0, message)
        }

        fun startWhenReady() {
            if (activeSessionId != pendingSessionId) return
            val latestBook = ReadBook.book ?: return
            val chapter = ReadBook.curTextChapter ?: return
            if (latestBook.bookUrl != bookUrl ||
                chapter.chapter.index != chapterIndex ||
                chapter.chapter.url != chapterUrl ||
                !chapter.isCompleted
            ) {
                failPendingRequest("无法加载选中的章节")
                return
            }
            val lastPage = chapter.lastPage
            if (lastPage == null || chapter.pageSize <= 0) {
                failPendingRequest("选中的章节没有可朗读内容")
                return
            }
            val chapterEndExclusive =
                (lastPage.chapterPosition + lastPage.text.length).coerceAtLeast(0)
            if (chapterEndExclusive <= 0) {
                failPendingRequest("选中的章节没有可朗读内容")
                return
            }
            val safePosition = chapterPosition.coerceIn(0, chapterEndExclusive - 1)
            val targetPageIndex = chapter.getPageIndexByCharIndex(safePosition)
                .coerceIn(0, chapter.lastIndex)
            val targetPageStart = chapter.getReadLength(targetPageIndex)
            val targetStartPos = (safePosition - targetPageStart).coerceAtLeast(0)
            ReadBook.durChapterPos = safePosition
            newReadAloud(
                play = true,
                pageIndex = targetPageIndex,
                startPos = targetStartPos
            )
        }

        lifecycleScope.launch {
            delay(READ_FROM_POSITION_TIMEOUT_MILLIS)
            if (activeSessionId == pendingSessionId) {
                failPendingRequest("从选中位置开始朗读超时")
            }
        }

        val currentChapter = ReadBook.curTextChapter
        if (ReadBook.durChapterIndex == chapterIndex &&
            currentChapter?.chapter?.url == chapterUrl &&
            currentChapter.isCompleted
        ) {
            startWhenReady()
        } else {
            ReadBook.openChapter(
                index = chapterIndex,
                durChapterPos = chapterPosition,
                upContent = true,
                success = ::startWhenReady
            )
        }
    }

    private fun prevP() {
        if (nowSpeak > 0) {
            playStop()
            if (!moveToPrevCue()) {
                toLast = true
                ReadBook.moveToPrevChapter(true, fromReadAloud = true)
                return
            }
            upTtsProgress(readAloudNumber + 1)
            play()
        } else {
            toLast = true
            ReadBook.moveToPrevChapter(true, fromReadAloud = true)
        }
    }

    private fun nextP() {
        if (nowSpeak < contentList.size - 1) {
            playStop()
            if (!moveToNextCue()) {
                nextChapter()
                return
            }
            upTtsProgress(readAloudNumber + 1)
            play()
        } else {
            nextChapter()
        }
    }

    private fun setTimer(minute: Int) {
        timeMinute = minute
        doDs()
    }

    private fun addTimer() {
        if (timeMinute == 180) {
            timeMinute = 0
        } else {
            timeMinute += 10
            if (timeMinute > 180) timeMinute = 180
        }
        doDs()
    }

    /**
     * 定时
     */
    @Synchronized
    private fun doDs() {
        postEvent(EventBus.READ_ALOUD_DS, timeMinute)
        upReadAloudNotification()
        dsJob?.cancel()
        dsJob = lifecycleScope.launch {
            while (isActive) {
                delay(60000)
                if (!pause) {
                    if (timeMinute >= 0) {
                        timeMinute--
                    }
                    if (timeMinute == 0) {
                        ReadAloud.stop(this@BaseReadAloudService)
                        postEvent(EventBus.READ_ALOUD_DS, timeMinute)
                        break
                    }
                }
                postEvent(EventBus.READ_ALOUD_DS, timeMinute)
                upReadAloudNotification()
            }
        }
    }

    /**
     * 请求音频焦点
     * @return 音频焦点
     */
    fun requestFocus(): Boolean {
        if (AppConfig.ignoreAudioFocus) {
            return true
        }
        val requestFocus = MediaHelp.requestFocus(mFocusRequest)
        if (!requestFocus) {
            pauseReadAloud(false)
            toastOnUi("未获取到音频焦点")
        }
        return requestFocus
    }

    /**
     * 放弃音频焦点
     */
    private fun abandonFocus() {
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, mFocusRequest)
    }

    /**
     * 更新媒体状态
     */
    private fun upMediaSessionPlaybackState(state: Int) {
        mediaSessionCompat.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(MediaHelp.MEDIA_SESSION_ACTIONS)
                .setState(state, nowSpeak.toLong(), 1f)
                // 为系统媒体控件添加定时按钮
                .addCustomAction(
                    "ACTION_ADD_TIMER",
                    getString(R.string.set_timer),
                    R.drawable.ic_time_add_24dp
                )
                .build()
        )
    }

    /**
     * 初始化MediaSession, 注册多媒体按钮
     */
    @SuppressLint("UnspecifiedImmutableFlag")
    private fun initMediaSession() {
        mediaSessionCompat.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        mediaSessionCompat.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                resumeReadAloud()
            }

            override fun onPause() {
                pauseReadAloud()
            }

            override fun onSkipToNext() {
                if (getPrefBoolean("mediaButtonPerNext", false)) {
                    nextChapter()
                } else {
                    nextP()
                }
            }

            override fun onSkipToPrevious() {
                if (getPrefBoolean("mediaButtonPerNext", false)) {
                    prevChapter()
                } else {
                    prevP()
                }
            }

            override fun onStop() {
                stopSelf()
            }

            override fun onCustomAction(action: String, extras: Bundle?) {
                if (action == "ACTION_ADD_TIMER") addTimer()
            }

            override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                return MediaButtonReceiver.handleIntent(
                    this@BaseReadAloudService, mediaButtonEvent
                )
            }
        })
        mediaSessionCompat.setMediaButtonReceiver(
            broadcastPendingIntent<MediaButtonReceiver>(Intent.ACTION_MEDIA_BUTTON)
        )
        mediaSessionCompat.isActive = true
    }

    private fun upMediaMetadata() {
        var nTitle: String = when {
            pause -> getString(R.string.read_aloud_pause)
            timeMinute > 0 -> getString(
                R.string.read_aloud_timer,
                timeMinute
            )

            else -> getString(R.string.read_aloud_t)
        }
        nTitle += ": ${ReadBook.book?.name}"
        val metadata = MediaMetadataCompat.Builder()
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, cover)
            .putText(MediaMetadataCompat.METADATA_KEY_TITLE, readAloudChapterTitle() ?: "null")
            .putText(MediaMetadataCompat.METADATA_KEY_ARTIST, nTitle)
            .putText(MediaMetadataCompat.METADATA_KEY_ALBUM, ReadBook.book?.author ?: "null")
//            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, nowSpeak.toLong())
            .build()
        mediaSessionCompat.setMetadata(metadata)
    }

    private fun readAloudChapterTitle(): String? {
        return ReadBook.curTextChapter?.chapter?.title?.takeIf { it.isNotBlank() }
    }

    /**
     * 注册多媒体按钮监听
     */
    private fun initBroadcastReceiver() {
        val intentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(broadcastReceiver, intentFilter)
    }

    /**
     * 音频焦点变化
     */
    override fun onAudioFocusChange(focusChange: Int) {
        if (AppConfig.ignoreAudioFocus) {
            AppLog.put("忽略音频焦点处理(TTS)")
            return
        }
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (needResumeOnAudioFocusGain) {
                    AppLog.put("音频焦点获得,继续朗读")
                    resumeReadAloud()
                } else {
                    AppLog.put("音频焦点获得")
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                AppLog.put("音频焦点丢失,暂停朗读")
                pauseReadAloud()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                AppLog.put("音频焦点暂时丢失并会很快再次获得,暂停朗读")
                if (!pause) {
                    needResumeOnAudioFocusGain = true
                    pauseReadAloud(false)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 短暂丢失焦点，这种情况是被其他应用申请了短暂的焦点希望其他声音能压低音量（或者关闭声音）凸显这个声音（比如短信提示音），
                AppLog.put("音频焦点短暂丢失,不做处理")
            }
        }
    }

    private fun upReadAloudNotification() {
        upNotificationJob = execute {
            try {
                upMediaMetadata()
                val notification = createNotification()
                notificationManager.notify(NotificationId.ReadAloudService, notification.build())
            } catch (e: Exception) {
                AppLog.put("创建朗读通知出错,${e.localizedMessage}", e, true)
            }
        }
    }

    private fun createNotification(): NotificationCompat.Builder {
        var nTitle: String = when {
            pause -> getString(R.string.read_aloud_pause)
            timeMinute > 0 -> getString(
                R.string.read_aloud_timer,
                timeMinute
            )

            else -> getString(R.string.read_aloud_t)
        }
        nTitle += ": ${ReadBook.book?.name}"
        var nSubtitle = readAloudChapterTitle()
        if (nSubtitle.isNullOrBlank())
            nSubtitle = getString(R.string.read_aloud_s)
        val builder = NotificationCompat
            .Builder(this, AppConst.channelIdReadAloud)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setSmallIcon(R.drawable.ic_volume_up)
            .setSubText(getString(R.string.read_aloud))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(nTitle)
            .setContentText(nSubtitle)
            .setContentIntent(
                activityPendingIntent<ReadBookActivity>("activity")
            )
            .setVibrate(null)
            .setSound(null)
            .setLights(0, 0, 0)
        builder.setLargeIcon(cover)
        // 按钮定义：上一章、播放、停止、下一章、定时
        builder.addAction(
            R.drawable.ic_skip_previous,
            getString(R.string.previous_chapter),
            aloudServicePendingIntent(IntentAction.prev)
        )
        if (pause) {
            builder.addAction(
                R.drawable.ic_play_24dp,
                getString(R.string.resume),
                aloudServicePendingIntent(IntentAction.resume)
            )
        } else {
            builder.addAction(
                R.drawable.ic_pause_24dp,
                getString(R.string.pause),
                aloudServicePendingIntent(IntentAction.pause)
            )
        }
        builder.addAction(
            R.drawable.ic_skip_next,
            getString(R.string.next_chapter),
            aloudServicePendingIntent(IntentAction.next)
        )
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.stop),
            aloudServicePendingIntent(IntentAction.stop)
        )
        builder.addAction(
            R.drawable.ic_time_add_24dp,
            getString(R.string.set_timer),
            aloudServicePendingIntent(IntentAction.addTimer)
        )
        builder.setStyle(androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)
            .setMediaSession(mediaSessionCompat.sessionToken)
        )
        return builder
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        execute {
            try {
                upMediaMetadata()
                val notification = createNotification()
                startForeground(NotificationId.ReadAloudService, notification.build())
            } catch (e: Exception) {
                AppLog.put("创建朗读通知出错,${e.localizedMessage}", e, true)
                //创建通知出错不结束服务就会崩溃,服务必须绑定通知
                stopSelf()
            }
        }
    }

    abstract fun aloudServicePendingIntent(actionStr: String): PendingIntent?

    open fun prevChapter(continuePlayback: Boolean = true) {
        toLast = false
        prepareChapterTransition(continuePlayback)
        ReadBook.moveToPrevChapter(true, toLast = false, fromReadAloud = true)
    }

    open fun nextChapter(continuePlayback: Boolean = true) {
        ReadBook.upReadTime()
        AppLog.putDebug("${ReadBook.curTextChapter?.chapter?.title} 朗读结束跳转下一章并朗读")
        prepareChapterTransition(continuePlayback)
        if (!ReadBook.moveToNextChapter(true, fromReadAloud = true)) {
            stopSelf()
        }
    }

    open fun selectChapter(chapterIndex: Int, continuePlayback: Boolean = true) {
        if (chapterIndex !in 0 until ReadBook.chapterSize) return
        prepareChapterTransition(continuePlayback)
        ReadBook.openChapter(chapterIndex, durChapterPos = 0, upContent = true) {
            ReadBook.readAloud(play = continuePlayback, startPos = 0)
        }
    }

    private fun initPhoneStateListener() {
        val needRegister = AppConfig.ignoreAudioFocus && AppConfig.pauseReadAloudWhilePhoneCalls
        if (needRegister && registeredPhoneStateListener) {
            return
        }
        if (needRegister) {
            registerPhoneStateListener(phoneStateListener)
        } else {
            unregisterPhoneStateListener(phoneStateListener)
        }
    }

    private fun unregisterPhoneStateListener(l: PhoneStateListener) {
        if (registeredPhoneStateListener) {
            withReadPhoneStatePermission {
                telephonyManager.listen(l, PhoneStateListener.LISTEN_NONE)
                registeredPhoneStateListener = false
            }
        }
    }

    private fun registerPhoneStateListener(l: PhoneStateListener) {
        withReadPhoneStatePermission {
            telephonyManager.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            registeredPhoneStateListener = true
        }
    }

    private fun withReadPhoneStatePermission(block: () -> Unit) {
        try {
            block.invoke()
        } catch (_: SecurityException) {
            PermissionsCompat.Builder()
                .addPermissions(Permissions.READ_PHONE_STATE)
                .rationale(R.string.read_aloud_read_phone_state_permission_rationale)
                .onGranted {
                    try {
                        block.invoke()
                    } catch (_: SecurityException) {
                        LogUtils.d(TAG, "Grant read phone state permission fail.")
                    }
                }
                .request()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    inner class ReadAloudPhoneStateListener : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            super.onCallStateChanged(state, phoneNumber)
            when (state) {
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (needResumeOnCallStateIdle) {
                        AppLog.put("来电结束,继续朗读")
                        resumeReadAloud()
                    } else {
                        AppLog.put("来电结束")
                    }
                }

                TelephonyManager.CALL_STATE_RINGING -> {
                    if (!pause) {
                        AppLog.put("来电响铃,暂停朗读")
                        needResumeOnCallStateIdle = true
                        pauseReadAloud()
                    } else {
                        AppLog.put("来电响铃")
                    }
                }

                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    AppLog.put("来电接听,不做处理")
                }
            }
        }
    }

}
