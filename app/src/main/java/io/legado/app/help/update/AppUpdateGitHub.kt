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
import kotlinx.coroutines.CoroutineScope

@Keep
@Suppress("unused")
object AppUpdateGitHub : AppUpdate.AppUpdateInterface {

    /** 本产品唯一的发布仓库。换仓库只改这一处。 */
    private const val RELEASES_URL =
        "https://api.github.com/repos/Kdkdmwnwdkd/-legado-build/releases?per_page=10"

    private val checkVariant: AppVariant
        get() = AppVariant.OFFICIAL

    private suspend fun getLatestRelease(): List<AppReleaseInfo> {
        // 默认走加速镜像，镜像挂掉或返回的不是 JSON 时自动回退直连，
        // 保证「检查更新」不会因为一个第三方服务失效就彻底不可用。
        val proxied = AppUpdateConfig.applyGithubProxy(RELEASES_URL)
        val raw = runCatching { loadReleases(proxied) }.getOrElse { proxyError ->
            if (proxied == RELEASES_URL) throw proxyError
            runCatching { loadReleases(RELEASES_URL) }.getOrElse { throw proxyError }
        }
        return raw
            // 本仓库同时发正式版和 nightly（prerelease），两种都要能被识别，
            // 否则装了 nightly 的用户永远「已是最新」。
            .flatMap { it.gitReleaseToAppReleaseInfo() }
            // 资产名里没有版本号的（早期命名）跳过，避免提示了更新却无法比较大小
            .filter { it.versionCode > 0L || it.versionName.isNotBlank() }
            .sortedWith(
                compareByDescending<AppReleaseInfo> { it.versionCode }
                    .thenByDescending { it.createdAt }
            )
    }

    private suspend fun loadReleases(url: String): List<GithubRelease> {
        val res = okHttpClient.newCallResponse {
            url(url)
        }
        if (!res.isSuccessful) {
            throw NoStackTraceException("获取新版本出错(${res.code})")
        }
        val body = res.body.text()
        if (body.isBlank()) {
            throw NoStackTraceException("获取新版本出错")
        }
        return GSON.fromJsonArray<GithubRelease>(body)
            .getOrElse {
                throw NoStackTraceException("获取新版本出错" + it.localizedMessage)
            }
    }

    override fun check(
        scope: CoroutineScope,
    ): Coroutine<AppUpdate.UpdateInfo> {
        return Coroutine.async(scope) {
            checkNow()
        }.timeout(10000)
    }

    suspend fun checkNow(): AppUpdate.UpdateInfo {
        val current = AppConst.appInfo
        return getLatestRelease()
            .filter { it.appVariant == checkVariant }
            .filter { it.supportsDeviceAbi() }
            // 版本名不是数字形式（例如 tag 是 nightly 又解析不出资产名）时直接跳过，
            // 否则会拿 "nightly" 和 "3.26.x" 做字符串比较，永远提示有新版本。
            .filter {
                it.versionCode > 0L ||
                    Regex("""^\d+(?:\.\d+)+""").containsMatchIn(it.versionName)
            }
            .firstOrNull {
                if (it.versionCode > 0L) {
                    it.versionCode > current.versionCode
                } else {
                    AppUpdate.compareVersionName(it.versionName, current.versionName) > 0
                }
            }
            ?.let {
                AppUpdate.UpdateInfo(
                    tagName = it.versionName.ifBlank { it.tagName },
                    updateLog = it.note,
                    downloadUrl = AppUpdateConfig.applyGithubProxy(it.downloadUrl),
                    fileName = it.name,
                    versionCode = it.versionCode
                )
            }
            ?: throw AppUpdate.latestVersionError()
    }
}
