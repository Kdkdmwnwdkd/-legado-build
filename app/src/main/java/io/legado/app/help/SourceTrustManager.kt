package io.legado.app.help

import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import splitties.init.appCtx

/**
 * 用户手动信任的书源证书白名单管理器
 * 存储用户选择信任的自签名/不规范 HTTPS 证书书源 URL
 */
object SourceTrustManager {

    private const val PREF_KEY_TRUSTED_SOURCES = "trustedSourceUrls"

    /**
     * 判断指定 URL 是否在用户白名单中
     */
    fun isTrusted(url: String): Boolean {
        val domain = extractDomain(url)
        return getTrustedUrls().any { extractDomain(it) == domain }
    }

    /**
     * 添加 URL 到白名单
     */
    fun add(url: String) {
        val urls = getTrustedUrls().toMutableSet()
        urls.add(url)
        saveTrustedUrls(urls)
    }

    /**
     * 从白名单移除 URL
     */
    fun remove(url: String) {
        val urls = getTrustedUrls().toMutableSet()
        urls.remove(url)
        saveTrustedUrls(urls)
    }

    /**
     * 获取所有白名单 URL 列表
     */
    fun getTrustedUrls(): Set<String> {
        val json = appCtx.defaultSharedPreferences.getString(PREF_KEY_TRUSTED_SOURCES, null)
            ?: return emptySet()
        return GSON.fromJsonObject<Set<String>>(json).getOrNull() ?: emptySet()
    }

    /**
     * 清空白名单
     */
    fun clear() {
        appCtx.defaultSharedPreferences.edit().remove(PREF_KEY_TRUSTED_SOURCES).apply()
    }

    /**
     * 保存 URL 列表到 SharedPreferences
     */
    private fun saveTrustedUrls(urls: Set<String>) {
        val json = GSON.toJson(urls)
        appCtx.defaultSharedPreferences.edit().putString(PREF_KEY_TRUSTED_SOURCES, json).apply()
    }

    /**
     * 从 URL 中提取域名，用于模糊匹配
     */
    private fun extractDomain(url: String): String {
        return try {
            val uri = java.net.URI(url)
            uri.host ?: url
        } catch (_: Exception) {
            url
        }
    }
}
