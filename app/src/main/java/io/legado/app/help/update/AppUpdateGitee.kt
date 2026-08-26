package io.legado.app.help.update

import androidx.annotation.Keep
import io.legado.app.constant.AppConst
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CoroutineScope

@Keep
@Suppress("unused")
object AppUpdateGitee : AppUpdate.AppUpdateInterface {

    private val checkVariant: AppVariant
        get() = AppVariant.OFFICIAL

    private suspend fun getLatestRelease(): List<AppReleaseInfo> {
        val lastReleaseUrl = if (checkVariant.isBeta()) {
            "https://gitee.com/api/v5/repos/zziji/legado/releases/latest"
        } else {
            "https://gitee.com/api/v5/repos/zziji/legado/releases?page=1&per_page=3&direction=desc"
        }
        val res = okHttpClient.newCallResponse {
            url(lastReleaseUrl)
        }
        if (!res.isSuccessful) {
            throw NoStackTraceException("获取新版本出错(${res.code})")
        }
        val body = res.body.text()
        if (body.isBlank()) {
            throw NoStackTraceException("获取新版本出错")
        }
        if (!checkVariant.isBeta()) {
            return GSON.fromJsonArray<GiteeRelease>(body)
                .getOrElse {
                    throw NoStackTraceException("获取新版本出错" + it.localizedMessage)
                }
                .filterNot { it.prerelease }
                .flatMap { it.gitReleaseToAppReleaseInfo() }
                .sortedByDescending { it.createdAt }
        }
        return GSON.fromJsonObject<GiteeRelease>(body)
            .getOrElse {
                throw NoStackTraceException("获取新版本出错" + it.localizedMessage)
            }
            .gitReleaseToAppReleaseInfo()
            .sortedByDescending { it.createdAt }
    }

    override fun check(
        scope: CoroutineScope,
    ): Coroutine<AppUpdate.UpdateInfo> {
        return Coroutine.async(scope) {
            checkNow()
        }.timeout(10000)
    }

    suspend fun checkNow(): AppUpdate.UpdateInfo {
        return getLatestRelease()
            .filter {
                it.appVariant == checkVariant
            }
            .filter { it.supportsDeviceAbi() }
            .firstOrNull {
                if (it.versionCode > 0L) {
                    it.versionCode > AppConst.appInfo.versionCode
                } else {
                    it.versionName > AppConst.appInfo.versionName
                }
            }
            ?.let {
                AppUpdate.UpdateInfo(
                    tagName = it.versionName,
                    updateLog = it.note,
                    downloadUrl = it.downloadUrl,
                    fileName = it.name,
                    versionCode = it.versionCode
                )
            }
            ?: throw AppUpdate.latestVersionError()
    }
}
