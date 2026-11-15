package com.shimmer.reader.service

import android.app.PendingIntent
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.lifecycleScope
import com.shimmer.reader.R
import com.shimmer.reader.constant.AppConst
import com.shimmer.reader.constant.AppLog
import com.shimmer.reader.constant.AppPattern
import com.shimmer.reader.exception.NoStackTraceException
import com.shimmer.reader.help.MediaHelp
import com.shimmer.reader.help.config.AppConfig
import com.shimmer.reader.help.coroutine.Coroutine
import com.shimmer.reader.help.readaloud.ReadAloudPlaybackState
import com.shimmer.reader.model.ReadAloud
import com.shimmer.reader.model.ReadBook
import com.shimmer.reader.utils.LogUtils
import com.shimmer.reader.utils.servicePendingIntent
import com.shimmer.reader.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地朗读
 */
class TTSReadAloudService : BaseReadAloudService(), TextToSpeech.OnInitListener {

    private data class UtteranceRef(
        val sessionId: Long,
        val generation: Long,
        val cueIndex: Int
    ) {
        val queueToken: TtsQueueToken
            get() = TtsQueueToken(sessionId, generation)
    }

    private data class QueueContext(
        val ref: UtteranceRef,
        val content: List<String>,
        val startCueIndex: Int,
        val startOffset: Int
    )

    private data class QueueFillResult(
        val addedCount: Int,
        val flushFailed: Boolean = false,
        val exhaustedAndEmpty: Boolean = false
    )

    private class PendingSpeak(
        val tts: TextToSpeech?,
        val reservation: TtsQueueReservation,
        val text: String
    )

    private var textToSpeech: TextToSpeech? = null
    private var ttsInitFinish = false
    private var pendingPlayOnInit = false
    private val ttsUtteranceListener = TTSUtteranceListener()
    private var speakJob: Coroutine<*>? = null
    private val utteranceGeneration = AtomicLong(0L)
    private val queueStateLock = Any()
    private val queueWindow = TtsQueueWindow(TTS_QUEUE_WINDOW_SIZE)
    private var queueContext: QueueContext? = null
    private val silenceGuard = ReadAloudSilenceGuard()
    private var silenceWatchJob: Job? = null
    // 补队列要连续调若干次 TextToSpeech.speak（每次都是跨进程调用），
    // 必须串行，否则先后顺序会和队列顺序对不上，播出来是乱的。
    private val fillMutex = Mutex()
    // 一段念完之后要补货，但补货不能占着 TTS 的回调线程做。
    private val refillQueue = Channel<UtteranceRef>(Channel.CONFLATED)
    private var refillWorker: Job? = null
    private var consecutiveSilenceGiveUps = 0
    private val TAG = "TTSReadAloudService"

    override fun onCreate() {
        super.onCreate()
        kotlin.runCatching {
            initTts()
        }.onFailure {
            AppLog.put("${getString(R.string.tts_init_failed)}\n$it", it, true)
        }
        startSilenceWatchdog()
        startRefillWorker()
    }

    override fun onDestroy() {
        silenceWatchJob?.cancel()
        silenceWatchJob = null
        refillWorker?.cancel()
        refillWorker = null
        refillQueue.close()
        MediaHelp.releaseSilentSound()
        super.onDestroy()
        clearTTS()
    }

    /**
     * 每隔几秒看一眼：本段是不是交出去之后一直没有动静。
     * 卡住就重播本段，连续卡住就跳过，免得整章停在那里。
     */
    private fun startSilenceWatchdog() {
        if (silenceWatchJob?.isActive == true) return
        silenceWatchJob = lifecycleScope.launch {
            while (isActive) {
                delay(SILENCE_CHECK_INTERVAL_MILLIS)
                checkSilence()
            }
        }
    }

