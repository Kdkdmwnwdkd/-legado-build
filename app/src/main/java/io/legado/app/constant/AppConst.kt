package io.legado.app.constant

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.annotation.Keep
import io.legado.app.BuildConfig
import io.legado.app.help.update.AppVariant
import org.apache.commons.lang3.time.FastDateFormat
import splitties.init.appCtx

@Suppress("ConstPropertyName")
@SuppressLint("SimpleDateFormat")
object AppConst {

    const val APP_TAG = "Legado"

    const val channelIdDownload = "channel_download"
    const val channelIdReadAloud = "channel_read_aloud"
    const val channelIdWeb = "channel_web"
    const val channelIdAiTask = "channel_ai_task"

    const val UA_NAME = "User-Agent"

    const val MAX_THREAD = 9

    const val DEFAULT_WEBDAV_ID = -1L

    /**
     * 本应用是独立分支产品，不再根据签名证书指纹判断版本类型。
     * 只要不是 debug 构建，一律视为正式版（OFFICIAL），
     * 这样更换签名密钥不会导致更新通道失效。
     */

    val timeFormat: FastDateFormat by lazy {
        FastDateFormat.getInstance("HH:mm")
    }

    val dateFormat: FastDateFormat by lazy {
        FastDateFormat.getInstance("yyyy/MM/dd HH:mm")
    }

    val fileNameFormat: FastDateFormat by lazy {
        FastDateFormat.getInstance("yy-MM-dd-HH-mm-ss")
    }

    const val imagePathKey = "imagePath"

    val menuViewNames = arrayOf(
        "com.android.internal.view.menu.ListMenuItemView",
        "androidx.appcompat.view.menu.ListMenuItemView"
    )

    @SuppressLint("PrivateResource")
    val sysElevation = appCtx.resources
        .getDimension(com.google.android.material.R.dimen.design_appbar_elevation)
        .toInt()

    val androidId: String by lazy {
        Settings.System.getString(appCtx.contentResolver, Settings.Secure.ANDROID_ID) ?: "null"
    }

    val appInfo: AppInfo by lazy {
        val appInfo = AppInfo()
        @Suppress("DEPRECATION")
        appCtx.packageManager.getPackageInfo(appCtx.packageName, PackageManager.GET_ACTIVITIES)
            ?.let {
                appInfo.versionName = it.versionName!!
                appInfo.appVariant = when {
                    it.packageName.contains("releaseA") -> AppVariant.BETA_RELEASEA
                    it.packageName.contains("releaseS") -> AppVariant.BETA_RELEASES
                    BuildConfig.DEBUG -> AppVariant.BETA_RELEASE
                    else -> AppVariant.OFFICIAL
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    appInfo.versionCode = it.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    appInfo.versionCode = it.versionCode.toLong()
                }
            }
        appInfo
    }

    val charsets =
        arrayListOf("UTF-8", "GB2312", "GB18030", "GBK", "Unicode", "UTF-16", "UTF-16LE", "ASCII")

    @Keep
    data class AppInfo(
        var versionCode: Long = 0L,
        var versionName: String = "",
        var appVariant: AppVariant = AppVariant.UNKNOWN
    )

    /**
     * The authority of a FileProvider defined in a <provider> element in your app's manifest.
     */
    const val authority = BuildConfig.APPLICATION_ID + ".fileProvider"

}
