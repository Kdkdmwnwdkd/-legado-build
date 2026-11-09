package com.shimmer.reader.help.config

import android.content.Context
import com.shimmer.reader.constant.EventBus
import com.shimmer.reader.constant.PreferKey
import com.shimmer.reader.utils.getPrefString
import com.shimmer.reader.utils.getPrefBoolean
import com.shimmer.reader.utils.postEvent
import com.shimmer.reader.utils.putPrefBoolean
import com.shimmer.reader.utils.putPrefString
import splitties.init.appCtx

object MainLayoutPresetConfig {

    const val PRESET_DEFAULT = "default"
    const val PRESET_REGULAR = "regular"
    const val PRESET_SIDEBAR = "sidebar"

    private val validPresets = setOf(PRESET_DEFAULT, PRESET_REGULAR, PRESET_SIDEBAR)

    fun currentPreset(): String {
        return appCtx.getPrefString(PreferKey.mainLayoutPreset, PRESET_DEFAULT)
            ?.takeIf { it in validPresets }
            ?: PRESET_DEFAULT
    }

    fun defaultBottomLayoutMode(): String {
        return when (currentPreset()) {
            PRESET_REGULAR -> "standard"
            PRESET_SIDEBAR -> "sidebar"
            else -> "floating"
        }
    }

    fun defaultTopBarStyle(): String {
        return appCtx.getPrefString(PreferKey.defaultTopBarStyle, TopBarConfig.STYLE_DEFAULT)
            ?.takeIf { it in setOf(TopBarConfig.STYLE_DEFAULT, TopBarConfig.STYLE_REGULAR) }
            ?: when (currentPreset()) {
                PRESET_REGULAR -> TopBarConfig.STYLE_REGULAR
                else -> TopBarConfig.STYLE_DEFAULT
            }
    }

    fun defaultTopBarShowSearch(): Boolean {
        return appCtx.getPrefBoolean(PreferKey.defaultTopBarShowSearch, false)
    }

    fun floatingBottomBarHideSearch(): Boolean {
        return appCtx.getPrefBoolean(PreferKey.floatingBottomBarHideSearch, false)
    }

    fun apply(context: Context, preset: String, notify: Boolean = true) {
        val nextPreset = preset.takeIf { it in validPresets } ?: PRESET_DEFAULT
        val topBarStyle = when (nextPreset) {
            PRESET_REGULAR -> TopBarConfig.STYLE_REGULAR
            else -> TopBarConfig.STYLE_DEFAULT
        }
        val bottomLayoutMode = when (nextPreset) {
            PRESET_REGULAR -> "standard"
            PRESET_SIDEBAR -> "sidebar"
            else -> "floating"
        }
        context.putPrefString(PreferKey.mainLayoutPreset, nextPreset)
        context.putPrefString(PreferKey.defaultTopBarStyle, topBarStyle)
        context.putPrefString(PreferKey.navigationBarPackageDay, NavigationBarIconConfig.DEFAULT_DIR_NAME)
        context.putPrefString(PreferKey.navigationBarPackageNight, NavigationBarIconConfig.DEFAULT_DIR_NAME)
        context.putPrefString(PreferKey.topBarPackageDay, TopBarConfig.DEFAULT_DIR_NAME)
        context.putPrefString(PreferKey.topBarPackageNight, TopBarConfig.DEFAULT_DIR_NAME)
        context.putPrefBoolean(PreferKey.defaultTopBarShowSearch, false)
        context.putPrefBoolean(PreferKey.floatingBottomBarHideSearch, false)
        AppConfig.bottomBarLayoutMode = bottomLayoutMode
        AppConfig.bottomBarSidebarGravity = "start"
        AppConfig.bottomBarEffectMode = if (bottomLayoutMode == "standard") "solid" else "glass"
        if (bottomLayoutMode == "sidebar") {
            context.putPrefBoolean(PreferKey.mergeDiscoveryRss, false)
        }
        if (notify) {
            postEvent(EventBus.NOTIFY_MAIN, true)
        }
    }
}