    private fun checkSilence() {
        val hasPending = synchronized(queueStateLock) {
            queueWindow.queuedCount > 0
        }
        val decision = silenceGuard.check(
            now = SystemClock.elapsedRealtime(),
            playing = !pause,
            hasPendingUtterance = hasPending
        )
        when (decision) {
            ReadAloudSilenceGuard.Decision.RETRY -> {
                AppLog.put("朗读长时间没有声音，重试本段")
                play()
            }

            ReadAloudSilenceGuard.Decision.GIVE_UP -> {
                AppLog.put("朗读连续没有声音，跳过本段")
                consecutiveSilenceGiveUps++
                if (consecutiveSilenceGiveUps >= MAX_CONSECUTIVE_SILENCE_GIVE_UPS) {
                    // 引擎整体不可用了，再往下跳也只是空转，停下等人处理
                    AppLog.put("朗读引擎持续无响应，已暂停")
                    toastOnUi(R.string.ra_engine_no_response)
                    pauseReadAloud()
                    return
                }
                if (moveToNextCue()) {
                    play()
                } else {
                    nextChapter()
                }
            }

            ReadAloudSilenceGuard.Decision.NONE -> Unit
        }
    }

    @Synchronized
    private fun initTts() {
        ttsInitFinish = false
        val engine = resolveSystemTtsEngine(ReadAloud.speechRoute.engineValue)
        LogUtils.d(TAG, "initTts engine:$engine")
        textToSpeech = if (engine.isNullOrBlank()) {
            TextToSpeech(this, this)
        } else {
            TextToSpeech(this, this, engine)
        }
        upSpeechRate()
    }

    private fun resolveSystemTtsEngine(engineValue: String): String? {
        val value = engineValue.trim()
        if (value.isBlank()) return null
        return runCatching {
            JSONObject(value).optString("value").takeIf { it.isNotBlank() }
        }.getOrNull() ?: value
    }

