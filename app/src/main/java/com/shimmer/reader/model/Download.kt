package com.shimmer.reader.model

import android.content.Context
import com.shimmer.reader.constant.IntentAction
import com.shimmer.reader.service.DownloadService
import com.shimmer.reader.utils.startService

object Download {


    fun start(context: Context, url: String, fileName: String, headers: Map<String, String> = emptyMap()) {
        context.startService<DownloadService> {
            action = IntentAction.start
            putExtra("url", url)
            putExtra("fileName", fileName)
            putExtra("headerNames", headers.keys.toTypedArray())
            putExtra("headerValues", headers.values.toTypedArray())
        }
    }

}
