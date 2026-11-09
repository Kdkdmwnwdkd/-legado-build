package com.shimmer.reader.model

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.shimmer.reader.constant.AppLog
import com.shimmer.reader.constant.EventBus
import com.shimmer.reader.constant.IntentAction
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.HttpTTS
import com.shimmer.reader.help.config.AppConfig
import com.shimmer.reader.help.readaloud.speech.SpeechRoute
import com.shimmer.reader.help.readaloud.speech.SpeechRouteSanitizer
import com.shimmer.reader.service.BaseReadAloudService
import com.shimmer.reader.service.HttpReadAloudService
import com.shimmer.reader.service.TTSReadAloudService
import com.shimmer.reader.utils.LogUtils
import com.shimmer.reader.utils.postEvent
import com.shimmer.reader.utils.startForegroundServiceCompat
import com.shimmer.reader.utils.toastOnUi
import splitties.init.appCtx

object ReadAloud {
    private var aloudClass: Class<*> = getReadAloudClass()
    val ttsEngine get() = ReadBook.book?.getTtsEngine() ?: AppConfig.ttsEngine
    val speechRoute get() = SpeechRouteSanitizer.validOrDefault(SpeechRoute.fromTtsEngineValue(ttsEngine))
    var httpTTS: HttpTTS? = null

    private fun getReadAloudClass(): Class<*> {
        val ttsEngine = ttsEngine
        val route = SpeechRoute.fromTtsEngineValue(ttsEngine)
        if (AppConfig.aiReadAloudRoleEnabled) {
            httpTTS = route
                .takeIf { it.engineType == SpeechRoute.ENGINE_HTTP }
                ?.engineValue
                ?.toLongOrNull()
                ?.let { appDb.httpTTSDao.get(it) }
                ?: appDb.httpTTSDao.all.firstOrNull()
            return HttpReadAloudService::class.java
        }
        if (ttsEngine.isNullOrBlank() || route.engineType == SpeechRoute.ENGINE_SYSTEM) {
            return TTSReadAloudService::class.java
        }
        if (route.engineType == SpeechRoute.ENGINE_HTTP) {
            httpTTS = route.engineValue.toLongOrNull()?.let { appDb.httpTTSDao.get(it) }
            if (httpTTS != null) {
                return HttpReadAloudService::class.java
            }
        }
        return TTSReadAloudService::class.java
    }

    fun upReadAloudClass() {
        stop(appCtx)
        aloudClass = getReadAloudClass()
    }

    fun resolveReadAloudClass(): Class<*> {
        return getReadAloudClass()
    }

    fun refreshReadAloudClass(): Class<*> {
        aloudClass = getReadAloudClass()
        return aloudClass
    }

    fun play(
        context: Context,
        play: Boolean = true,
        pageIndex: Int = ReadBook.durPageIndex,
        startPos: Int = 0
    ) {
        val intent = Intent(context, aloudClass)
        intent.action = IntentAction.play
        intent.putExtra("play", play)
        intent.putExtra("pageIndex", pageIndex)
        intent.putExtra("startPos", startPos)
        LogUtils.d("ReadAloud", intent.toString())
        try {
            context.startForegroundServiceCompat(intent)
        } catch (e: Exception) {
            val msg = "启动朗读服务出错\n${e.localizedMessage}"
            AppLog.put(msg, e)
            context.toastOnUi(msg)
        }
    }

    fun playByEventBus(
        play: Boolean = true,
        pageIndex: Int = ReadBook.durPageIndex,
        startPos: Int = 0
    ) {
        val bundle = Bundle().apply {
            putBoolean("play", play)
            putInt("pageIndex", pageIndex)
            putInt("startPos", startPos)
        }
        postEvent(EventBus.READ_ALOUD_PLAY, bundle)
    }

