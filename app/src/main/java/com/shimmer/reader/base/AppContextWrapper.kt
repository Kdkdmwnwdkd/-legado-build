package com.shimmer.reader.base

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import com.shimmer.reader.constant.PreferKey
import com.shimmer.reader.lib.theme.ThemeRuntimeKeys
import com.shimmer.reader.utils.getPrefInt
import com.shimmer.reader.utils.getPrefString
import com.shimmer.reader.utils.sysConfiguration
import java.util.*


@Suppress("unused")
object AppContextWrapper {

    @SuppressLint("ObsoleteSdkInt")
    fun wrap(context: Context): Context {
        val resources: Resources = context.resources
        val configuration: Configuration = resources.configuration
        val targetLocale = getSetLocale(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocale(targetLocale)
            configuration.setLocales(LocaleList(targetLocale))
        } else {
            @Suppress("DEPRECATION")
            configuration.locale = targetLocale
        }
        configuration.fontScale = getFontScale(context)
        return context.createConfigurationContext(configuration)
    }

    fun getFontScale(context: Context): Float {
        val isNightTheme = when (context.getPrefString(PreferKey.themeMode, "0")) {
            "1", "3" -> false
            "2" -> true
            else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        var fontScale = context.getPrefInt(ThemeRuntimeKeys.fontScale(isNightTheme)) / 10f
        if (fontScale !in 0.8f..1.6f) {
            fontScale = sysConfiguration.fontScale
        }
        return fontScale
    }

    /**
     * 应用语言。
     *
     * 资源只保留简体中文（values 即中文），不再提供跟随系统/繁体/英文，
     * 历史设置里的 auto / tw / en 一律回落简体，避免英文系统上取不到资源。
     */
    private fun getSetLocale(@Suppress("UNUSED_PARAMETER") context: Context): Locale {
        return Locale.SIMPLIFIED_CHINESE
    }

}
