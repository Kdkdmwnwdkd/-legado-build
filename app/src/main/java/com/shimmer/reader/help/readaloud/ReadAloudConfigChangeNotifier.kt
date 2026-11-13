package com.shimmer.reader.help.readaloud

import android.os.Bundle
import com.shimmer.reader.constant.EventBus
import com.shimmer.reader.utils.postEvent

object ReadAloudConfigChangeNotifier {

    fun notify(scope: String) {
        postEvent(
            EventBus.READ_ALOUD_CONFIG_CHANGED,
            Bundle().apply {
                putString(EventBus.READ_ALOUD_CONFIG_SCOPE, scope)
            }
        )
    }

    fun notifyEngine() {
        notify(EventBus.READ_ALOUD_CONFIG_SCOPE_ENGINE)
    }

    fun notifySpeech() {
        notify(EventBus.READ_ALOUD_CONFIG_SCOPE_SPEECH)
    }

    fun notifyAudio() {
        notify(EventBus.READ_ALOUD_CONFIG_SCOPE_AUDIO)
    }
}
