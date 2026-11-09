package com.shimmer.reader.ui.login

import android.webkit.JavascriptInterface
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.shimmer.reader.R
import com.shimmer.reader.constant.EventBus
import com.shimmer.reader.data.entities.BaseSource
import com.shimmer.reader.data.entities.HttpTTS
import com.shimmer.reader.model.ReadAloud
import com.shimmer.reader.model.ReadBook
import com.shimmer.reader.ui.rss.read.RssJsExtensions
import com.shimmer.reader.ui.widget.dialog.BottomWebViewDialog
import com.shimmer.reader.utils.FileUtils
import com.shimmer.reader.utils.postEvent
import com.shimmer.reader.utils.sendToClip
import com.shimmer.reader.utils.showDialogFragment
import com.shimmer.reader.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference

@Suppress("unused")
class SourceLoginJsExtensions(
    activity: AppCompatActivity?,
    source: BaseSource?,
    bookType: Int = 0,
    callback: Callback? = null
) : RssJsExtensions(activity, source, bookType) {
    private val callbackRef: WeakReference<Callback> = WeakReference(callback)
    interface Callback {
        fun upUiData(data: Map<String, Any?>?)
        fun reUiView(deltaUp: Boolean = false)
        fun showBrowser(
            url: String,
            html: String? = null,
            preloadJs: String? = null,
            config: String? = null
        ): Boolean = false
        fun open(
            name: String,
            url: String? = null,
            title: String? = null,
            origin: String? = null
        ): Boolean = false
    }

    fun upLoginData(data: Map<String, Any?>?) {
        callbackRef.get()?.upUiData(data)
    }

    @JvmOverloads
    fun reLoginView(deltaUp: Boolean = false) {
        callbackRef.get()?.reUiView(deltaUp)
    }

    fun refreshExplore() {
        callbackRef.get()?.reUiView()
    }

    override fun onOpen(
        name: String,
        url: String?,
        title: String?,
        origin: String?
    ): Boolean {
        return callbackRef.get()?.open(name, url, title, origin) == true
    }

    fun refreshBookInfo() {
        postEvent(EventBus.REFRESH_BOOK_INFO, true)
    }

    fun refreshBookToc() {
        postEvent(EventBus.REFRESH_BOOK_TOC, true)
    }

    fun refreshContent() {
        postEvent(EventBus.REFRESH_BOOK_CONTENT, true)
    }

    @JavascriptInterface
    fun refreshParagraph(): Boolean {
        return ReadBook.refreshCurrentParagraphRuleResult()
    }

    fun copyText(text: String) {
        activityRef.get()?.sendToClip(text)
    }

    fun clearTtsCache() {
        if (getSource() !is HttpTTS) return
        val activity = activityRef.get() ?: return
        activity.lifecycleScope.launch(IO) {
            ReadAloud.upReadAloudClass()
            val ttsFolderPath = "${activity.cacheDir.absolutePath}${File.separator}httpTTS${File.separator}"
            FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
                FileUtils.delete(it.absolutePath)
            }
            activity.toastOnUi(R.string.clear_cache_success)
        }
    }

    @JvmOverloads
    fun showBrowser(url: String, html: String? = null, preloadJs: String? = null, config: String? = null) {
        val source = getSource() ?: return
        if (callbackRef.get()?.showBrowser(url, html, preloadJs, config) == true) {
            return
        }
        val activity = activityRef.get() ?: return
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            // 从弹出菜单等瞬时上下文触发时活动可能不在 STARTED，提交 DialogFragment 事务会异常退出；
            // 加生命周期守卫 + 兜底捕获，避免崩溃。
            if (!activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                return@runOnUiThread
            }
            runCatching {
                activity.showDialogFragment(
                    BottomWebViewDialog(
                        source.getKey(),
                        bookType,
                        url,
                        html,
                        preloadJs,
                        config
                    )
                )
            }
        }
    }

}
