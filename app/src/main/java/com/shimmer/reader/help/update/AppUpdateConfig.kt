package com.shimmer.reader.help.update

import com.shimmer.reader.constant.PreferKey
import com.shimmer.reader.utils.GSON
import com.shimmer.reader.utils.fromJsonArray
import com.shimmer.reader.utils.getPrefBoolean
import com.shimmer.reader.utils.getPrefInt
import com.shimmer.reader.utils.getPrefString
import com.shimmer.reader.utils.putPrefBoolean
import com.shimmer.reader.utils.putPrefInt
import com.shimmer.reader.utils.putPrefString
import com.shimmer.reader.utils.removePref
import splitties.init.appCtx

object AppUpdateConfig {

    const val INTERNAL_BETA_KEY_HEADER = "X-Download-Key"

    private const val URL_PLACEHOLDER = "\${url}"

    var githubProxyTemplates: List<String>
        get() {
            val raw = appCtx.getPrefString(PreferKey.updateGithubProxyTemplates).orEmpty()
            return GSON.fromJsonArray<String>(raw)
                .getOrDefault(emptyList())
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        }
        set(value) {
            val normalized = value
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            appCtx.putPrefString(PreferKey.updateGithubProxyTemplates, GSON.toJson(normalized))
            if (githubProxyIndex >= normalized.size) {
                githubProxyIndex = -1
            }
        }

    var githubProxyIndex: Int
        get() = appCtx.getPrefInt(PreferKey.updateGithubProxyIndex, -1)
        set(value) = appCtx.putPrefInt(PreferKey.updateGithubProxyIndex, value)

    val selectedGithubProxyTemplate: String?
        get() = githubProxyTemplates.getOrNull(githubProxyIndex)

    var internalBetaEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.internalBetaUpdateEnabled, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.internalBetaUpdateEnabled, value)

    var internalBetaUrl: String?
        get() = appCtx.getPrefString(PreferKey.internalBetaUpdateUrl)
        set(value) {
            val normalized = value?.trim().orEmpty()
            if (normalized.isBlank()) {
                appCtx.removePref(PreferKey.internalBetaUpdateUrl)
            } else {
                appCtx.putPrefString(PreferKey.internalBetaUpdateUrl, normalized)
            }
        }

    var internalBetaKey: String?
        get() = appCtx.getPrefString(PreferKey.internalBetaUpdateKey)
        set(value) {
            val normalized = value?.trim().orEmpty()
            if (normalized.isBlank()) {
                appCtx.removePref(PreferKey.internalBetaUpdateKey)
            } else {
                appCtx.putPrefString(PreferKey.internalBetaUpdateKey, normalized)
            }
        }

    val internalBetaConfigured: Boolean
        get() = internalBetaEnabled &&
            !internalBetaUrl.isNullOrBlank() &&
            !internalBetaKey.isNullOrBlank()

    val internalBetaHeaders: Map<String, String>
        get() = internalBetaKey
            ?.takeIf { it.isNotBlank() }
            ?.let { mapOf(INTERNAL_BETA_KEY_HEADER to it) }
            ?: emptyMap()

    /**
     * 国内直连 github.com 与 api.github.com 常年跑不满带宽，
     * 用户没有自己配加速时默认走这个，失败会自动回退直连。
     */
    private const val DEFAULT_PROXY_TEMPLATE = "https://gh-proxy.com/\${url}"

    fun applyGithubProxy(url: String): String {
        val template = selectedGithubProxyTemplate?.trim().orEmpty()
            .ifBlank { DEFAULT_PROXY_TEMPLATE }
        return when {
            template.contains(URL_PLACEHOLDER) -> template.replace(URL_PLACEHOLDER, url)
            template.contains("{url}") -> template.replace("{url}", url)
            else -> template.trimEnd('/') + "/" + url
        }
    }
}
