package com.shimmer.reader.ui.video

import android.app.Application
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import com.script.rhino.runScriptWithContext
import com.shimmer.reader.base.BaseViewModel
import com.shimmer.reader.constant.AppLog
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.BookSource
import com.shimmer.reader.data.entities.RssSource
import com.shimmer.reader.model.VideoPlay
import com.shimmer.reader.ui.login.SourceLoginJsExtensions
import com.shimmer.reader.utils.toastOnUi

class VideoPlayerViewModel(application: Application) : BaseViewModel(application) {
    val upStarMenuData = MutableLiveData<Boolean>()
    fun removeFromBookshelf(success: (() -> Unit)?) {
        val bookUrl = VideoPlay.book?.bookUrl
        execute {
            bookUrl?.let(appDb.bookDao::deleteIfNotShelf)
        }.onError {
            AppLog.put("删除临时视频书失败: bookUrl=$bookUrl", it)
        }.onFinally {
            success?.invoke()
        }
    }

    fun addFavorite(success: () -> Unit) {
        execute {
            VideoPlay.rssStar ?: VideoPlay.rssRecord?.toStar()?.let {
                appDb.rssStarDao.insert(it)
                VideoPlay.rssStar = it
            }
        }.onSuccess {
            upStarMenuData.postValue(true)
            success.invoke()
        }
    }

    fun updateFavorite(title: String?, group: String?) {
        execute {
            (VideoPlay.rssStar ?: VideoPlay.rssRecord?.toStar())?.let {
                it.title = title ?: it.title
                it.group = group ?: it.group
                appDb.rssStarDao.update(it)
                VideoPlay.rssStar = it
            }
        }.onSuccess {
            upStarMenuData.postValue(true)
        }
    }

    fun delFavorite() {
        execute {
            VideoPlay.rssStar?.let {
                appDb.rssStarDao.delete(it.origin, it.link)
                VideoPlay.rssRecord = it.toRecord()
                VideoPlay.rssStar = null
            }
        }.onSuccess {
            upStarMenuData.postValue(true)
        }
    }

    fun upSource(success: (() -> Unit)? = null) {
        when (val source = VideoPlay.source) {
            is BookSource -> {
                VideoPlay.source = appDb.bookSourceDao.getBookSource(source.getKey())?.also {
                    success?.invoke()
                }
            }
            is RssSource -> {
                VideoPlay.source = appDb.rssSourceDao.getByKey(source.getKey())
            }
        }
    }

    fun onButtonClick(activity: AppCompatActivity, name: String, click: String) {
        val source = VideoPlay.source ?: return
        val book = VideoPlay.book ?: return
        execute {
            val java = SourceLoginJsExtensions(activity, source)
            runScriptWithContext {
                source.evalJS(click) {
                    put("result", null)
                    put("java", java)
                    put("book", book)
                }
            }
        }.onError {
            AppLog.put("${source.getTag()}: ${it.localizedMessage}", it)
            context.toastOnUi("$name click error\n${it.localizedMessage}")
        }
    }
}
