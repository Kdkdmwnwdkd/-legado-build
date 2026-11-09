package com.shimmer.reader.help.update

import android.os.Build
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import com.shimmer.reader.exception.NoStackTraceException
import java.time.Instant

data class AppReleaseInfo(
    val appVariant: AppVariant,
    val createdAt: Long,
    val note: String,
    val name: String,
    val downloadUrl: String,
    val assetUrl: String,
    /** 所在 release 的 tag，资产名解析不出时才用它兜底 */
    val tagName: String = ""
) {
    /**
     * 本仓库的资产命名：shimmer_3.26.110817_110817.apk
     * 第一段产品名，第二段版本名，第三段 versionCode。
     */
    private val nightlyNameRegex = Regex("""^.+?_(\d+(?:\.\d+)+)_(\d+)\.apk$""")

    /** 上游格式：xxx_yyy_zzz.apk，保持兼容，避免影响其他渠道 */
    private val apkNameRegex = Regex("""^.+?_.+?_([^_]+)(?:_(\d+))?\.apk$""")

    private val nightlyMatch = nightlyNameRegex.matchEntire(name)
    private val legacyMatch = apkNameRegex.matchEntire(name)

    val versionName: String = (
        nightlyMatch?.groupValues?.getOrNull(1)
            ?: legacyMatch?.groupValues?.getOrNull(1)
            ?: tagName
        ).removePrefix("v").trim()

    val versionCode: Long = nightlyMatch?.groupValues?.getOrNull(2)?.toLongOrNull()
        ?: legacyMatch?.groupValues?.getOrNull(2)?.toLongOrNull()
        ?: 0L

    val abi: String? = when {
        name.contains("arm64-v8a", ignoreCase = true) -> "arm64-v8a"
        name.contains("armeabi-v7a", ignoreCase = true) -> "armeabi-v7a"
        name.contains("x86_64", ignoreCase = true) -> "x86_64"
        name.contains("x86", ignoreCase = true) -> "x86"
        else -> null
    }

    fun supportsDeviceAbi(): Boolean {
        val assetAbi = abi ?: return true
        return Build.SUPPORTED_ABIS.any { it.equals(assetAbi, ignoreCase = true) }
    }
}

enum class AppVariant {
    OFFICIAL,
    BETA_RELEASEA,
    BETA_RELEASES,
    BETA_RELEASE,
    UNKNOWN;

    fun isBeta(): Boolean {
        return this == BETA_RELEASE || this == BETA_RELEASEA
    }

}

@Keep
data class GithubRelease(
    val assets: List<Asset>?,
    val body: String,
    @SerializedName("prerelease")
    val isPreRelease: Boolean,
    @SerializedName("tag_name")
    val tagName: String = ""
) {
    fun gitReleaseToAppReleaseInfo(): List<AppReleaseInfo> {
        assets ?: throw NoStackTraceException("获取新版本出错")
        return assets
            .filter { it.isValid }
            .map { it.assetToAppReleaseInfo(isPreRelease, body, tagName) }
    }
}
@Keep
data class Asset(
    @SerializedName("browser_download_url")
    val apkUrl: String,
    @SerializedName("content_type")
    val contentType: String,
    @SerializedName("created_at")
    val createdAt: String,
    @SerializedName("download_count")
    val downloadCount: Int,
    val id: Int,
    val name: String,
    val state: String,
    val url: String
) {
    val isValid: Boolean
        get() = (contentType == "application/vnd.android.package-archive") && (state == "uploaded")

    fun assetToAppReleaseInfo(
        preRelease: Boolean,
        note: String,
        tagName: String = ""
    ): AppReleaseInfo {
        val instant = Instant.parse(createdAt)
        val timestamp: Long = instant.toEpochMilli()

        // 只认明确的渠道标记。以前用 name.contains("release") 判定，
        // 结果 app-app-release.apk 这类普通资产也被当成 beta，正式通道永远查不到更新。
        val appVariant = when {
            name.contains("releaseA", ignoreCase = true) -> AppVariant.BETA_RELEASEA
            name.contains("releaseS", ignoreCase = true) -> AppVariant.BETA_RELEASES
            else -> AppVariant.OFFICIAL
        }

        return AppReleaseInfo(
            appVariant = appVariant,
            createdAt = timestamp,
            note = note,
            name = name,
            downloadUrl = apkUrl,
            assetUrl = url,
            tagName = tagName
        )
    }
}
