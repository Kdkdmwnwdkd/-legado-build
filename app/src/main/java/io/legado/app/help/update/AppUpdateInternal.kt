package io.legado.app.help.update

import androidx.annotation.Keep
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

object AppUpdateInternal {

    suspend fun checkNow(): AppUpdate.UpdateInfo {
        val baseUrl = AppUpdateConfig.internalBetaUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw NoStackTraceException("内测更新地址未配置")
        val key = AppUpdateConfig.internalBetaKey
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw NoStackTraceException("内测更新密钥未配置")
        val headers = mapOf(AppUpdateConfig.INTERNAL_BETA_KEY_HEADER to key)
        val res = okHttpClient.newCallResponse {
            url(endpointUrl(baseUrl, "latest"))
            addHeaders(headers)
        }
        if (!res.isSuccessful) {
            throw NoStackTraceException("获取内测版本出错(${res.code})")
        }
        val body = res.body.text()
        if (body.isBlank()) {
            throw NoStackTraceException("获取内测版本出错")
        }
        val metadata = GSON.fromJsonObject<InternalReleaseMetadata>(body)
            .getOrElse {
                throw NoStackTraceException("获取内测版本出错" + it.localizedMessage)
            }
        val fileName = metadata.fileName?.takeIf { it.isNotBlank() }
            ?: throw NoStackTraceException("内测版本文件名缺失")
        val tagName = metadata.versionName
            ?.takeIf { it.isNotBlank() }
            ?: metadata.tagName?.takeIf { it.isNotBlank() }
            ?: versionNameFromFileName(fileName)
            ?: throw NoStackTraceException("内测版本号缺失")
        val downloadUrl = resolveUrl(baseUrl, metadata.downloadUrl, "download")
        val updateInfo = AppUpdate.UpdateInfo(
            tagName = tagName,
            updateLog = metadata.updateLog?.takeIf { it.isNotBlank() } ?: "内测版更新",
            downloadUrl = downloadUrl,
            fileName = fileName,
            versionCode = metadata.versionCode ?: AppUpdate.versionCodeFromFileName(fileName),
            requestHeaders = headers
        )
        if (!AppUpdate.isNewerThanCurrent(updateInfo)) {
            throw AppUpdate.latestVersionError()
        }
        return updateInfo
    }

    private fun endpointUrl(baseUrl: String, path: String): String {
        val normalized = rootUrl(baseUrl)
        return "$normalized/$path"
    }

    private fun rootUrl(url: String): String {
        return url
            .trim()
            .trimEnd('/')
            .removeSuffix("/latest")
            .removeSuffix("/download")
            .trimEnd('/')
    }

    private fun resolveUrl(baseUrl: String, value: String?, fallbackPath: String): String {
        val candidate = value?.trim().orEmpty()
        if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
            return candidate
        }
        val root = rootUrl(baseUrl)
        if (candidate.isBlank()) {
            return "$root/$fallbackPath"
        }
        return if (candidate.startsWith("/")) {
            root + candidate
        } else {
            "$root/$candidate"
        }
    }

    private fun versionNameFromFileName(fileName: String): String? {
        return Regex("""^.+?_.+?_([^_]+)(?:_(\d+))?\.apk$""")
            .matchEntire(fileName)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
    }

    @Keep
    private data class InternalReleaseMetadata(
        val tagName: String? = null,
        val versionName: String? = null,
        val versionCode: Long? = null,
        val updateLog: String? = null,
        val downloadUrl: String? = null,
        val fileName: String? = null
    )
}
