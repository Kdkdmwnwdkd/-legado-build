package com.shimmer.reader.ui.config

import android.app.Application
import android.content.Context
import com.shimmer.reader.R
import com.shimmer.reader.base.BaseViewModel
import com.shimmer.reader.data.appDb
import com.shimmer.reader.help.AppCloudStorage
import com.shimmer.reader.help.book.BookHelp
import com.shimmer.reader.utils.FileUtils
import com.shimmer.reader.utils.restart
import com.shimmer.reader.utils.toastOnUi
import kotlinx.coroutines.delay
import splitties.init.appCtx

class ConfigViewModel(application: Application) : BaseViewModel(application) {

    fun upCloudStorageConfig() {
        execute {
            AppCloudStorage.upConfig()
        }
    }

    fun upWebDavConfig() = upCloudStorageConfig()

    fun clearCache() {
        execute {
            BookHelp.clearCache()
            FileUtils.delete(context.cacheDir.absolutePath)
        }.onSuccess {
            context.toastOnUi(R.string.clear_cache_success)
        }
    }

    fun clearWebViewData() {
        execute {
            FileUtils.delete(context.getDir("webview", Context.MODE_PRIVATE))
            FileUtils.delete(context.getDir("hws_webview", Context.MODE_PRIVATE), true)
            context.toastOnUi(R.string.clear_webview_data_success)
            delay(3000)
            appCtx.restart()
        }
    }

    fun shrinkDatabase() {
        execute {
            appDb.openHelper.writableDatabase.execSQL("VACUUM")
        }.onSuccess {
            context.toastOnUi(R.string.success)
        }
    }

}
