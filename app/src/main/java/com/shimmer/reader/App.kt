package com.shimmer.reader

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import com.github.liuyueyi.quick.transfer.constants.TransType
import com.jeremyliao.liveeventbus.LiveEventBus
import com.jeremyliao.liveeventbus.logger.DefaultLogger
import com.script.rhino.ReadOnlyJavaObject
import com.script.rhino.RhinoScriptEngine
import com.script.rhino.RhinoWrapFactory
import com.shimmer.reader.base.AppContextWrapper
import com.shimmer.reader.constant.AppConst.channelIdDownload
import com.shimmer.reader.constant.AppConst.channelIdAiTask
import com.shimmer.reader.constant.AppConst.channelIdReadAloud
import com.shimmer.reader.constant.AppConst.channelIdWeb
import com.shimmer.reader.constant.PreferKey
import com.shimmer.reader.constant.AppLog
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.Book
import com.shimmer.reader.data.entities.BookChapter
import com.shimmer.reader.data.entities.BookSource
import com.shimmer.reader.data.entities.HttpTTS
import com.shimmer.reader.data.entities.RssSource
import com.shimmer.reader.data.entities.rule.BookInfoRule
import com.shimmer.reader.data.entities.rule.ContentRule
import com.shimmer.reader.data.entities.rule.ExploreRule
import com.shimmer.reader.data.entities.rule.SearchRule
import com.shimmer.reader.help.AppFreezeMonitor
import com.shimmer.reader.help.AppCloudStorage
import com.shimmer.reader.help.CrashHandler
import com.shimmer.reader.help.DefaultData
import com.shimmer.reader.help.DispatchersMonitor
import com.shimmer.reader.help.LifecycleHelp
import com.shimmer.reader.help.RuleBigDataHelp
import com.shimmer.reader.help.book.BookHelp
import com.shimmer.reader.help.config.AppConfig
import com.shimmer.reader.help.config.AppearanceKitManager
import com.shimmer.reader.help.config.ReadBookConfig
import com.shimmer.reader.help.config.ThemeConfig.applyDayNight
import com.shimmer.reader.help.config.ThemeConfig.applyDayNightInit
import com.shimmer.reader.lib.theme.ThemeRuntimeKeys
import com.shimmer.reader.help.coroutine.Coroutine
import com.shimmer.reader.help.http.Cronet
import com.shimmer.reader.help.http.ObsoleteUrlFactory
import com.shimmer.reader.help.http.okHttpClient
import com.shimmer.reader.help.rhino.NativeBaseSource
import com.shimmer.reader.help.source.SourceHelp
import com.shimmer.reader.help.storage.Backup
import com.shimmer.reader.help.storage.ReaderDataRepair
import com.shimmer.reader.help.storage.RestoreJournal
import com.shimmer.reader.model.BookCover
import com.shimmer.reader.service.AutoTaskService
import com.shimmer.reader.utils.ChineseUtils
import com.shimmer.reader.utils.LogUtils
import com.shimmer.reader.utils.defaultSharedPreferences
import com.shimmer.reader.utils.getPrefBoolean
import com.shimmer.reader.utils.isDebuggable
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.chromium.base.ThreadUtils
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level

class App : Application() {

    private lateinit var oldConfig: Configuration
    private val themeConfigurationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var themeConfigurationJob: Job? = null
    private val themeConfigurationMutex = Mutex()
    private val themeConfigurationGeneration = AtomicLong()

    override fun onCreate() {
        super.onCreate()
        CrashHandler(this)
        if (isDebuggable) {
            ThreadUtils.setThreadAssertsDisabledForTesting(true)
        }
        repairReaderDataBeforeUi()
        oldConfig = Configuration(resources.configuration)
        applyDayNightInit(this)
        registerActivityLifecycleCallbacks(LifecycleHelp)
        defaultSharedPreferences.registerOnSharedPreferenceChangeListener(AppConfig)
        Coroutine.async {
            RestoreJournal.recoverIfNeeded("应用启动检测到上次恢复未完成")
            RestoreJournal.markStableIfPending()
            LogUtils.init(this@App)
            LogUtils.d("App", "onCreate")
            LogUtils.logDeviceInfo()
            //预下载Cronet so
            Cronet.preDownload()
            createNotificationChannels()
            LiveEventBus.config()
                .lifecycleObserverAlwaysActive(true)
                .autoClear(false)
                .enableLogger(AppConfig.recordLog)
                .setLogger(EventLogger())
            DefaultData.upVersion()
            AppFreezeMonitor.init(this@App)
            DispatchersMonitor.init()
            URL.setURLStreamHandlerFactory(ObsoleteUrlFactory(okHttpClient))
            launch { installGmsTlsProvider(appCtx) }
            initRhino()
            //初始化封面
            BookCover.toString()
            //清除过期数据
            appDb.cacheDao.clearDeadline(System.currentTimeMillis())
            if (getPrefBoolean(PreferKey.autoClearExpired, true)) {
                val clearTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
                appDb.searchBookDao.clearExpired(clearTime)
            }
            appDb.searchBookDao.clearUnsafeRows()
            RuleBigDataHelp.clearInvalid()
            BookHelp.clearInvalidCache()
            Backup.clearCache()
            ReadBookConfig.clearBgAndCache()
//            ThemeConfig.clearBg() //每次手动切换主题时清理多余图片
            //初始化简繁转换引擎
            when (AppConfig.chineseConverterType) {
                1 -> {
                    ChineseUtils.fixT2sDict()
                    ChineseUtils.preLoad(true, TransType.TRADITIONAL_TO_SIMPLE)
                }

                2 -> ChineseUtils.preLoad(true, TransType.SIMPLE_TO_TRADITIONAL)
            }
            //调整排序序号
            SourceHelp.adjustSortNumber()
            //同步阅读记录
            if (AppConfig.syncBookProgress) {
                AppCloudStorage.downloadAllBookProgress()
            }
            AutoTaskService.restoreSchedule(appCtx)
        }
    }