    fun moveToCue(
        context: Context,
        cueIndex: Int,
        chapterPosition: Int,
        expectedChapterIndex: Int = ReadBook.durChapterIndex,
        play: Boolean = BaseReadAloudService.isPlay()
    ) {
        if (!BaseReadAloudService.isRun) return
        ReadBook.resumeReadAloudFollow(jumpToPosition = false)
        val intent = Intent(context, aloudClass)
        intent.action = IntentAction.moveTo
        intent.putExtra("cueIndex", cueIndex)
        intent.putExtra("chapterPosition", chapterPosition)
        intent.putExtra("expectedChapterIndex", expectedChapterIndex)
        intent.putExtra("play", play)
        context.startForegroundServiceCompat(intent)
    }

    fun playFromPosition(
        context: Context,
        bookUrl: String,
        chapterIndex: Int,
        chapterUrl: String,
        chapterPosition: Int
    ) {
        val intent = Intent(context, aloudClass)
        intent.action = IntentAction.playFromPosition
        intent.putExtra("bookUrl", bookUrl)
        intent.putExtra("chapterIndex", chapterIndex)
        intent.putExtra("chapterUrl", chapterUrl)
        intent.putExtra("chapterPosition", chapterPosition)
        try {
            context.startForegroundServiceCompat(intent)
        } catch (e: Exception) {
            val msg = "启动选句朗读出错\n${e.localizedMessage}"
            AppLog.put(msg, e)
            context.toastOnUi(msg)
        }
    }

    fun pause(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.pause
            context.startForegroundServiceCompat(intent)
        }
    }

    fun resume(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.resume
            context.startForegroundServiceCompat(intent)
        }
    }

    fun stop(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.stop
            context.startForegroundServiceCompat(intent)
        }
    }

    fun stopForBookSwitch(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.stop
            intent.putExtra(IntentAction.stopReason, IntentAction.stopReasonBookSwitch)
            context.startForegroundServiceCompat(intent)
        }
    }

    fun prevParagraph(context: Context) {
        if (BaseReadAloudService.isRun) {
            ReadBook.resumeReadAloudFollow(jumpToPosition = false)
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.prevParagraph
            context.startForegroundServiceCompat(intent)
        }
    }

    fun nextParagraph(context: Context) {
        if (BaseReadAloudService.isRun) {
            ReadBook.resumeReadAloudFollow(jumpToPosition = false)
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.nextParagraph
            context.startForegroundServiceCompat(intent)
        }
    }

    fun prevChapter(context: Context, continuePlayback: Boolean = BaseReadAloudService.isPlay()) {
        if (BaseReadAloudService.isRun) {
            ReadBook.resumeReadAloudFollow(jumpToPosition = false)
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.prev
            intent.putExtra("continuePlayback", continuePlayback)
            context.startForegroundServiceCompat(intent)
        }
    }

    fun nextChapter(context: Context, continuePlayback: Boolean = BaseReadAloudService.isPlay()) {
        if (BaseReadAloudService.isRun) {
            ReadBook.resumeReadAloudFollow(jumpToPosition = false)
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.next
            intent.putExtra("continuePlayback", continuePlayback)
            context.startForegroundServiceCompat(intent)
        }
    }

    fun selectChapter(
        context: Context,
        chapterIndex: Int,
        continuePlayback: Boolean = BaseReadAloudService.isPlay()
    ) {
        if (BaseReadAloudService.isRun) {
            ReadBook.resumeReadAloudFollow(jumpToPosition = false)
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.selectChapter
            intent.putExtra("chapterIndex", chapterIndex)
            intent.putExtra("continuePlayback", continuePlayback)
            context.startForegroundServiceCompat(intent)
        }
    }

    fun upTtsSpeechRate(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.upTtsSpeechRate
            context.startForegroundServiceCompat(intent)
        }
    }

    fun setTimer(context: Context, minute: Int) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.setTimer
            intent.putExtra("minute", minute)
            context.startForegroundServiceCompat(intent)
        }
    }

}
