package com.shimmer.reader.ui.config

import com.shimmer.reader.ui.config.compose.ComposeSettingFragment

/**
 * 设置页登记表：configTag 与对应页面一一对应。
 *
 * 以前这套映射写在 [ConfigActivity] 的 when 分支里，搜索索引另外手工维护一份，
 * 两边都容易和真实页面脱节。现在统一放在这里，页面注册一次即可被
 * 路由和搜索索引同时使用。
 */
object ConfigPageRegistry {

    data class Page(
        val tag: String,
        val fragmentClass: Class<out ComposeSettingFragment>
    )

    val pages: List<Page> = listOf(
        Page(ConfigTag.OTHER_CONFIG, OtherConfigFragment::class.java),
        Page(ConfigTag.NETWORK_DNS_CONFIG, NetworkDnsConfigFragment::class.java),
        Page(ConfigTag.THEME_CONFIG, ThemeConfigFragment::class.java),
        Page(ConfigTag.BACKUP_CONFIG, BackupConfigFragment::class.java),
        Page(ConfigTag.AI_CONFIG, AiConfigFragment::class.java),
        Page(ConfigTag.COVER_CONFIG, CoverConfigFragment::class.java),
        Page(ConfigTag.WELCOME_CONFIG, WelcomeConfigFragment::class.java),
        Page(
            ConfigTag.DISCOVERY_SUBSCRIPTION_CONFIG,
            DiscoverySubscriptionConfigFragment::class.java
        ),
        Page(ConfigTag.DISCOVERY_CONFIG, DiscoveryConfigFragment::class.java),
        Page(ConfigTag.SUBSCRIPTION_CONFIG, SubscriptionConfigFragment::class.java)
    )

    fun find(tag: String): Page? = pages.firstOrNull { it.tag == tag }
}