    private fun repairReaderDataBeforeUi() {
        runCatching {
            appDb.openHelper.writableDatabase
        }.onFailure {
            Log.e("App", "open database before reader data repair failed", it)
        }
        ReaderDataRepair.repairOnAppStart()
    }

    override fun attachBaseContext(base: Context) {
        runCatching {
            ThemeRuntimeKeys.migrateLegacyNightValues(base)
        }
        super.attachBaseContext(AppContextWrapper.wrap(base))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        //只处理真正的亮暗切换，忽略车载/底座等 uiMode 类型变化，避免无谓重建
        val nightChanged = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
            (oldConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        if (nightChanged && AppConfig.themeMode == "0") {
            val expectedNight = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            val generation = themeConfigurationGeneration.incrementAndGet()
            themeConfigurationJob?.cancel()
            themeConfigurationJob = themeConfigurationScope.launch {
                try {
                    themeConfigurationMutex.lock()
                    try {
                        currentCoroutineContext().ensureActive()
                        if (generation != themeConfigurationGeneration.get()) return@launch
                        val applied = AppearanceKitManager.applyCurrentModeTheme(this@App, expectedNight)
                        currentCoroutineContext().ensureActive()
                        if (!applied || generation != themeConfigurationGeneration.get()) return@launch
                    } finally {
                        themeConfigurationMutex.unlock()
                    }
                    withContext(Dispatchers.Main.immediate) {
                        //必须用 newConfig 的 expectedNight，resources.configuration 被包装上下文固定为启动模式，跟随系统切换后不更新，否则会跳过 applyDayNight
                        if (generation == themeConfigurationGeneration.get() &&
                            AppConfig.themeMode == "0"
                        ) {
                            applyDayNight(this@App, expectedNight)
                        }
                    }
                } catch (_: CancellationException) {
                    // A newer system theme request superseded this one.
                } catch (e: Throwable) {
                    AppLog.put("apply system theme failed\n${e.localizedMessage}", e)
                }
            }
        }
        oldConfig = Configuration(newConfig)
    }

    /**
     * 尝试在安装了GMS的设备上(GMS或者MicroG)使用GMS内置的Conscrypt
     * 作为首选JCE提供程序，而使Okhttp在低版本Android上
     * 能够启用TLSv1.3
     * https://f-droid.org/zh_Hans/2020/05/29/android-updates-and-tls-connections.html
     * https://developer.android.google.cn/reference/javax/net/ssl/SSLSocket
     *
     * @param context
     * @return
     */
    private fun installGmsTlsProvider(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return
        }
        try {
            val gmsPackageName = "com.google.android.gms"
            val appInfo = packageManager.getApplicationInfo(gmsPackageName, 0)
            if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                return
            }
            val gms = context.createPackageContext(
                gmsPackageName,
                CONTEXT_INCLUDE_CODE or CONTEXT_IGNORE_SECURITY
            )
            gms.classLoader
                .loadClass("com.google.android.gms.common.security.ProviderInstallerImpl")
                .getMethod("insertProvider", Context::class.java)
                .invoke(null, gms)
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 创建通知ID
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val downloadChannel = NotificationChannel(
            channelIdDownload,
            getString(R.string.action_download),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val readAloudChannel = NotificationChannel(
            channelIdReadAloud,
            getString(R.string.read_aloud),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val webChannel = NotificationChannel(
            channelIdWeb,
            getString(R.string.web_service),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val aiTaskChannel = NotificationChannel(
            channelIdAiTask,
            "AI任务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        //向notification manager 提交channel
        notificationManager.createNotificationChannels(
            listOf(
                downloadChannel,
                readAloudChannel,
                webChannel,
                aiTaskChannel
            )
        )
    }

    private fun initRhino() {
        RhinoScriptEngine
        RhinoWrapFactory.register(BookSource::class.java, NativeBaseSource.factory)
        RhinoWrapFactory.register(RssSource::class.java, NativeBaseSource.factory)
        RhinoWrapFactory.register(HttpTTS::class.java, NativeBaseSource.factory)
        RhinoWrapFactory.register(ExploreRule::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(SearchRule::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(BookInfoRule::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(ContentRule::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(BookChapter::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(Book.ReadConfig::class.java, ReadOnlyJavaObject.factory)
    }

    class EventLogger : DefaultLogger() {

        override fun log(level: Level, msg: String) {
            super.log(level, msg)
            LogUtils.d(TAG, msg)
        }

        override fun log(level: Level, msg: String, th: Throwable?) {
            super.log(level, msg, th)
            LogUtils.d(TAG, "$msg\n${th?.stackTraceToString()}")
        }

        companion object {
            private const val TAG = "[LiveEventBus]"
        }
    }

}
