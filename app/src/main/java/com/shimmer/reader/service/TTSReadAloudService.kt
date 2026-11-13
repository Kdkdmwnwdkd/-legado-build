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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    private data class SpeakAttempt(
        val reservation: TtsQueueReservation,
        val text: String,
        val result: Int,
        val error: Throwable?
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
    }

    override fun onDestroy() {
        silenceWatchJob?.cancel()
        silenceWatchJob = null
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
            val fillResult = fillTtsQueue(queueRef)
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
            val attempt = synchronized(queueStateLock) {
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
                val text = checkNotNull(context.speakText(reservation.cueIndex))
                val tts = textToSpeech
                val result: Result<Int> = if (tts == null) {
                    Result.failure(NoStackTraceException("tts is null"))
                } else {
                    runCatching {
                        tts.speak(
                            text,
                            if (reservation.flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                            ttsParamsForCue(reservation.cueIndex),
                            utteranceId(ref.sessionId, ref.generation, reservation.cueIndex)
                        )
                    }
                }
                val resultCode = result.getOrDefault(TextToSpeech.ERROR)
                if (resultCode == TextToSpeech.ERROR) {
                    queueWindow.complete(ref.queueToken, reservation.cueIndex)
                }
                SpeakAttempt(
                    reservation = reservation,
                    text = text,
                    result = resultCode,
                    error = result.exceptionOrNull()
                )
            }
            if (attempt.result != TextToSpeech.ERROR) {
                addedCount++
                continue
            }
            attempt.error?.let {
                AppLog.put("tts出错\n${it.localizedMessage}", it, true)
            }
            if (attempt.reservation.flush) {
                return QueueFillResult(addedCount, flushFailed = true)
            }
            AppLog.put("tts朗读出错:${attempt.text}")
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
        val fillResult = fillTtsQueue(ref)
        if (fillResult.flushFailed) {
            recoverFromFlushFailure(ref)
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

    private fun ttsParamsForCue(cueIndex: Int): Bundle {
        return Bundle().apply {
            putFloat(
                TextToSpeech.Engine.KEY_PARAM_VOLUME,
                speakerLoudnessInfo(cueIndex).gain.coerceIn(0f, 1f)
            )
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
        const val TTS_QUEUE_WINDOW_SIZE = 4
        const val SILENCE_CHECK_INTERVAL_MILLIS = 3_000L
        const val MAX_CONSECUTIVE_SILENCE_GIVE_UPS = 3
    }

}
