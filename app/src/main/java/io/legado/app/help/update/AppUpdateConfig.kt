package io.legado.app.help.update

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import splitties.init.appCtx

object AppUpdateConfig {

    const val STRATEGY_GITEE_THEN_GITHUB = "gitee_then_github"
    const val STRATEGY_GITEE_ONLY = "gitee_only"
    const val STRATEGY_GITHUB_ONLY = "github_only"
    const val INTERNAL_BETA_KEY_HEADER = "X-Download-Key"

    private const val URL_PLACEHOLDER = "\${url}"

    var strategy: String
        get() = when (appCtx.getPrefString(PreferKey.updateSourceStrategy, STRATEGY_GITEE_THEN_GITHUB)) {
            STRATEGY_GITEE_ONLY -> STRATEGY_GITEE_ONLY
            STRATEGY_GITHUB_ONLY -> STRATEGY_GITHUB_ONLY
            else -> STRATEGY_GITEE_THEN_GITHUB
        }
        set(value) {
            appCtx.putPrefString(
                PreferKey.updateSourceStrategy,
                when (value) {
                    STRATEGY_GITEE_ONLY -> STRATEGY_GITEE_ONLY
                    STRATEGY_GITHUB_ONLY -> STRATEGY_GITHUB_ONLY
                    else -> STRATEGY_GITEE_THEN_GITHUB
                }
            )
        }

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

    fun applyGithubProxy(url: String): String {
        val template = selectedGithubProxyTemplate?.trim().orEmpty()
        if (template.isBlank()) return url
        return when {
            template.contains(URL_PLACEHOLDER) -> template.replace(URL_PLACEHOLDER, url)
            template.contains("{url}") -> template.replace("{url}", url)
            else -> template.trimEnd('/') + "/" + url
        }
    }

    fun strategyLabel(context: Context): String {
        return when (strategy) {
            STRATEGY_GITEE_ONLY -> "只使用 Gitee"
            STRATEGY_GITHUB_ONLY -> "只使用 GitHub"
            else -> "Gitee 优先，失败后 GitHub"
        }
    }

    fun summary(context: Context): String {
        val proxy = selectedGithubProxyTemplate
            ?.takeIf { it.isNotBlank() }
            ?.let { "，GitHub 代理已启用" }
            .orEmpty()
        return strategyLabel(context) + proxy
    }
}
