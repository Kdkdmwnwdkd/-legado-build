package com.shimmer.reader.ui.config

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.core.view.postDelayed
import androidx.fragment.app.activityViewModels
import com.jeremyliao.liveeventbus.LiveEventBus
import com.shimmer.reader.BuildConfig
import com.shimmer.reader.R
import com.shimmer.reader.base.BaseActivity
import com.shimmer.reader.constant.EventBus
import com.shimmer.reader.constant.PreferKey
import com.shimmer.reader.help.AppFreezeMonitor
import com.shimmer.reader.help.DispatchersMonitor
import com.shimmer.reader.help.config.AppConfig
import com.shimmer.reader.help.config.EpubReadEnginePolicy
import com.shimmer.reader.help.config.LocalConfig
import com.shimmer.reader.model.CheckSource
import com.shimmer.reader.model.ImageProvider
import com.shimmer.reader.receiver.SharedReceiverActivity
import com.shimmer.reader.service.WebService
import com.shimmer.reader.ui.book.read.config.ContentSelectMenuConfigDialog
import com.shimmer.reader.ui.config.compose.ComposeSettingFragment
import com.shimmer.reader.ui.config.compose.SettingActionSpec
import com.shimmer.reader.ui.config.compose.SettingChoiceOption
import com.shimmer.reader.ui.config.compose.SettingChoiceSpec
import com.shimmer.reader.ui.config.compose.SettingItemSpec
import com.shimmer.reader.ui.config.compose.SettingPageSpec
import com.shimmer.reader.ui.config.compose.SettingSectionSpec
import com.shimmer.reader.ui.config.compose.SettingSwitchSpec
import com.shimmer.reader.ui.file.HandleFileContract
import com.shimmer.reader.ui.video.config.SettingsDialog
import com.shimmer.reader.ui.widget.compose.showComposeConfirmDialog
import com.shimmer.reader.ui.widget.compose.showComposeNumberPickerDialog
import com.shimmer.reader.ui.widget.compose.showComposeTextInputDialog
import com.shimmer.reader.utils.LogUtils
import com.shimmer.reader.utils.postEvent
import com.shimmer.reader.utils.putPrefString
import com.shimmer.reader.utils.removePref
import com.shimmer.reader.utils.restart
import com.shimmer.reader.utils.showDialogFragment
import com.shimmer.reader.utils.startActivity
import splitties.init.appCtx

/**
 * 其它设置
 */
class OtherConfigFragment : ComposeSettingFragment() {

