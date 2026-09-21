package io.legado.app.help.storage

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AdvancedTipPackageManager
import io.legado.app.help.config.AdvancedTitlePackageManager
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.defaultSharedPreferences
import splitties.init.appCtx

object ReaderDataRepair {

    const val REPAIR_VERSION = 112
    private const val TAG = "ReaderDataRepair"

    data class Report(
        val changed: Boolean,
        val prefChanged: Int,
        val prefRemoved: Int,
        val readConfigChanged: Boolean,
        val shareConfigChanged: Boolean
    )

    private val intKeys = setOf(
        PreferKey.readStyleSelect,
        PreferKey.epubReadStyleSelect,
        PreferKey.comicStyleSelect,
        PreferKey.autoReadSpeed,
        PreferKey.autoReadMode,
        PreferKey.advancedTitleHeightFactor,
        PreferKey.bookshelfLayout,
        PreferKey.bookshelfSort,
        PreferKey.exportType,
        PreferKey.chineseConverterType,
        PreferKey.systemTypefaces,
        PreferKey.localBookImportSort
    )

    private val booleanKeys = setOf(
        PreferKey.shareLayout,
        PreferKey.epubShareLayout,
        "epubLayout." + PreferKey.textFullJustify,
        PreferKey.hideStatusBar,
        PreferKey.hideNavigationBar,
        PreferKey.readBodyToLh,
        PreferKey.useZhLayout,
        PreferKey.textFullJustify,
        PreferKey.textBottomJustify,
        PreferKey.optimizeRender,
        PreferKey.bookshelfReturnToTopAfterRead,
        PreferKey.bookCoverShadow,
        PreferKey.aiChatAutoSpeakEnabled,
        PreferKey.welcomeShowText,
        PreferKey.welcomeShowTextDark
    )

    @Synchronized
    fun repair(force: Boolean = false): Report {
        val prefs = appCtx.defaultSharedPreferences
        val marker = runCatching {
            prefs.getInt(PreferKey.readerDataRepairVersion, 0)
        }.getOrDefault(0)
        if (!force && marker >= REPAIR_VERSION) {
            return Report(
                changed = false,
                prefChanged = 0,
                prefRemoved = 0,
                readConfigChanged = false,
                shareConfigChanged = false
            )
        }

        val prefReport = repairPreferences(prefs)
        var configRepairFailed = false
        val configReport = runCatching {
            ReadBookConfig.repairAndReload()
        }.onFailure {
            configRepairFailed = true
            Log.e(TAG, "repair read config failed", it)
        }.getOrDefault(ReadBookConfig.RepairResult(readConfigChanged = false, shareConfigChanged = false))
        runCatching {
            AdvancedTitlePackageManager.invalidate()
            AdvancedTipPackageManager.header.invalidate()
            AdvancedTipPackageManager.footer.invalidate()
        }.onFailure {
            Log.e(TAG, "invalidate advanced packages failed", it)
        }

        if (!configRepairFailed) {
            prefs.edit(commit = true) {
                putInt(PreferKey.readerDataRepairVersion, REPAIR_VERSION)
            }
        }
        val report = Report(
            changed = prefReport.changed > 0 || prefReport.removed > 0 ||
                    configReport.readConfigChanged || configReport.shareConfigChanged,
            prefChanged = prefReport.changed,
            prefRemoved = prefReport.removed,
            readConfigChanged = configReport.readConfigChanged,
            shareConfigChanged = configReport.shareConfigChanged
        )
        if (report.changed) {
            Log.w(TAG, "reader data repaired: $report")
        }
        return report
    }

    fun repairOnAppStart() {
        runCatching {
            repair(force = false)
        }.onFailure {
            Log.e(TAG, "app start repair failed", it)
        }
    }

    fun repairAfterRestore() {
        runCatching {
            repair(force = true)
        }.onFailure {
            Log.e(TAG, "restore repair failed", it)
        }
    }

    private data class PrefReport(val changed: Int, val removed: Int)

