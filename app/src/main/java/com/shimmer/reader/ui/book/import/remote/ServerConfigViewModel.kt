package com.shimmer.reader.ui.book.import.remote

import android.app.Application
import com.shimmer.reader.R
import com.shimmer.reader.base.BaseViewModel
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.Server
import com.shimmer.reader.utils.toastOnUi
import splitties.init.appCtx

class ServerConfigViewModel(application: Application): BaseViewModel(application) {

    var mServer: Server? = null

    fun init(id: Long?, onSuccess: () -> Unit) {
        //mServer不为空可能是旋转屏幕界面重新创建,不用更新数据
        if (mServer != null) return
        execute {
            mServer = if (id != null) {
                appDb.serverDao.get(id)
            } else {
                Server()
            }
        }.onSuccess {
            onSuccess.invoke()
        }
    }

    fun save(server: Server, onSuccess: () -> Unit) {
        execute {
            mServer?.let {
                appDb.serverDao.delete(it)
            }
            mServer = server
            appDb.serverDao.insert(server)
        }.onSuccess {
            onSuccess.invoke()
        }.onError {
            context.toastOnUi(appCtx.getString(R.string.toast_save_failed, it.localizedMessage))
        }
    }

}