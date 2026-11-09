package com.shimmer.reader.ui.association

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import com.shimmer.reader.base.BaseViewModel
import com.shimmer.reader.utils.inputStream
import com.shimmer.reader.utils.jsonPath
import splitties.init.appCtx
import com.shimmer.reader.R

abstract class BaseAssociationViewModel(application: Application) : BaseViewModel(application) {

    val successLive = MutableLiveData<Pair<String, String>>()
    val errorLive = MutableLiveData<String>()

    /**
     * 纯 JS 单文件书源：不走 JSON 结构判断，直接交给书源导入对话框解析脚本
     */
    fun importJsSource(uri: Uri) {
        successLive.postValue("bookSource" to uri.toString())
    }

    fun importJson(uri: Uri) {
        val map = readFirstJsonObject(uri)

        when {
            map.containsKey("bookSourceUrl") ->
                successLive.postValue("bookSource" to uri.toString())

            map.containsKey("sourceUrl") ->
                successLive.postValue("rssSource" to uri.toString())

            map.containsKey("pattern") ->
                successLive.postValue("replaceRule" to uri.toString())

            map.containsKey("themeName") ->
                successLive.postValue("theme" to uri.toString())

            map.containsKey("showRule") ->
                successLive.postValue("dictRule" to uri.toString())

            map.containsKey("name") && map.containsKey("rule") ->
                successLive.postValue("txtRule" to uri.toString())

            map.containsKey("name") && map.containsKey("url") ->
                successLive.postValue("httpTts" to uri.toString())

            else -> errorLive.postValue(appCtx.getString(R.string.wrong_format))
        }
    }

    private fun readFirstJsonObject(uri: Uri): Map<String, *> {
        uri.inputStream(context).getOrThrow().use { input ->
            runCatching {
                jsonPath.parse(input).read<Map<String, *>>("$[0]")
            }.getOrNull()?.let { return it }
        }
        return uri.inputStream(context).getOrThrow().use {
            jsonPath.parse(it).read("$")
        }
    }

}