    @Synchronized
    fun clearTTS() {
        speakJob?.cancel()
        speakJob = null
        pendingPlayOnInit = false
        val tts = synchronized(queueStateLock) {
            utteranceGeneration.incrementAndGet()
            queueWindow.clear()
            queueContext = null
            textToSpeech.also { textToSpeech = null }
        }
        tts?.runCatching {
            stop()
            shutdown()
        }
        ttsInitFinish = false
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let {
                it.setOnUtteranceProgressListener(ttsUtteranceListener)
                ttsInitFinish = true
                if (pendingPlayOnInit) {
                    play()
                }
            }
        } else {
            toastOnUi(R.string.tts_init_failed)
        }
    }

    @Synchronized
    override fun play() {
        if (!ttsInitFinish) {
            pendingPlayOnInit = true
            return
        }
        pendingPlayOnInit = false
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            pauseReadAloud(abandonFocus = false)
            postReadAloudPlaybackPhase(
                ReadAloudPlaybackState.PHASE_PAUSED,
                message = getString(R.string.ra_empty_content),
                playing = false
            )
            return
        }
        super.play()
        MediaHelp.playSilentSound(this@TTSReadAloudService)
        silenceGuard.onUtterance(
            cueIndex = nowSpeak,
            text = contentList.getOrNull(nowSpeak),
            now = SystemClock.elapsedRealtime()
        )
        speakJob?.cancel()
        val sessionId = currentReadAloudSessionId
        val queueRef = synchronized(queueStateLock) {
            val generation = utteranceGeneration.incrementAndGet()
            UtteranceRef(sessionId, generation, nowSpeak).also { ref ->
                queueContext = QueueContext(
                    ref = ref,
                    content = contentList,
                    startCueIndex = nowSpeak,
                    startOffset = paragraphStartPos
                )
                queueWindow.reset(ref.queueToken, nowSpeak)
            }
        }
        speakJob = execute {
            LogUtils.d(TAG, "朗读列表大小 ${contentList.size}")
            LogUtils.d(TAG, "朗读页数 ${textChapter?.pageSize}")
            ensureActive()
            val fillResult = fillMutex.withLock { fillTtsQueue(queueRef) }
            when {
                fillResult.flushFailed -> recoverFromFlushFailure(queueRef)
                fillResult.exhaustedAndEmpty -> moveToNextChapterAfterEmptyQueue(queueRef)
                else -> LogUtils.d(
                    TAG,
                    "TTS queue primed ${fillResult.addedCount}/$TTS_QUEUE_WINDOW_SIZE"
                )
            }
        }.onError {
            AppLog.put("tts朗读出错\n${it.localizedMessage}", it, true)
        }
    }

    private fun fillTtsQueue(ref: UtteranceRef): QueueFillResult {
        var addedCount = 0
        while (true) {
            // 锁里只做两件事：占一个位置、取这一段正文。
            // 真正的 speak 是跨进程调用，引擎正忙时会阻塞几百毫秒，
            // 放在锁里会把 onStart/onDone 这些回调一起堵住——那就又卡回原样了。
            val pending = synchronized(queueStateLock) {
                val context = activeQueueContextLocked(ref)
                    ?: return QueueFillResult(addedCount)
                val reservation = queueWindow.reserve(
                    token = ref.queueToken,
                    cueCount = context.content.size
                ) { cueIndex ->
                    context.speakText(cueIndex) != null
                } ?: return QueueFillResult(
                    addedCount = addedCount,
                    exhaustedAndEmpty = queueWindow.exhausted && queueWindow.queuedCount == 0
                )
                PendingSpeak(
                    tts = textToSpeech,
                    reservation = reservation,
                    text = checkNotNull(context.speakText(reservation.cueIndex))
                )
            }
            val tts = pending.tts
            val result: Result<Int> = if (tts == null) {
                Result.failure(NoStackTraceException("tts is null"))
            } else {
                runCatching {
                    tts.speak(
                        pending.text,
                        if (pending.reservation.flush) {
                            TextToSpeech.QUEUE_FLUSH
                        } else {
                            TextToSpeech.QUEUE_ADD
                        },
                        ttsParamsForCue(pending.reservation.cueIndex),
                        utteranceId(ref.sessionId, ref.generation, pending.reservation.cueIndex)
                    )
                }
            }
            if (result.getOrDefault(TextToSpeech.ERROR) != TextToSpeech.ERROR) {
                addedCount++
                continue
            }
            // 这一句没交出去，把位置还回去，窗口才不会被占着补不进来
            synchronized(queueStateLock) {
                queueWindow.complete(ref.queueToken, pending.reservation.cueIndex)
            }
            result.exceptionOrNull()?.let {
                AppLog.put("tts出错\n${it.localizedMessage}", it, true)
            }
            if (pending.reservation.flush) {
                return QueueFillResult(addedCount, flushFailed = true)
            }
            AppLog.put("tts朗读出错:${pending.text}")
        }
    }

    private fun QueueContext.speakText(cueIndex: Int): String? {
        var text = content.getOrNull(cueIndex) ?: return null
        if (cueIndex == startCueIndex && startOffset > 0) {
            text = text.substring(startOffset.coerceIn(0, text.length))
        }
        return text.takeUnless { it.matches(AppPattern.notReadAloudRegex) }
    }

    private fun activeQueueContextLocked(ref: UtteranceRef): QueueContext? {
        val context = queueContext ?: return null
        return context.takeIf {
            it.ref.queueToken == ref.queueToken &&
                    queueWindow.isActive(ref.queueToken) &&
                    utteranceGeneration.get() == ref.generation &&
                    isCurrentReadAloudSession(ref.sessionId) &&
                    !pause
        }
    }

    private fun completeQueuedUtterance(ref: UtteranceRef): Boolean {
        return synchronized(queueStateLock) {
            activeQueueContextLocked(ref) != null &&
                    queueWindow.complete(ref.queueToken, ref.cueIndex)
        }
    }

    private fun finishUtteranceAndRefill(ref: UtteranceRef) {
        if (!completeQueuedUtterance(ref) || pause) return
        // 这一段刚念完，定时也到点了，就停在这里，不填下一段
        if (consumeStopAfterCue()) return
        if (ref.cueIndex == nowSpeak && !moveToNextCue()) {
            nextChapter()
            return
        }
        silenceGuard.onUtterance(
            cueIndex = nowSpeak,
            text = contentList.getOrNull(nowSpeak),
            now = SystemClock.elapsedRealtime()
        )
        // 补一次货要连续跨进程调用好几遍 speak。第三方引擎（MultiTTS 这类）正在合成时，
        // 这些调用会阻塞，压在本回调线程上就等于把下一句的 onStart 一起堵住，
        // 听感就是两句话之间空一下。丢给后台串行队列，回调立刻返回。
        refillQueue.trySend(ref)
    }

    /**
     * 补队列的后台工人。一次只处理一个请求：队列靠 [TtsQueueWindow] 自己往后推，
     * 中途新来的请求覆盖旧的也不要紧，补货永远补到窗口满为止。
     */
    private fun startRefillWorker() {
        if (refillWorker?.isActive == true) return
        refillWorker = lifecycleScope.launch(Dispatchers.IO) {
            for (ref in refillQueue) {
                val result = runCatching {
                    fillMutex.withLock { fillTtsQueue(ref) }
                }
                result.onSuccess { fillResult ->
                    when {
                        fillResult.flushFailed -> recoverFromFlushFailure(ref)
                        fillResult.exhaustedAndEmpty -> moveToNextChapterAfterEmptyQueue(ref)
                    }
                }.onFailure {
                    if (it !is CancellationException) {
                        AppLog.put("tts补队列出错\n${it.localizedMessage}", it, true)
                    }
                }
            }
        }
    }

    private fun recoverFromFlushFailure(ref: UtteranceRef) {
        val active = synchronized(queueStateLock) {
            activeQueueContextLocked(ref) != null
        }
        if (!active) return
        AppLog.put("tts出错 尝试重新初始化")
        clearTTS()
        initTts()
    }

    private fun moveToNextChapterAfterEmptyQueue(ref: UtteranceRef) {
        val active = synchronized(queueStateLock) {
            activeQueueContextLocked(ref) != null && queueWindow.queuedCount == 0
        }
        if (!active) return
        playStop()
        val stopGeneration = utteranceGeneration.get()
        lifecycleScope.launch {
            delay(1000)
            if (isCurrentReadAloudSession(ref.sessionId) &&
                utteranceGeneration.get() == stopGeneration &&
                !pause
            ) {
                nextChapter()
            }
        }
    }

    private fun ttsParamsForCue(cueIndex: Int): Bundle? {
        // 逐句音量只有在线音频那条链路算得出真实增益；本地 TTS 没有音频样本可测，
        // 绝大多数时候增益就是 1。这时再传 KEY_PARAM_VOLUME，一部分引擎会认为
        // "参数变了"，每句都重新准备一次合成管线，正好卡在句与句之间。
        val gain = speakerLoudnessInfo(cueIndex).gain
        if (kotlin.math.abs(gain - 1f) < 0.01f) return null
        return Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, gain.coerceIn(0f, 1f))
        }
    }

    private fun utteranceId(sessionId: Long, generation: Long, cueIndex: Int): String =
        "${AppConst.APP_TAG}|$sessionId|$generation|$cueIndex"

    @Synchronized
    override fun playStop() {
        speakJob?.cancel()
        speakJob = null
        pendingPlayOnInit = false
        val tts = synchronized(queueStateLock) {
            utteranceGeneration.incrementAndGet()
            queueWindow.clear()
            queueContext = null
            textToSpeech
        }
        tts?.runCatching {
            stop()
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        if (AppConfig.ttsFlowSys) {
            if (reset) {
                clearTTS()
                initTts()
            }
        } else {
            val speechRate = (AppConfig.ttsSpeechRate + 5) / 10f
            textToSpeech?.setSpeechRate(speechRate)
        }
    }

    /**
     * 暂停朗读
     */
    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        playStop()
    }

    /**
     * 恢复朗读
     */
    override fun resumeReadAloud() {
        if (resumeBlockedReadAloudIfNeeded()) return
        super.resumeReadAloud()
        play()
    }

    /**
     * 朗读监听
     */
    private inner class TTSUtteranceListener : UtteranceProgressListener() {

        private val TAG = "TTSUtteranceListener"

        private fun utteranceRef(utteranceId: String?): UtteranceRef? {
            val parts = utteranceId
                ?.removePrefix("${AppConst.APP_TAG}|")
                ?.split('|')
                ?: return null
            if (parts.size != 3) return null
            val ref = UtteranceRef(
                sessionId = parts[0].toLongOrNull() ?: return null,
                generation = parts[1].toLongOrNull() ?: return null,
                cueIndex = parts[2].toIntOrNull() ?: return null
            )
            return ref.takeIf {
                isCurrentReadAloudSession(it.sessionId) &&
                        utteranceGeneration.get() == it.generation &&
                        it.cueIndex in contentList.indices
            }
        }

        override fun onStart(s: String) {
            val cueIndex = utteranceRef(s)?.cueIndex ?: return
            if (pause) return
            val now = SystemClock.elapsedRealtime()
            silenceGuard.onUtterance(cueIndex, contentList.getOrNull(cueIndex), now)
            silenceGuard.onStarted(now)
            if (cueIndex != nowSpeak) {
                syncToCueIndex(cueIndex)
            }
            val cueStartOffset = paragraphStartPos.takeIf { cueIndex == nowSpeak } ?: 0
            postReadAloudPlaybackPhase(
                ReadAloudPlaybackState.PHASE_PLAYING,
                cueIndex = cueIndex
            )
            LogUtils.d(TAG, "onStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$s")
            textChapter?.let {
                if (contentList.getOrNull(nowSpeak)?.matches(AppPattern.notReadAloudRegex) == true) {
                    nextParagraph()
                }
                if (pageIndex + 1 < it.pageSize
                    && readAloudNumber + cueStartOffset + 1 > it.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    if (!ReadBook.readAloudFollowPaused) {
                        ReadBook.moveToNextPage(fromReadAloud = true)
                    }
                }
                upTtsProgress(readAloudNumber + cueStartOffset + 1)
            }
        }

        override fun onDone(s: String) {
            LogUtils.d(TAG, "onDone utteranceId:$s")
            val ref = utteranceRef(s) ?: return
            consecutiveSilenceGiveUps = 0
            silenceGuard.onCompleted(SystemClock.elapsedRealtime())
            finishUtteranceAndRefill(ref)
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            super.onRangeStart(utteranceId, start, end, frame)
            val cueIndex = utteranceRef(utteranceId)?.cueIndex ?: return
            if (pause) return
            silenceGuard.onActivity(SystemClock.elapsedRealtime())
            if (cueIndex != nowSpeak) {
                syncToCueIndex(cueIndex)
            }
            val cueStartOffset = paragraphStartPos.takeIf { cueIndex == nowSpeak } ?: 0
            val msg =
                "onRangeStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId start:$start end:$end frame:$frame"
            LogUtils.d(TAG, msg)
            textChapter?.let {
                if (pageIndex + 1 < it.pageSize
                    && readAloudNumber + cueStartOffset + start > it.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    if (!ReadBook.readAloudFollowPaused) {
                        ReadBook.moveToNextPage(fromReadAloud = true)
                    }
                    upTtsProgress(readAloudNumber + cueStartOffset + start)
                }
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            val ref = utteranceRef(utteranceId) ?: return
            val cueIndex = ref.cueIndex
            postReadAloudPlaybackPhase(
                ReadAloudPlaybackState.PHASE_ERROR,
                cueIndex = cueIndex,
                message = getString(R.string.ra_tts_error_code, errorCode)
            )
            LogUtils.d(
                TAG,
                "onError nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId errorCode:$errorCode"
            )
            finishUtteranceAndRefill(ref)
        }

        private fun nextParagraph() {
            //跳过全标点段落
            if (!moveToNextCue()) {
                nextChapter()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(s: String) {
            val ref = utteranceRef(s) ?: return
            val cueIndex = ref.cueIndex
            postReadAloudPlaybackPhase(
                ReadAloudPlaybackState.PHASE_ERROR,
                cueIndex = cueIndex,
                message = getString(R.string.ra_tts_error)
            )
            LogUtils.d(TAG, "onError nowSpeak:$nowSpeak pageIndex:$pageIndex s:$s")
            finishUtteranceAndRefill(ref)
        }

    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSReadAloudService>(actionStr)
    }

    private companion object {
        /**
         * 交给引擎排队的段数。
         *
         * 原来只排 4 段。MultiTTS 这类引擎是"收到一段、合成一段、播一段"，一旦某段合成
         * 比上一段播放慢，队列见底就得干等——听感就是每隔几句卡一下。排到 8 段，
         * 引擎手上始终有几段的余量，短句连读的章节也不会被追着跑。
         * 这些只是文本，占用可以忽略；暂停/跳转都会 QUEUE_FLUSH 清空，不会积压。
         */
        const val TTS_QUEUE_WINDOW_SIZE = 8
        const val SILENCE_CHECK_INTERVAL_MILLIS = 3_000L
        const val MAX_CONSECUTIVE_SILENCE_GIVE_UPS = 3
    }

}