    private val viewModel by activityViewModels<ConfigViewModel>()
    private val packageManager = appCtx.packageManager
    private val componentName = ComponentName(
        appCtx,
        SharedReceiverActivity::class.java.name
    )
    private val localBookTreeSelect = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { treeUri ->
            AppConfig.defaultBookTreeUri = treeUri.toString()
        }
    }

    override val titleRes: Int = R.string.other_setting

    override fun onResume() {
        super.onResume()
        val enabled = isProcessTextEnabled()
        if (booleanSetting(PreferKey.processText, true) != enabled) {
            updateBooleanSetting(PreferKey.processText, enabled)
        }
    }

    override fun buildPageSpec(): SettingPageSpec {
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    items = listOf(
                        choice(
                            key = KEY_LANGUAGE,
                            title = str(R.string.language),
                            entriesRes = R.array.language,
                            valuesRes = R.array.language_value,
                            defaultValue = "zh"
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.main_activity),
                    items = listOf(
                        switch(
                            key = PreferKey.autoRefresh,
                            title = str(R.string.pt_auto_refresh),
                            summary = str(R.string.ps_auto_refresh),
                            defaultValue = false
                        ),
                        switch(
                            key = PreferKey.onlyUpdateRead,
                            title = str(R.string.only_update_read),
                            summary = str(R.string.ps_only_update_read),
                            defaultValue = false,
                            visible = booleanSetting(PreferKey.autoRefresh, false)
                        ),
                        switch(
                            key = PreferKey.defaultToRead,
                            title = str(R.string.pt_default_read),
                            summary = str(R.string.ps_default_read),
                            defaultValue = false
                        ),
                        choice(
                            key = PreferKey.defaultHomePage,
                            title = str(R.string.default_home_page),
                            entriesRes = R.array.default_home_page,
                            valuesRes = R.array.default_home_page_value,
                            defaultValue = "bookshelf"
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.other_setting),
                    items = otherSettingItems()
                )
            )
        )
    }

    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            PreferKey.threadCount -> {
                postEvent(PreferKey.threadCount, "")
            }

            PreferKey.webPort -> {
                if (WebService.isRun) {
                    WebService.stop(requireContext())
                    WebService.start(requireContext())
                }
            }

            PreferKey.epubReadEngine, PreferKey.textReadEngine -> {
                postEvent(EventBus.UP_CONFIG, arrayListOf(13))
            }

            PreferKey.recordLog -> {
                AppConfig.recordLog = booleanSetting(PreferKey.recordLog, false)
                LogUtils.upLevel()
                LogUtils.logDeviceInfo()
                LiveEventBus.config().enableLogger(AppConfig.recordLog)
                AppFreezeMonitor.init(appCtx)
                DispatchersMonitor.init()
            }

            PreferKey.processText -> {
                setProcessTextEnable(booleanSetting(PreferKey.processText, true))
            }

            KEY_LANGUAGE -> view?.postDelayed(1000) {
                appCtx.restart()
            }

            PreferKey.highBrush -> {
                (activity as? BaseActivity<*>)?.applyPreferredRefreshRate()
            }
        }
    }

    private fun otherSettingItems(): List<SettingItemSpec> {
        return listOf(
            SettingActionSpec(
                key = PreferKey.contentSelectMenuConfig,
                title = str(R.string.content_select_menu_config),
                summary = str(R.string.content_select_menu_config_summary),
                onClick = {
                    ContentSelectMenuConfigDialog()
                        .show(parentFragmentManager, "contentSelectMenuConfig")
                }
            ),
            intChoice(
                key = PreferKey.fastScrollerTouchTargetDp,
                title = str(R.string.fast_scroller_touch_target),
                entriesRes = R.array.fast_scroller_touch_target_entries,
                valuesRes = R.array.fast_scroller_touch_target_values,
                defaultValue = AppConfig.DEFAULT_FAST_SCROLLER_TOUCH_TARGET_DP
            ),
            choice(
                key = PreferKey.epubReadEngine,
                title = str(R.string.epub_read_engine),
                summary = epubReadEngineSummary(),
                entriesRes = R.array.epub_read_engine_entries,
                valuesRes = R.array.epub_read_engine_values,
                defaultValue = EpubReadEnginePolicy.DEFAULT
            ),
            choice(
                key = PreferKey.textReadEngine,
                title = str(R.string.text_read_engine),
                summary = str(R.string.text_read_engine_summary),
                entriesRes = R.array.text_read_engine_entries,
                valuesRes = R.array.text_read_engine_values,
                defaultValue = com.shimmer.reader.help.config.TextReadEnginePolicy.DEFAULT
            ),
            SettingActionSpec(
                key = KEY_LOCAL_PASSWORD,
                title = str(R.string.set_local_password),
                summary = str(R.string.set_local_password_summary),
                onClick = ::alertLocalPassword
            ),
            SettingActionSpec(
                key = PreferKey.userAgent,
                title = str(R.string.user_agent),
                summary = userAgentValue(),
                onClick = ::showUserAgentDialog
            ),
            SettingActionSpec(
                key = "networkDns",
                title = str(R.string.network_dns_title),
                summary = str(R.string.network_dns_summary),
                onClick = {
                    startActivity<ConfigActivity> {
                        putExtra("configTag", ConfigTag.NETWORK_DNS_CONFIG)
                    }
                }
            ),
            switch(
                key = PreferKey.webServiceWakeLock,
                title = str(R.string.web_service_wake_lock),
                summary = str(R.string.web_service_wake_lock_summary),
                defaultValue = false
            ),
            SettingActionSpec(
                key = PreferKey.defaultBookTreeUri,
                title = str(R.string.book_tree_uri_t),
                summary = AppConfig.defaultBookTreeUri ?: str(R.string.book_tree_uri_s),
                onClick = {
                    localBookTreeSelect.launch {
                        title = str(R.string.select_book_folder)
                        mode = HandleFileContract.DIR_SYS
                    }
                }
            ),
            numberAction(
                key = PreferKey.sourceEditMaxLine,
                title = str(R.string.source_edit_text_max_line),
                summary = str(
                    R.string.source_edit_max_line_summary,
                    AppConfig.sourceEditMaxLine.toString()
                ),
                min = 10,
                max = Int.MAX_VALUE,
                value = AppConfig.sourceEditMaxLine,
                onSelected = { AppConfig.sourceEditMaxLine = it }
            ),
            SettingActionSpec(
                key = PreferKey.checkSource,
                title = str(R.string.check_source_config),
                summary = CheckSource.summary,
                onClick = { showDialogFragment<CheckSourceConfig>() }
            ),
            SettingActionSpec(
                key = PreferKey.uploadRule,
                title = str(R.string.direct_link_upload_rule),
                summary = str(R.string.direct_link_upload_rule_summary),
                onClick = { showDialogFragment<ComposeDirectLinkUploadDialog>() }
            ),
            switch(
                key = PreferKey.antiAlias,
                title = str(R.string.anti_alias),
                summary = str(R.string.pref_anti_alias_summary),
                defaultValue = false
            ),
            switch(
                key = PreferKey.highBrush,
                title = str(R.string.high_brush_title),
                summary = str(R.string.high_brush_summary),
                defaultValue = true
            ),
            numberAction(
                key = PreferKey.bitmapCacheSize,
                title = str(R.string.bitmap_cache_size),
                summary = str(R.string.bitmap_cache_size_summary, AppConfig.bitmapCacheSize.toString()),
                min = 1,
                max = 1024,
                value = AppConfig.bitmapCacheSize,
                onSelected = {
                    AppConfig.bitmapCacheSize = it
                    ImageProvider.bitmapLruCache.resize(ImageProvider.cacheSize)
                }
            ),
            numberAction(
                key = PreferKey.imageRetainNum,
                title = str(R.string.image_retain_number),
                summary = str(R.string.image_retain_number_summary, AppConfig.imageRetainNum.toString()),
                min = 0,
                max = 999,
                value = AppConfig.imageRetainNum,
                onSelected = { AppConfig.imageRetainNum = it }
            ),
            numberAction(
                key = PreferKey.preDownloadNum,
                title = str(R.string.pre_download),
                summary = str(R.string.pre_download_s, AppConfig.preDownloadNum.toString()),
                min = 0,
                max = 9999,
                value = AppConfig.preDownloadNum,
                onSelected = { AppConfig.preDownloadNum = it }
            ),
            switch(
                key = PreferKey.replaceEnableDefault,
                title = str(R.string.replace_enable_default_t),
                summary = str(R.string.replace_enable_default_s),
                defaultValue = true
            ),
            switch(
                key = KEY_MEDIA_BUTTON_ON_EXIT,
                title = str(R.string.media_button_on_exit_title),
                summary = str(R.string.media_button_on_exit_summary),
                defaultValue = true
            ),
            switch(
                key = PreferKey.readAloudByMediaButton,
                title = str(R.string.read_aloud_by_media_button_title),
                summary = str(R.string.read_aloud_by_media_button_summary),
                defaultValue = false
            ),
            switch(
                key = PreferKey.ignoreAudioFocus,
                title = str(R.string.ignore_audio_focus_title),
                summary = str(R.string.ignore_audio_focus_summary),
                defaultValue = false
            ),
            switch(
                key = PreferKey.autoClearExpired,
                title = str(R.string.auto_clear_expired),
                summary = str(R.string.auto_clear_expired_summary),
                defaultValue = true
            ),
            switch(
                key = PreferKey.showAddToShelfAlert,
                title = str(R.string.show_add_to_shelf_alert_title),
                summary = str(R.string.show_add_to_shelf_alert_summary),
                defaultValue = true
            ),
            switch(
                key = KEY_AUTO_UPDATE_VARIANT,
                title = str(R.string.auto_update),
                summary = str(R.string.auto_update_summary),
                defaultValue = true
            ),
            switch(
                key = PreferKey.showMangaUi,
                title = str(R.string.show_manga_ui),
                defaultValue = true
            ),
            SettingActionSpec(
                key = PreferKey.videoSetting,
                title = str(R.string.video_setting),
                summary = str(R.string.video_setting_summary),
                onClick = { showDialogFragment(SettingsDialog(requireActivity())) }
            ),
            switch(
                key = PreferKey.autoRefreshMediaToc,
                title = str(R.string.auto_refresh_media_toc),
                summary = str(R.string.auto_refresh_media_toc_summary),
                defaultValue = true
            ),
            numberAction(
                key = PreferKey.webPort,
                title = str(R.string.web_port_title),
                summary = str(R.string.web_port_summary, AppConfig.webPort.toString()),
                min = 1024,
                max = 60000,
                value = AppConfig.webPort,
                onSelected = { AppConfig.webPort = it }
            ),
            SettingActionSpec(
                key = PreferKey.cleanCache,
                title = str(R.string.clear_cache),
                summary = str(R.string.clear_cache_summary),
                onClick = ::clearCache
            ),
            SettingActionSpec(
                key = PreferKey.clearWebViewData,
                title = str(R.string.clear_webview_data),
                summary = str(R.string.clear_webview_data_summary),
                onClick = ::clearWebViewData
            ),
            SettingActionSpec(
                key = PreferKey.shrinkDatabase,
                title = str(R.string.shrink_database),
                summary = str(R.string.shrink_database_summary),
                onClick = ::shrinkDatabase
            ),
            numberAction(
                key = PreferKey.threadCount,
                title = str(R.string.threads_num_title),
                summary = str(R.string.threads_num, AppConfig.threadCount.toString()),
                min = 1,
                max = 999,
                value = AppConfig.threadCount,
                onSelected = { AppConfig.threadCount = it }
            ),
            switch(
                key = PreferKey.processText,
                title = str(R.string.add_to_text_context_menu_t),
                summary = str(R.string.add_to_text_context_menu_s),
                defaultValue = true
            ),
            switch(
                key = PreferKey.recordLog,
                title = str(R.string.record_log),
                summary = str(R.string.record_debug_log),
                defaultValue = false
            ),
            switch(
                key = PreferKey.recordHeapDump,
                title = str(R.string.record_heap_dump_t),
                summary = str(R.string.record_heap_dump_s),
                defaultValue = false
            )
        )
    }

    private fun switch(
        key: String,
        title: String,
        summary: String? = null,
        defaultValue: Boolean,
        visible: Boolean = true
    ): SettingSwitchSpec {
        return SettingSwitchSpec(
            key = key,
            title = title,
            summary = summary,
            checked = booleanSetting(key, defaultValue),
            visible = visible,
            onCheckedChange = { updateBooleanSetting(key, it) }
        )
    }

    private fun choice(
        key: String,
        title: String,
        entriesRes: Int,
        valuesRes: Int,
        defaultValue: String,
        summary: String? = null
    ): SettingChoiceSpec {
        val options = choiceOptions(entriesRes, valuesRes)
        val selectedValue = stringSetting(key, defaultValue)
        return SettingChoiceSpec(
            key = key,
            title = title,
            summary = summary ?: choiceLabel(options, selectedValue),
            options = options,
            selectedValue = selectedValue,
            onSelected = { updateStringSetting(key, it) }
        )
    }

    private fun intChoice(
        key: String,
        title: String,
        entriesRes: Int,
        valuesRes: Int,
        defaultValue: Int,
        summary: String? = null
    ): SettingChoiceSpec {
        val options = choiceOptions(entriesRes, valuesRes)
        val selectedValue = intSetting(key, defaultValue).toString()
        return SettingChoiceSpec(
            key = key,
            title = title,
            summary = summary ?: choiceLabel(options, selectedValue),
            options = options,
            selectedValue = selectedValue,
            onSelected = { updateIntSetting(key, it.toIntOrNull() ?: defaultValue) }
        )
    }

    private fun choiceOptions(
        entriesRes: Int,
        valuesRes: Int
    ): List<SettingChoiceOption> {
        val entries = strings.getStringArray(entriesRes)
        val values = strings.getStringArray(valuesRes)
        return values.mapIndexed { index, value ->
            SettingChoiceOption(
                value = value,
                label = entries.getOrElse(index) { value }
            )
        }
    }

    private fun choiceLabel(
        options: List<SettingChoiceOption>,
        selectedValue: String
    ): String {
        return options.firstOrNull { it.value == selectedValue }
            ?.label
            ?.toString()
            ?: selectedValue
    }

    private fun numberAction(
        key: String,
        title: String,
        summary: String,
        min: Int,
        max: Int,
        value: Int,
        onSelected: (Int) -> Unit
    ): SettingActionSpec {
        return SettingActionSpec(
            key = key,
            title = title,
            summary = summary,
            onClick = {
                showComposeNumberPickerDialog(
                    title = title,
                    value = value,
                    minValue = min,
                    maxValue = max,
                    onValue = onSelected
                )
            }
        )
    }

    private fun epubReadEngineSummary(): String {
        val current = when (AppConfig.epubReadEngine) {
            "core" -> str(R.string.epub_read_engine_core)
            else -> str(R.string.epub_read_engine_text)
        }
        return "${str(R.string.epub_read_engine_summary)}\n$current"
    }

    private fun showUserAgentDialog() {
        showComposeTextInputDialog(
            title = str(R.string.user_agent),
            hint = str(R.string.user_agent),
            initialValue = userAgentValue(),
            onPositive = { userAgent ->
                if (userAgent.isNullOrBlank()) {
                    removePref(PreferKey.userAgent)
                } else {
                    putPrefString(PreferKey.userAgent, userAgent)
                }
            }
        )
    }

    private fun userAgentValue(): String {
        return stringSetting(PreferKey.userAgent, "")
            .ifBlank { DEFAULT_USER_AGENT }
    }

    private fun clearCache() {
        showComposeConfirmDialog(
            title = str(R.string.clear_cache),
            message = str(R.string.sure_del),
            positiveText = str(android.R.string.ok),
            negativeText = str(R.string.no),
            onPositive = { viewModel.clearCache() }
        )
    }

    private fun shrinkDatabase() {
        showComposeConfirmDialog(
            title = str(R.string.sure),
            message = str(R.string.shrink_database),
            positiveText = str(android.R.string.ok),
            negativeText = str(R.string.no),
            onPositive = { viewModel.shrinkDatabase() }
        )
    }

    private fun clearWebViewData() {
        showComposeConfirmDialog(
            title = str(R.string.clear_webview_data),
            message = str(R.string.sure_del),
            positiveText = str(android.R.string.ok),
            negativeText = str(R.string.no),
            onPositive = { viewModel.clearWebViewData() }
        )
    }

    private fun isProcessTextEnabled(): Boolean {
        return packageManager.getComponentEnabledSetting(componentName) !=
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    private fun setProcessTextEnable(enable: Boolean) {
        if (enable) {
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } else {
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    private fun alertLocalPassword() {
        showComposeTextInputDialog(
            title = str(R.string.set_local_password),
            hint = "password",
            message = str(R.string.set_local_password_summary),
            onPositive = { LocalConfig.password = it }
        )
    }

    companion object {
        private const val KEY_LANGUAGE = "language"
        private const val KEY_LOCAL_PASSWORD = "localPassword"
        private const val KEY_MEDIA_BUTTON_ON_EXIT = "mediaButtonOnExit"
        private const val KEY_AUTO_UPDATE_VARIANT = "autoUpdateVariant"
        private val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" +
                BuildConfig.Cronet_Main_Version +
                " Safari/537.36"
    }
}
