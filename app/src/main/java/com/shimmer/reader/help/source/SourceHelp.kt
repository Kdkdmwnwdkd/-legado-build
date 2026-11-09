package com.shimmer.reader.help.source

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.shimmer.reader.R
import com.shimmer.reader.constant.SourceType
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.BaseSource
import com.shimmer.reader.data.entities.BookSource
import com.shimmer.reader.data.entities.BookSourcePart
import com.shimmer.reader.data.entities.RssSource
import com.shimmer.reader.help.AppCacheManager
import com.shimmer.reader.help.config.SourceConfig
import com.shimmer.reader.help.coroutine.Coroutine
import com.shimmer.reader.lib.permission.Permissions
import com.shimmer.reader.lib.permission.PermissionsCompat
import com.shimmer.reader.model.AudioPlay
import com.shimmer.reader.model.ReadBook
import com.shimmer.reader.model.ReadManga
import com.shimmer.reader.model.VideoPlay
import com.shimmer.reader.service.VideoPlayService
import com.shimmer.reader.ui.video.VideoPlayerActivity
import com.shimmer.reader.utils.EncoderUtils
import com.shimmer.reader.utils.NetworkUtils
import com.shimmer.reader.utils.splitNotBlank
import com.shimmer.reader.utils.startActivity
import com.shimmer.reader.utils.toastOnUi
import splitties.init.appCtx

object SourceHelp {

    private val list18Plus by lazy {
        try {
            return@lazy String(appCtx.assets.open("18PlusList.txt").readBytes())
                .splitNotBlank("\n").map {
                    EncoderUtils.base64Decode(it)
                }.toHashSet()
        } catch (_: Exception) {
            return@lazy emptySet()
        }
    }

    fun getSource(key: String?): BaseSource? {
        key ?: return null
        if (ReadBook.bookSource?.bookSourceUrl == key) {
            return ReadBook.bookSource
        } else if (AudioPlay.bookSource?.bookSourceUrl == key) {
            return AudioPlay.bookSource
        } else if (ReadManga.bookSource?.bookSourceUrl == key) {
            return ReadManga.bookSource
        } else if (VideoPlay.source?.getKey() == key) {
            return VideoPlay.source
        }
        return appDb.bookSourceDao.getBookSource(key)
            ?: appDb.rssSourceDao.getByKey(key)
    }

    fun getSource(key: String?, @SourceType.Type type: Int): BaseSource? {
        key ?: return null
        return when (type) {
            SourceType.book -> appDb.bookSourceDao.getBookSource(key)
            SourceType.rss -> appDb.rssSourceDao.getByKey(key)
            else -> null
        }
    }

    fun deleteSource(key: String, @SourceType.Type type: Int) {
        when (type) {
            SourceType.book -> deleteBookSource(key)
            SourceType.rss -> deleteRssSource(key)
        }
    }

    fun deleteBookSourceParts(sources: List<BookSourcePart>) {
        appDb.runInTransaction {
            sources.forEach {
                deleteBookSourceInternal(it.bookSourceUrl)
            }
        }
        AppCacheManager.clearSourceVariables()
    }

    fun deleteBookSources(sources: List<BookSource>) {
        appDb.runInTransaction {
            sources.forEach {
                deleteBookSourceInternal(it.bookSourceUrl)
            }
        }
        AppCacheManager.clearSourceVariables()
    }

    private fun deleteBookSourceInternal(key: String) {
        appDb.bookSourceDao.delete(key)
        appDb.cacheDao.deleteSourceVariables(key)
        SourceConfig.removeSource(key)
    }

    fun deleteBookSource(key: String) {
        deleteBookSourceInternal(key)
        AppCacheManager.clearSourceVariables()
    }

    fun deleteRssSources(sources: List<RssSource>) {
        appDb.runInTransaction {
            sources.forEach {
                deleteRssSourceInternal(it.sourceUrl)
            }
        }
        AppCacheManager.clearSourceVariables()
    }

    private fun deleteRssSourceInternal(key: String) {
        appDb.rssSourceDao.delete(key)
        appDb.rssArticleDao.delete(key)
        appDb.cacheDao.deleteSourceVariables(key)
    }

    fun deleteRssSource(key: String) {
        deleteRssSourceInternal(key)
        AppCacheManager.clearSourceVariables()
    }

    fun enableSource(key: String, @SourceType.Type type: Int, enable: Boolean) {
        when (type) {
            SourceType.book -> appDb.bookSourceDao.enable(key, enable)
            SourceType.rss -> appDb.rssSourceDao.enable(key, enable)
        }
    }

    fun insertRssSource(vararg rssSources: RssSource) {
        val rssSourcesGroup = rssSources.groupBy {
            is18Plus(it.sourceUrl)
        }
        rssSourcesGroup[true]?.forEach {
            appCtx.toastOnUi(appCtx.getString(R.string.toast_adult_site_blocked, it.sourceName))
        }
        rssSourcesGroup[false]?.let {
            appDb.rssSourceDao.insert(*it.toTypedArray())
        }
    }

    fun insertBookSource(vararg bookSources: BookSource) {
        val bookSourcesGroup = bookSources.groupBy {
            is18Plus(it.bookSourceUrl)
        }
        bookSourcesGroup[true]?.forEach {
            appCtx.toastOnUi(appCtx.getString(R.string.toast_adult_site_blocked, it.bookSourceName))
        }
        bookSourcesGroup[false]?.let {
            appDb.bookSourceDao.insert(*it.toTypedArray())
        }
        Coroutine.async {
            adjustSortNumber()
        }
    }

    private fun is18Plus(url: String?): Boolean {
        if (list18Plus.isEmpty()) {
            return false
        }
        url ?: return false
        val baseUrl = NetworkUtils.getBaseUrl(url) ?: return false
        kotlin.runCatching {
            val host = baseUrl.split("//", ".").let {
                if (it.size > 2) "${it[it.lastIndex - 1]}.${it.last()}" else return false
            }
            return list18Plus.contains(host)
        }
        return false
    }

    /**
     * 调整排序序号
     */
    fun adjustSortNumber() {
        if (
            appDb.bookSourceDao.maxOrder > 99999
            || appDb.bookSourceDao.minOrder < -99999
            || appDb.bookSourceDao.hasDuplicateOrder
        ) {
            val sources = appDb.bookSourceDao.allPart
            sources.forEachIndexed { index, bookSource ->
                bookSource.customOrder = index
            }
            appDb.bookSourceDao.upOrder(sources)
        }
    }

    fun openVideoPlayer(source: BaseSource?, url: String, title: String, isFloat: Boolean) {
        if (isFloat) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(appCtx)) {
                PermissionsCompat.Builder()
                    .addPermissions(Permissions.SYSTEM_ALERT_WINDOW)
                    .rationale(R.string.float_permission_rationale)
                    .onGranted {
                        openVideoPlayer(source, url, title, isFloat = true)
                    }
                    .onDenied {
                        appCtx.toastOnUi(R.string.toast_reopen_floating_playback)
                    }
                    .request()
                return
            }
            val intent = Intent(appCtx, VideoPlayService::class.java).apply {
                putExtra("videoUrl", url)
                putExtra("videoTitle", title)
                putExtra("sourceKey", source?.getKey())
                putExtra("sourceType", source?.getSourceType())
            }
            ContextCompat.startForegroundService(appCtx, intent)
        } else {
            appCtx.startActivity<VideoPlayerActivity> {
                putExtra("videoUrl", url)
                putExtra("videoTitle", title)
                putExtra("sourceKey", source?.getKey())
                putExtra("sourceType", source?.getSourceType())
            }
        }
    }

}
