package io.legado.app.help.http.dns

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import io.legado.app.constant.PreferKey
import io.legado.app.utils.defaultSharedPreferences

object NetworkDnsStore {
    fun load(context: Context): NetworkDnsConfig = runCatching {
        NetworkDnsConfig.decode(context.defaultSharedPreferences.getString(PreferKey.networkDnsConfig, null))
    }.getOrDefault(NetworkDnsConfig())

    fun hasInvalidSettings(context: Context): Boolean = runCatching {
        NetworkDnsConfig.decode(context.defaultSharedPreferences.getString(PreferKey.networkDnsConfig, null))
    }.isFailure

    fun save(context: Context, config: NetworkDnsConfig) {
        val encoded = config.validated().encode()
        context.defaultSharedPreferences.edit { putString(PreferKey.networkDnsConfig, encoded) }
    }

    /** A commit waits for pending apply() writes on the same preferences file.
     * Finish this off the UI thread before restart() kills the process. */
    @WorkerThread
    fun persistBeforeRestart(context: Context): Boolean = context.defaultSharedPreferences.edit().commit()
}