    private fun repairPreferences(prefs: SharedPreferences): PrefReport {
        val all = prefs.all
        val edit = prefs.edit()
        var changed = 0
        var removed = 0

        fun putIntIfNeeded(key: String, value: Int) {
            if (all[key] == value && all[key] is Int) return
            edit.putInt(key, value)
            changed += 1
        }

        fun putBooleanIfNeeded(key: String, value: Boolean) {
            if (all[key] == value && all[key] is Boolean) return
            edit.putBoolean(key, value)
            changed += 1
        }

        fun removeKey(key: String) {
            edit.remove(key)
            removed += 1
        }

        intKeys.forEach { key ->
            val raw = all[key] ?: return@forEach
            when (raw) {
                is Int -> Unit
                is Number -> putIntIfNeeded(key, raw.toInt())
                is String -> raw.trim().toIntOrNull()?.let { putIntIfNeeded(key, it) } ?: removeKey(key)
                is Boolean -> putIntIfNeeded(key, if (raw) 1 else 0)
                else -> removeKey(key)
            }
        }

        booleanKeys.forEach { key ->
            val raw = all[key] ?: return@forEach
            when (raw) {
                is Boolean -> Unit
                is String -> parseBoolean(raw)?.let { putBooleanIfNeeded(key, it) } ?: removeKey(key)
                is Number -> putBooleanIfNeeded(key, raw.toInt() != 0)
                else -> removeKey(key)
            }
        }

        val maxReadStyle = runCatching { ReadBookConfig.lastStyleIndex(epub = false) }
            .getOrDefault(0)
            .coerceAtLeast(0)
        coerceIntPref(all, PreferKey.readStyleSelect, 0, maxReadStyle, edit)?.let {
            if (it) changed += 1 else removed += 1
        }
        coerceIntPref(all, PreferKey.comicStyleSelect, 0, maxReadStyle, edit)?.let {
            if (it) changed += 1 else removed += 1
        }
        coerceIntPref(all, PreferKey.epubReadStyleSelect, 0, ReadBookConfig.lastStyleIndex(epub = true), edit)?.let {
            if (it) changed += 1 else removed += 1
        }
        coerceIntPref(all, PreferKey.autoReadSpeed, 1, 1000, edit)?.let {
            if (it) changed += 1 else removed += 1
        }
        coerceIntPref(all, PreferKey.autoReadMode, 0, 1, edit)?.let {
            if (it) changed += 1 else removed += 1
        }
        coerceIntPref(all, PreferKey.advancedTitleHeightFactor, 30, 120, edit)?.let {
            if (it) changed += 1 else removed += 1
        }

        if (changed > 0 || removed > 0) {
            edit.commit()
            reloadReadPrefs()
        }
        return PrefReport(changed, removed)
    }

    private fun parseBoolean(raw: String): Boolean? {
        return when (raw.trim().lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }

    private fun coerceIntPref(
        all: Map<String, *>,
        key: String,
        min: Int,
        max: Int,
        edit: SharedPreferences.Editor
    ): Boolean? {
        val raw = all[key] ?: return null
        val value = when (raw) {
            is Int -> raw
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            is Boolean -> if (raw) 1 else 0
            else -> null
        } ?: run {
            edit.remove(key)
            return false
        }
        val fixed = value.coerceIn(min, max)
        if (fixed == value && raw is Int) return null
        edit.putInt(key, fixed)
        return true
    }

    private fun reloadReadPrefs() {
        ReadBookConfig.readBodyToLh = appCtx.defaultSharedPreferences
            .getBooleanCompat(PreferKey.readBodyToLh, true)
        ReadBookConfig.autoReadSpeed = appCtx.defaultSharedPreferences
            .getIntCompat(PreferKey.autoReadSpeed, 10)
        ReadBookConfig.autoReadMode = appCtx.defaultSharedPreferences
            .getIntCompat(PreferKey.autoReadMode, ReadBookConfig.AUTO_READ_MODE_SCROLL)
        ReadBookConfig.comicStyleSelect = appCtx.defaultSharedPreferences
            .getIntCompat(PreferKey.comicStyleSelect, appCtx.defaultSharedPreferences.getIntCompat(PreferKey.readStyleSelect, 0))
        ReadBookConfig.hideStatusBar = appCtx.defaultSharedPreferences
            .getBooleanCompat(PreferKey.hideStatusBar, false)
        ReadBookConfig.hideNavigationBar = appCtx.defaultSharedPreferences
            .getBooleanCompat(PreferKey.hideNavigationBar, false)
        ReadBookConfig.useZhLayout = appCtx.defaultSharedPreferences
            .getBooleanCompat(PreferKey.useZhLayout, false)
    }

    private fun SharedPreferences.getIntCompat(key: String, defValue: Int): Int {
        return runCatching { getInt(key, defValue) }.getOrDefault(defValue)
    }

    private fun SharedPreferences.getBooleanCompat(key: String, defValue: Boolean): Boolean {
        return runCatching { getBoolean(key, defValue) }.getOrDefault(defValue)
    }
}
