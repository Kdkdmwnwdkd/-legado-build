package com.shimmer.reader.ui.book.read.config

import android.app.Application
import android.os.Bundle
import com.shimmer.reader.R
import com.shimmer.reader.base.BaseViewModel
import com.shimmer.reader.constant.EventBus
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.HttpTTS
import com.shimmer.reader.exception.NoStackTraceException
import com.shimmer.reader.help.ConcurrentRateLimiter.Companion.concurrentRecordMap
import com.shimmer.reader.help.readaloud.speech.SpeechRouteSanitizer
import com.shimmer.reader.model.ReadAloud
import com.shimmer.reader.utils.getClipText
import com.shimmer.reader.utils.isJsonArray
import com.shimmer.reader.utils.isJsonObject
import com.shimmer.reader.utils.postEvent
import com.shimmer.reader.utils.toastOnUi
import splitties.init.appCtx

class HttpTtsEditViewModel(app: Application) : BaseViewModel(app) {

    var id: Long? = null
    var httpTTS: HttpTTS? = null

    fun initData(arguments: Bundle?, success: (httpTTS: HttpTTS) -> Unit) {
        execute {
            if (id == null) {
                val argumentId = arguments?.getLong("id")
                if (argumentId != null && argumentId != 0L) {
                    id = argumentId
                    val source = appDb.httpTTSDao.get(argumentId)
                    httpTTS = source
                    return@execute source
                }
            }
            return@execute null
        }.onSuccess {
            it?.let {
                success.invoke(it)
            }
        }
    }

    fun save(httpTTS: HttpTTS, success: (() -> Unit)? = null) {
        id = httpTTS.id
        this.httpTTS = httpTTS
        execute {
            appDb.httpTTSDao.insert(httpTTS)
            SpeechRouteSanitizer.cleanInvalidRoutes()
            concurrentRecordMap.remove(httpTTS.getKey()) //删除并发限制缓存
            ReadAloud.refreshReadAloudClass()
            postEvent(
                EventBus.READ_ALOUD_CONFIG_CHANGED,
                Bundle().apply {
                    putString(
                        EventBus.READ_ALOUD_CONFIG_SCOPE,
                        EventBus.READ_ALOUD_CONFIG_SCOPE_ENGINE
                    )
                }
            )
        }.onSuccess {
            success?.invoke()
        }
    }

    fun importFromClip(onSuccess: (httpTTS: HttpTTS) -> Unit) {
        val text = context.getClipText()
        if (text.isNullOrBlank()) {
            context.toastOnUi(R.string.toast_clipboard_empty)
        } else {
            importSource(text, onSuccess)
        }
    }

    fun importSource(text: String, onSuccess: (httpTTS: HttpTTS) -> Unit) {
        val text1 = text.trim()
        execute {
            when {
                text1.isJsonObject() -> {
                    HttpTTS.fromJson(text1).getOrThrow()
                }
                text1.isJsonArray() -> {
                    HttpTTS.fromJsonArray(text1).getOrThrow().first()
                }
                else -> {
                    throw NoStackTraceException(appCtx.getString(R.string.wrong_format))
                }
            }
        }.onSuccess {
            onSuccess.invoke(it)
        }.onError {
            context.toastOnUi(it.localizedMessage)
        }
    }

}
