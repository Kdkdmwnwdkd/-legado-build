package io.legado.app.help.update

import io.legado.app.constant.AppConst
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull

object AppUpdate {

    val githubUpdate: AppUpdateInterface by lazy {
        AppUpdateGitHub
    }
    val preferredUpdate: AppUpdateInterface by lazy {
        PreferredAppUpdate
    }

    data class UpdateInfo(
        val tagName: String,
        val updateLog: String,
        val downloadUrl: String,
        val fileName: String,
        val versionCode: Long = versionCodeFromFileName(fileName),
        val requestHeaders: Map<String, String> = emptyMap()
    )

    interface AppUpdateInterface {

        fun check(scope: CoroutineScope): Coroutine<UpdateInfo>

    }

    fun isLatestVersionError(error: Throwable): Boolean {
        val message = error.message ?: return false
        return error is NoStackTraceException &&
            (message.contains("最新版本") || message.contains("鏈€鏂扮増鏈"))
    }

    fun latestVersionError(): NoStackTraceException {
        return NoStackTraceException("已是最新版本")
    }

    fun versionCodeFromFileName(fileName: String): Long {
        // 本仓库命名：shimmer_3.26.110817_110817.apk
        Regex("""^.+?_(\d+(?:\.\d+)+)_(\d+)\.apk$""")
            .matchEntire(fileName)
            ?.groupValues
            ?.getOrNull(2)
            ?.toLongOrNull()
            ?.let { return it }
        // 上游命名兜底
        return Regex("""^.+?_.+?_([^_]+)(?:_(\d+))?\.apk$""")
            .matchEntire(fileName)
            ?.groupValues
            ?.getOrNull(2)
            ?.toLongOrNull()
            ?: 0L
    }

    /**
     * 按点分段比较版本名，避免 "nightly" > "3.26.x" 这种字符串比较误判。
     */
    fun compareVersionName(left: String, right: String): Int {
        fun segments(v: String): List<Long> =
            v.trim().removePrefix("v")
                .split('.', '-', '_', '+')
                .mapNotNull { it.toLongOrNull() }

        val a = segments(left)
        val b = segments(right)
        if (a.isEmpty() || b.isEmpty()) {
            // 有一方不是数字版本（例如 "nightly"），交给字符串比较，但至少不会误判成无限更新
            return left.compareTo(right)
        }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0L }
            val y = b.getOrElse(i) { 0L }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    fun isNewerThanCurrent(updateInfo: UpdateInfo): Boolean {
        return if (updateInfo.versionCode > 0L) {
            updateInfo.versionCode > AppConst.appInfo.versionCode
        } else {
            compareVersionName(updateInfo.tagName, AppConst.appInfo.versionName) > 0
        }
    }

    private fun compareVersion(left: UpdateInfo, right: UpdateInfo): Int {
        return if (left.versionCode > 0L && right.versionCode > 0L) {
            left.versionCode.compareTo(right.versionCode)
        } else {
            compareVersionName(left.tagName, right.tagName)
        }
    }

    private object PreferredAppUpdate : AppUpdateInterface {
        override fun check(scope: CoroutineScope): Coroutine<UpdateInfo> {
            return Coroutine.async(scope) {
                checkNow()
            }.timeout(15000)
        }

        private suspend fun checkNow(): UpdateInfo = coroutineScope {
            val officialDeferred = async {
                withTimeoutOrNull(8_000) {
                    runCatching { checkOfficialNow() }
                } ?: Result.failure(NoStackTraceException("正式版更新检查超时"))
            }
            if (!AppUpdateConfig.internalBetaConfigured) {
                return@coroutineScope officialDeferred.await().getOrThrow()
            }

            val internalBetaDeferred = async {
                withTimeoutOrNull(8_000) {
                    runCatching { AppUpdateInternal.checkNow() }
                } ?: Result.failure(NoStackTraceException("内测版更新检查超时"))
            }

            val internalBetaResult = internalBetaDeferred.await()
            val officialResult = officialDeferred.await()
            val official = officialResult.getOrNull()
            val internalBeta = internalBetaResult.getOrNull()

            if (official != null && internalBeta != null) {
                return@coroutineScope if (compareVersion(official, internalBeta) >= 0) {
                    official
                } else {
                    internalBeta
                }
            }
            official?.let { return@coroutineScope it }
            internalBeta?.let { return@coroutineScope it }

            val officialError = officialResult.exceptionOrNull()
            val internalBetaError = internalBetaResult.exceptionOrNull()
            if (officialError != null && !isLatestVersionError(officialError)) {
                throw officialError
            }
            if (internalBetaError != null && !isLatestVersionError(internalBetaError)) {
                throw internalBetaError
            }
            throw officialError ?: internalBetaError ?: latestVersionError()
        }

        private suspend fun checkOfficialNow(): UpdateInfo {
            // 阅读Shimmer 为独立产品，发布与更新只走自己的 GitHub 仓库。
            return AppUpdateGitHub.checkNow()
        }
    }

}
