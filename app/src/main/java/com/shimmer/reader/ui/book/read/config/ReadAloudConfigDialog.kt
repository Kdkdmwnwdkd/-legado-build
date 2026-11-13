package com.shimmer.reader.ui.book.read.config

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import com.shimmer.reader.R
import com.shimmer.reader.base.BasePrefDialogFragment
import com.shimmer.reader.constant.EventBus
import com.shimmer.reader.constant.PreferKey
import com.shimmer.reader.data.appDb
import com.shimmer.reader.help.IntentHelp
import com.shimmer.reader.help.book.BookIdentity
import com.shimmer.reader.help.config.AppConfig
import com.shimmer.reader.help.readaloud.ReadAloudCacheStore
import com.shimmer.reader.help.readaloud.ReadAloudConfigChangeNotifier
import com.shimmer.reader.help.readaloud.ReadAloudSpeakerLoudnessManager
import com.shimmer.reader.help.readaloud.role.ReadAloudPreprocessRuleConfig
import com.shimmer.reader.help.readaloud.role.ReadAloudQuotePair
import com.shimmer.reader.help.readaloud.role.ReadAloudRolePreprocessor
import com.shimmer.reader.help.readaloud.role.ReadAloudSoundEffectPreprocessor
import com.shimmer.reader.help.readaloud.speech.SpeechRoute
import com.shimmer.reader.lib.theme.UiCorner
import com.shimmer.reader.lib.permission.Permissions
import com.shimmer.reader.lib.permission.PermissionsCompat
import com.shimmer.reader.lib.theme.dialogSurfaceBackground
import com.shimmer.reader.lib.theme.primaryColor
import com.shimmer.reader.lib.theme.primaryTextColor
import com.shimmer.reader.lib.theme.themeCardColorOrDefault
import com.shimmer.reader.lib.theme.uiTypeface
import com.shimmer.reader.model.ReadAloud
import com.shimmer.reader.model.ReadBook
import com.shimmer.reader.service.BaseReadAloudService
import com.shimmer.reader.ui.book.character.BookCharacterManageActivity
import com.shimmer.reader.ui.config.compose.ComposeSettingFragment
import com.shimmer.reader.ui.config.compose.SettingActionSpec
import com.shimmer.reader.ui.config.compose.SettingChoiceOption
import com.shimmer.reader.ui.config.compose.SettingChoiceSpec
import com.shimmer.reader.ui.config.compose.SettingItemSpec
import com.shimmer.reader.ui.config.compose.SettingPageSpec
import com.shimmer.reader.ui.config.compose.SettingSectionSpec
import com.shimmer.reader.ui.config.compose.SettingSliderSpec
import com.shimmer.reader.ui.config.compose.SettingSwitchSpec
import com.shimmer.reader.ui.widget.compose.showComposeChoiceListDialog
import com.shimmer.reader.ui.widget.compose.showComposeConfirmDialog
import com.shimmer.reader.ui.widget.compose.showComposeNumberPickerDialog
import com.shimmer.reader.ui.widget.compose.showComposeTextInputDialog
import com.shimmer.reader.utils.ConvertUtils
import com.shimmer.reader.utils.dpToPx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import com.shimmer.reader.utils.postEvent
import com.shimmer.reader.utils.setLayout
import com.shimmer.reader.utils.showDialogFragment
import com.shimmer.reader.utils.startActivity
import com.shimmer.reader.utils.toastOnUi

private const val KEY_AI_READ_ALOUD_BGM_MANAGE = "aiReadAloudBgmManage"
private const val KEY_AI_READ_ALOUD_USAGE_RECORDS = "aiReadAloudUsageRecords"
private const val KEY_AI_READ_ALOUD_MODEL_ROUTING = "aiReadAloudModelRouting"
private const val KEY_AI_READ_ALOUD_CHARACTER_MANAGE = "aiReadAloudCharacterManage"
private const val KEY_READ_ALOUD_SPEAKER_MANAGE = "readAloudSpeakerManage"
private const val KEY_READ_ALOUD_LOUDNESS_RESET = "readAloudSpeakerLoudnessReset"
private const val KEY_MEDIA_BUTTON_PER_NEXT = "mediaButtonPerNext"
private const val KEY_SYS_TTS_CONFIG = "sysTtsConfig"
private const val KEY_READ_ALOUD_CACHE_CLEAR = "readAloudCacheClear"
private val SPEAKER_MANAGE_SUMMARY: String
    get() = appCtx.getString(R.string.ra_cfg_speaker_manage_summary)

enum class ReadAloudConfigGroup(
    @param:StringRes val titleRes: Int,
    val preferenceKeys: Set<String>
) {
    Base(
        R.string.ra_cfg_tab_base,
        setOf(
            PreferKey.ignoreAudioFocus,
            PreferKey.pauseReadAloudWhilePhoneCalls,
            PreferKey.readAloudWakeLock,
            PreferKey.showReadAloudFloatingBall,
            KEY_MEDIA_BUTTON_PER_NEXT
        )
    ),
    Reading(
        R.string.ra_cfg_tab_reading,
        setOf(
            PreferKey.readAloudByPage,
            PreferKey.streamReadAloudAudio,
            PreferKey.ttsFollowSys,
            PreferKey.ttsSpeechRate
        )
    ),
    AiRole(
        R.string.ra_cfg_tab_multi_role,
        setOf(
            PreferKey.aiReadAloudRoleEnabled,
            KEY_AI_READ_ALOUD_MODEL_ROUTING,
            PreferKey.aiReadAloudRoleBackupModelId,
            PreferKey.aiReadAloudRoleFirstResponseTimeoutSeconds,
            PreferKey.aiReadAloudAudioModelId,
            PreferKey.aiReadAloudAudioBackupModelId,
            PreferKey.aiReadAloudAutoCreateCharacters,
            PreferKey.aiReadAloudAutoCreateCharacterPrompt,
            PreferKey.aiReadAloudAutoCreateAvatar,
            KEY_READ_ALOUD_SPEAKER_MANAGE,
            PreferKey.aiReadAloudRoleMode,
            PreferKey.aiReadAloudRolePreprocess,
            PreferKey.aiReadAloudRoleThreadCount,
            PreferKey.aiReadAloudRoleContextParagraphs,
            PreferKey.aiReadAloudRoleMergeGapParagraphs,
            PreferKey.aiReadAloudRolePrompt,
            KEY_AI_READ_ALOUD_USAGE_RECORDS,
            PreferKey.aiReadAloudBgmEnabled,
            KEY_AI_READ_ALOUD_BGM_MANAGE,
            PreferKey.aiReadAloudBgmPrompt,
            PreferKey.aiReadAloudSoundEffectPrompt,
            PreferKey.aiReadAloudBgmVolume,
            PreferKey.aiReadAloudSfxVolume,
            PreferKey.readAloudSpeakerLoudnessEnabled,
            KEY_READ_ALOUD_LOUDNESS_RESET
        )
    ),
    Engine(
        R.string.ra_cfg_tab_engine,
        setOf(
            PreferKey.ttsEngine,
            KEY_READ_ALOUD_SPEAKER_MANAGE,
            KEY_SYS_TTS_CONFIG
        )
    );

    companion object {
        private val hiddenPreferenceKeys = setOf(
            PreferKey.aiReadAloudRoleModelId,
            PreferKey.readAloudTargetVoiceVolume,
            PreferKey.readAloudMaxSpeakerGain,
            PreferKey.readAloudNarratorBaseGain
        )
        val allPreferenceKeys: Set<String> =
            values().flatMap { it.preferenceKeys }.toSet() + hiddenPreferenceKeys
    }
}

class ReadAloudConfigDialog : BasePrefDialogFragment() {
    private val readAloudPreferTag = "readAloudPreferTag"
    private val groupTabs = arrayListOf<Pair<ReadAloudConfigGroup, TextView>>()
    private var selectedGroup = ReadAloudConfigGroup.Base

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setBackgroundDrawableResource(R.color.transparent)
            val maxHeight = minOf(
                (resources.displayMetrics.heightPixels * 0.72f).toInt(),
                620.dpToPx()
            ).coerceAtLeast(360.dpToPx())
            setLayout(0.9f, maxHeight)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        groupTabs.clear()
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = requireContext().dialogSurfaceBackground
            clipToOutline = true
            setPadding(10.dpToPx(), 10.dpToPx(), 10.dpToPx(), 8.dpToPx())
        }
        val tabBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            setBackgroundResource(R.drawable.bg_bookshelf_tag_track)
        }
        ReadAloudConfigGroup.values().forEach { group ->
            val tab = TextView(requireContext()).apply {
                text = getString(group.titleRes)
                gravity = Gravity.CENTER
                textSize = 14f
                typeface = requireContext().uiTypeface()
                isSingleLine = true
                background = UiCorner.actionSelector(
                    Color.TRANSPARENT,
                    requireContext().themeCardColorOrDefault(),
                    UiCorner.actionRadius(requireContext())
                )
                setOnClickListener { selectGroup(group) }
            }
            groupTabs.add(group to tab)
            tabBar.addView(
                tab,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )
        }
        root.addView(
            tabBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                42.dpToPx()
            ).apply {
                bottomMargin = 8.dpToPx()
            }
        )
        root.addView(
            FrameLayout(requireContext()).apply { id = R.id.tag1 },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        container?.addView(root)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        var preferenceFragment = childFragmentManager.findFragmentByTag(readAloudPreferTag)
        if (preferenceFragment == null) preferenceFragment = ReadAloudPreferenceFragment()
        childFragmentManager.beginTransaction()
            .replace(R.id.tag1, preferenceFragment, readAloudPreferTag)
            .commit()
        selectGroup(selectedGroup)
    }

    private fun selectGroup(group: ReadAloudConfigGroup) {
        selectedGroup = group
        groupTabs.forEach { (tabGroup, tab) ->
            val selected = tabGroup == group
            tab.isSelected = selected
            tab.setTextColor(
                if (selected) requireContext().primaryColor
                else requireContext().primaryTextColor
            )
        }
        (childFragmentManager.findFragmentByTag(readAloudPreferTag) as? ReadAloudPreferenceFragment)
            ?.selectGroup(group)
    }

    class ReadAloudPreferenceFragment : ComposeSettingFragment(),
        SpeakEngineDialog.CallBack {

        private var selectedGroup = ReadAloudConfigGroup.Base

        override val titleRes: Int = R.string.aloud_config

        override val applyActivityTitle: Boolean = false

        override val autoOpenTargetItem: Boolean = false

        private val speakEngineSummary: String
            get() {
                val route = SpeechRoute.fromTtsEngineValue(ReadAloud.ttsEngine)
                val engineName = when (route.engineType) {
                    SpeechRoute.ENGINE_HTTP -> route.engineValue.toLongOrNull()
                        ?.let { appDb.httpTTSDao.getName(it) }
                        ?: "HTTP TTS"
                    SpeechRoute.ENGINE_SYSTEM -> route.speakerName.ifBlank { getString(R.string.system_tts) }
                    else -> getString(R.string.system_tts)
                }
                return buildList {
                    add(engineName)
                    route.speakerName
                        .takeIf { it.isNotBlank() && it != engineName }
                        ?.let(::add)
                    route.emotionName.takeIf { it.isNotBlank() }?.let(::add)
                }.joinToString(" · ")
            }

        /** 朗读缓存占用；null 表示还在后台统计 */
        private var cacheSizeBytes: Long? = null

        private val cacheSummary: String
            get() {
                val used = cacheSizeBytes
                    ?: return getString(R.string.ra_cache_summary_calculating)
                return getString(
                    R.string.ra_cache_summary,
                    ConvertUtils.formatFileSize(used),
                    cacheLimitLabel(AppConfig.readAloudCacheMb)
                )
            }

        private fun cacheLimitLabel(mb: Int): String =
            if (mb >= 1024) getString(R.string.ra_cache_limit_gb, mb / 1024)
            else getString(R.string.ra_cache_limit_mb, mb)

        override fun onResume() {
            super.onResume()
            loadCacheSize()
        }

        private fun loadCacheSize() {
            lifecycleScope.launch {
                val size = withContext(Dispatchers.IO) { ReadAloudCacheStore.sizeBytes() }
                if (!isAdded) return@launch
                cacheSizeBytes = size
                refreshSettings()
            }
        }

        private fun showCacheLimitDialog() {
            val limits = listOf(128, 256, 512, 1024)
            val current = AppConfig.readAloudCacheMb
            showComposeChoiceListDialog(
                title = getString(R.string.ra_cache_limit),
                labels = limits.map { cacheLimitLabel(it) },
                selectedIndex = limits.indexOfFirst { it == current }.coerceAtLeast(0),
                message = getString(R.string.ra_cache_limit_message)
            ) { index ->
                limits.getOrNull(index)?.let {
                    AppConfig.readAloudCacheMb = it
                    refreshSettings()
                }
            }
        }

        private fun confirmClearCache() {
            showComposeConfirmDialog(
                title = getString(R.string.ra_cache_clear),
                message = getString(R.string.ra_cache_clear_confirm),
                dangerPositive = true,
                onPositive = {
                    val context = requireContext().applicationContext
                    lifecycleScope.launch {
                        // SimpleCache 占着目录，必须等服务销毁再删
                        if (BaseReadAloudService.isRun) {
                            ReadAloud.stop(context)
                            delay(800)
                        }
                        val cleared = withContext(Dispatchers.IO) { ReadAloudCacheStore.clear() }
                        val size = withContext(Dispatchers.IO) { ReadAloudCacheStore.sizeBytes() }
                        if (!isAdded) return@launch
                        cacheSizeBytes = size
                        refreshSettings()
                        toastOnUi(
                            if (cleared) R.string.ra_cache_clear_done
                            else R.string.ra_cache_clear_failed
                        )
                    }
                }
            )
        }

        override fun buildPageSpec(): SettingPageSpec {
            return SettingPageSpec(
                titleRes = titleRes,
                sections = listOf(
                    SettingSectionSpec(
                        title = getString(selectedGroup.titleRes),
                        items = when (selectedGroup) {
                            ReadAloudConfigGroup.Base -> baseItems()
                            ReadAloudConfigGroup.Reading -> readingItems()
                            ReadAloudConfigGroup.AiRole -> aiRoleItems()
                            ReadAloudConfigGroup.Engine -> engineItems()
                        }
                    )
                )
            )
        }

        override fun onSettingPreferenceChanged(key: String) {
            when (key) {
                PreferKey.readAloudByPage, PreferKey.streamReadAloudAudio -> {
                    if (BaseReadAloudService.isRun) {
                        postEvent(EventBus.MEDIA_BUTTON, false)
                    }
                }

                PreferKey.ttsFollowSys,
                PreferKey.ttsSpeechRate -> {
                    applySpeechRate()
                }

                PreferKey.showReadAloudFloatingBall -> Unit

                PreferKey.aiReadAloudRoleEnabled,
                PreferKey.aiReadAloudRoleModelId,
                PreferKey.aiReadAloudRoleBackupModelId,
                PreferKey.aiReadAloudRoleFirstResponseTimeoutSeconds,
                PreferKey.aiReadAloudAudioModelId,
                PreferKey.aiReadAloudAudioBackupModelId,
                PreferKey.aiReadAloudAutoCreateCharacters,
                PreferKey.aiReadAloudAutoCreateCharacterPrompt,
                PreferKey.aiReadAloudAutoCreateAvatar,
                PreferKey.aiReadAloudRoleMode,
                PreferKey.aiReadAloudRolePreprocess,
                PreferKey.aiReadAloudRoleThreadCount,
                PreferKey.aiReadAloudRoleContextParagraphs,
                PreferKey.aiReadAloudRoleMergeGapParagraphs,
                PreferKey.aiReadAloudRolePrompt -> {
                    postReadAloudConfigChanged(
                        if (key == PreferKey.aiReadAloudRoleEnabled) {
                            EventBus.READ_ALOUD_CONFIG_SCOPE_ENGINE
                        } else {
                            EventBus.READ_ALOUD_CONFIG_SCOPE_SPEECH
                        }
                    )
                }
                PreferKey.aiReadAloudBgmEnabled,
                PreferKey.aiReadAloudBgmPrompt,
                PreferKey.aiReadAloudSoundEffectPrompt,
                PreferKey.aiReadAloudBgmVolume,
                PreferKey.aiReadAloudSfxVolume,
                PreferKey.readAloudSpeakerLoudnessEnabled,
                PreferKey.readAloudTargetVoiceVolume,
                PreferKey.readAloudMaxSpeakerGain,
                PreferKey.readAloudNarratorBaseGain -> {
                    postReadAloudConfigChanged(EventBus.READ_ALOUD_CONFIG_SCOPE_AUDIO)
                }
            }
        }

        private fun postReadAloudConfigChanged(scope: String) {
            ReadAloudConfigChangeNotifier.notify(scope)
        }

        override fun upSpeakEngineSummary() {
            refreshSettings()
        }

        fun selectGroup(group: ReadAloudConfigGroup) {
            selectedGroup = group
            refreshSettings()
        }

        private fun applySpeechRate() {
            ReadAloud.upTtsSpeechRate(requireContext())
            if (!BaseReadAloudService.pause) {
                ReadAloud.pause(requireContext())
                ReadAloud.resume(requireContext())
            }
        }

        private fun updateAiRolePreferences() {
            refreshSettings()
        }

        private fun baseItems(): List<SettingItemSpec> {
            return listOf(
                switch(
                    key = PreferKey.ignoreAudioFocus,
                    title = getString(R.string.ignore_audio_focus_title),
                    summary = getString(R.string.ignore_audio_focus_summary),
                    defaultValue = false
                ),
                switch(
                    key = PreferKey.pauseReadAloudWhilePhoneCalls,
                    title = getString(R.string.pause_read_aloud_while_phone_calls_title),
                    summary = getString(R.string.pause_read_aloud_while_phone_calls_summary),
                    defaultValue = false,
                    enabled = AppConfig.ignoreAudioFocus
                ),
                switch(
                    key = PreferKey.readAloudWakeLock,
                    title = getString(R.string.read_aloud_wake_lock),
                    summary = getString(R.string.read_aloud_wake_lock_summary),
                    defaultValue = false
                ),
                switch(
                    key = PreferKey.showReadAloudFloatingBall,
                    title = getString(R.string.read_aloud_floating_ball),
                    summary = getString(R.string.read_aloud_floating_ball_summary),
                    checked = booleanSetting(PreferKey.showReadAloudFloatingBall, true),
                    onCheckedChange = ::setReadAloudFloatingBallEnabled
                ),
                switch(
                    key = KEY_MEDIA_BUTTON_PER_NEXT,
                    title = getString(R.string.pref_media_button_per_next),
                    summary = getString(R.string.pref_media_button_per_next_summary),
                    defaultValue = false
                )
            )
        }

        private fun setReadAloudFloatingBallEnabled(enabled: Boolean) {
            if (!enabled) {
                updateBooleanSetting(PreferKey.showReadAloudFloatingBall, false)
                return
            }
            PermissionsCompat.Builder()
                .addPermissions(Permissions.SYSTEM_ALERT_WINDOW)
                .rationale(R.string.read_aloud_floating_ball_permission_rationale)
                .onGranted {
                    updateBooleanSetting(PreferKey.showReadAloudFloatingBall, true)
                }
                .onDenied {
                    updateBooleanSetting(PreferKey.showReadAloudFloatingBall, false)
                }
                .onError {
                    updateBooleanSetting(PreferKey.showReadAloudFloatingBall, false)
                }
                .request()
        }

        private fun readingItems(): List<SettingItemSpec> {
            return listOf(
                switch(
                    key = PreferKey.readAloudByPage,
                    title = getString(R.string.read_aloud_by_page),
                    summary = getString(R.string.read_aloud_by_page_summary),
                    defaultValue = false
                ),
                switch(
                    key = PreferKey.streamReadAloudAudio,
                    title = getString(R.string.stream_read_aloud_audio),
                    summary = getString(R.string.stream_read_aloud_audio_summary),
                    defaultValue = false
                ),
                switch(
                    key = PreferKey.ttsFollowSys,
                    title = getString(R.string.flow_sys),
                    summary = getString(R.string.ra_cfg_follow_sys_rate_summary),
                    defaultValue = true
                ),
                slider(
                    key = PreferKey.ttsSpeechRate,
                    title = getString(R.string.read_aloud_speed),
                    summary = formatSpeechRate(AppConfig.ttsSpeechRate),
                    value = AppConfig.ttsSpeechRate.coerceIn(0, 45),
                    range = 0..45,
                    enabled = !AppConfig.ttsFlowSys
                )
            )
        }

        private fun aiRoleItems(): List<SettingItemSpec> {
            val enabled = AppConfig.aiReadAloudRoleEnabled
            val hasModel = AppConfig.aiReadAloudRoleModelConfig != null
            val fullMode = AppConfig.aiReadAloudRoleMode == AppConfig.AI_READ_ALOUD_ROLE_MODE_FULL
            val audioEnabled = enabled && AppConfig.aiReadAloudBgmEnabled
            val bgmCount = appDb.readAloudBgmDao
                .enabledTracksByType(com.shimmer.reader.data.entities.ReadAloudBgmTrack.TYPE_BGM)
                .size
            val sfxCount = appDb.readAloudBgmDao
                .enabledTracksByType(com.shimmer.reader.data.entities.ReadAloudBgmTrack.TYPE_SFX)
                .size
            val loudnessCount = ReadAloudSpeakerLoudnessManager.learnedSpeakerCount()
            val currentBook = ReadBook.book
            val characterCount = runCatching {
                currentBook?.let {
                    appDb.bookCharacterDao.characters(BookIdentity.key(it)).size
                } ?: 0
            }.getOrDefault(0)
            return listOf(
                switch(
                    key = PreferKey.aiReadAloudRoleEnabled,
                    title = getString(R.string.ra_cfg_multi_role),
                    summary = if (hasModel) getString(R.string.ra_cfg_multi_role_summary_on) else getString(R.string.ra_cfg_multi_role_summary_off),
                    checked = enabled,
                    enabled = hasModel,
                    onCheckedChange = { AppConfig.aiReadAloudRoleEnabled = it }
                ),
                action(
                    key = KEY_AI_READ_ALOUD_MODEL_ROUTING,
                    title = getString(R.string.ra_cfg_ai_model_timeout),
                    summary = aiReadAloudModelRoutingSummary(),
                    enabled = hasModel,
                    onClick = ::showAiReadAloudModelRoutingDialog
                ),
                switch(
                    key = PreferKey.aiReadAloudAutoCreateCharacters,
                    title = getString(R.string.ra_cfg_auto_create),
                    summary = if (AppConfig.aiReadAloudAutoCreateCharacters) {
                        getString(R.string.ra_cfg_auto_create_summary_on)
                    } else {
                        getString(R.string.ra_cfg_auto_create_summary_off)
                    },
                    checked = AppConfig.aiReadAloudAutoCreateCharacters,
                    enabled = enabled,
                    onCheckedChange = { AppConfig.aiReadAloudAutoCreateCharacters = it }
                ),
                action(
                    key = PreferKey.aiReadAloudAutoCreateCharacterPrompt,
                    title = getString(R.string.ra_cfg_auto_card_prompt),
                    summary = firstLineSummary(
                        value = AppConfig.aiReadAloudAutoCreateCharacterPrompt,
                        useDefault = AppConfig.aiReadAloudUsingDefaultAutoCreateCharacterPrompt
                    ),
                    enabled = enabled && AppConfig.aiReadAloudAutoCreateCharacters,
                    onClick = ::showAutoCreateCharacterPromptDialog
                ),
                switch(
                    key = PreferKey.aiReadAloudAutoCreateAvatar,
                    title = getString(R.string.ra_cfg_auto_avatar),
                    summary = when {
                        !AppConfig.aiReadAloudAutoCreateAvatar -> getString(R.string.ra_cfg_auto_avatar_summary_off)
                        AppConfig.aiEnabledImageProviders.isEmpty() -> getString(R.string.ra_cfg_auto_avatar_no_provider)
                        else -> getString(R.string.ra_cfg_auto_avatar_summary_on)
                    },
                    checked = AppConfig.aiReadAloudAutoCreateAvatar,
                    enabled = enabled && AppConfig.aiReadAloudAutoCreateCharacters,
                    onCheckedChange = { AppConfig.aiReadAloudAutoCreateAvatar = it }
                ),
                action(
                    key = KEY_AI_READ_ALOUD_CHARACTER_MANAGE,
                    title = getString(R.string.ra_cfg_character_manage),
                    summary = when {
                        currentBook == null -> getString(R.string.ra_cfg_character_manage_no_book)
                        characterCount > 0 -> getString(
                            R.string.ra_cfg_character_manage_summary,
                            characterCount
                        )
                        else -> getString(R.string.ra_cfg_character_manage_empty)
                    },
                    enabled = currentBook != null,
                    onClick = {
                        val bookUrl = currentBook?.bookUrl.orEmpty()
                        startActivity<BookCharacterManageActivity> {
                            putExtra(BookCharacterManageActivity.EXTRA_BOOK_URL, bookUrl)
                        }
                    }
                ),
                action(
                    key = KEY_READ_ALOUD_SPEAKER_MANAGE,
                    title = getString(R.string.ra_cfg_speaker_manage),
                    summary = SPEAKER_MANAGE_SUMMARY,
                    onClick = { startActivity<SpeakerGroupManageActivity>() }
                ),
                choice(
                    key = PreferKey.aiReadAloudRoleMode,
                    title = getString(R.string.ra_cfg_process_mode),
                    entriesRes = R.array.ai_read_aloud_role_mode_title,
                    valuesRes = R.array.ai_read_aloud_role_mode_value,
                    selectedValue = AppConfig.aiReadAloudRoleMode,
                    enabled = enabled
                ),
                action(
                    key = PreferKey.aiReadAloudRolePreprocess,
                    title = getString(R.string.ra_cfg_preprocess_rules),
                    summary = getString(
                        R.string.ra_cfg_preprocess_summary,
                        if (AppConfig.aiReadAloudRoleUsingDefaultPreprocessRules) {
                            getString(R.string.ra_cfg_builtin_prefix)
                        } else ""
                    ),
                    enabled = enabled,
                    onClick = ::showMultiRolePreprocessRulesDialog
                ),
                action(
                    key = PreferKey.aiReadAloudRoleThreadCount,
                    title = getString(R.string.ra_cfg_concurrency),
                    summary = getString(R.string.ra_cfg_concurrency_summary, AppConfig.aiReadAloudRoleThreadCount),
                    visible = !fullMode,
                    enabled = enabled
                ) {
                    showAiRoleNumberDialog(getString(R.string.ra_cfg_concurrency), AppConfig.aiReadAloudRoleThreadCount, 1, 10) {
                        AppConfig.aiReadAloudRoleThreadCount = it
                        updateAiRolePreferences()
                    }
                },
                action(
                    key = PreferKey.aiReadAloudRoleContextParagraphs,
                    title = getString(R.string.ra_cfg_context_paragraphs),
                    summary = getString(R.string.ra_cfg_context_summary, AppConfig.aiReadAloudRoleContextParagraphs),
                    visible = !fullMode,
                    enabled = enabled
                ) {
                    showAiRoleNumberDialog(getString(R.string.ra_cfg_context_paragraphs), AppConfig.aiReadAloudRoleContextParagraphs, 0, 20) {
                        AppConfig.aiReadAloudRoleContextParagraphs = it
                        updateAiRolePreferences()
                    }
                },
                action(
                    key = PreferKey.aiReadAloudRoleMergeGapParagraphs,
                    title = getString(R.string.ra_cfg_merge_gap),
                    summary = getString(R.string.ra_cfg_merge_gap_summary, AppConfig.aiReadAloudRoleMergeGapParagraphs),
                    visible = !fullMode,
                    enabled = enabled
                ) {
                    showAiRoleNumberDialog(getString(R.string.ra_cfg_merge_gap), AppConfig.aiReadAloudRoleMergeGapParagraphs, 0, 10) {
                        AppConfig.aiReadAloudRoleMergeGapParagraphs = it
                        updateAiRolePreferences()
                    }
                },
                action(
                    key = PreferKey.aiReadAloudRolePrompt,
                    title = getString(R.string.ra_cfg_role_prompt),
                    summary = firstLineSummary(
                        value = AppConfig.aiReadAloudRolePrompt,
                        useDefault = AppConfig.aiReadAloudRoleUsingDefaultPrompt
                    ),
                    enabled = enabled,
                    onClick = ::showMultiRolePromptDialog
                ),
                action(
                    key = KEY_AI_READ_ALOUD_USAGE_RECORDS,
                    title = getString(R.string.ra_cfg_usage_records),
                    summary = appDb.aiReadAloudUsageRecordDao.list(limit = 1000).size
                        .let {
                            if (it > 0) getString(R.string.ra_cfg_usage_records_summary, it)
                            else getString(R.string.ra_cfg_usage_records_empty)
                        },
                    onClick = {
                        startActivity(
                            android.content.Intent(requireContext(), AiReadAloudUsageRecordActivity::class.java)
                        )
                    }
                ),
                switch(
                    key = PreferKey.aiReadAloudBgmEnabled,
                    title = getString(R.string.ra_cfg_bgm),
                    summary = if (AppConfig.aiReadAloudBgmEnabled) {
                        getString(R.string.ra_cfg_bgm_summary_on)
                    } else {
                        getString(R.string.ra_cfg_bgm_summary_off)
                    },
                    checked = AppConfig.aiReadAloudBgmEnabled,
                    enabled = enabled,
                    onCheckedChange = { AppConfig.aiReadAloudBgmEnabled = it }
                ),
                action(
                    key = KEY_AI_READ_ALOUD_BGM_MANAGE,
                    title = getString(R.string.ra_cfg_bgm_manage),
                    summary = getString(R.string.ra_cfg_bgm_manage_summary, bgmCount, sfxCount),
                    enabled = enabled,
                    onClick = {
                        startActivity(
                            android.content.Intent(requireContext(), ReadAloudBgmManageActivity::class.java)
                        )
                    }
                ),
                action(
                    key = PreferKey.aiReadAloudBgmPrompt,
                    title = getString(R.string.ra_cfg_bgm_prompt),
                    summary = firstLineSummary(
                        value = AppConfig.aiReadAloudBgmPrompt,
                        useDefault = AppConfig.aiReadAloudUsingDefaultBgmPrompt
                    ),
                    enabled = audioEnabled,
                    onClick = ::showBgmPromptDialog
                ),
                action(
                    key = PreferKey.aiReadAloudSoundEffectPrompt,
                    title = getString(R.string.ra_cfg_sfx_prompt),
                    summary = firstLineSummary(
                        value = AppConfig.aiReadAloudSoundEffectPrompt,
                        useDefault = AppConfig.aiReadAloudUsingDefaultSoundEffectPrompt
                    ),
                    enabled = audioEnabled,
                    onClick = ::showSoundEffectPromptDialog
                ),
                slider(
                    key = PreferKey.aiReadAloudBgmVolume,
                    title = getString(R.string.ra_cfg_bgm_volume),
                    summary = getString(R.string.ra_cfg_volume_current, AppConfig.aiReadAloudBgmVolume),
                    value = AppConfig.aiReadAloudBgmVolume,
                    range = 0..100,
                    enabled = audioEnabled
                ),
                slider(
                    key = PreferKey.aiReadAloudSfxVolume,
                    title = getString(R.string.ra_cfg_sfx_volume),
                    summary = getString(R.string.ra_cfg_volume_current, AppConfig.aiReadAloudSfxVolume),
                    value = AppConfig.aiReadAloudSfxVolume,
                    range = 0..100,
                    enabled = audioEnabled
                ),
                switch(
                    key = PreferKey.readAloudSpeakerLoudnessEnabled,
                    title = getString(R.string.ra_cfg_loudness),
                    summary = if (AppConfig.readAloudSpeakerLoudnessEnabled) {
                        getString(R.string.ra_cfg_loudness_summary_on)
                    } else {
                        getString(R.string.ra_cfg_loudness_summary_off)
                    },
                    checked = AppConfig.readAloudSpeakerLoudnessEnabled,
                    onCheckedChange = { AppConfig.readAloudSpeakerLoudnessEnabled = it }
                ),
                action(
                    key = KEY_READ_ALOUD_LOUDNESS_RESET,
                    title = getString(R.string.ra_cfg_loudness_reset),
                    summary = if (loudnessCount > 0) {
                        getString(R.string.ra_cfg_loudness_learned, loudnessCount)
                    } else {
                        getString(R.string.ra_cfg_loudness_empty)
                    },
                    enabled = loudnessCount > 0
                ) {
                    ReadAloudSpeakerLoudnessManager.reset()
                    updateAiRolePreferences()
                    postReadAloudConfigChanged(EventBus.READ_ALOUD_CONFIG_SCOPE_AUDIO)
                    toastOnUi(R.string.toast_speaker_loudness_reset)
                }
            )
        }

        private fun engineItems(): List<SettingItemSpec> {
            return listOf(
                action(
                    key = PreferKey.ttsEngine,
                    title = getString(R.string.speak_engine),
                    summary = speakEngineSummary,
                    onClick = { showDialogFragment(SpeakEngineDialog()) }
                ),
                action(
                    key = KEY_READ_ALOUD_SPEAKER_MANAGE,
                    title = getString(R.string.ra_cfg_speaker_manage),
                    summary = SPEAKER_MANAGE_SUMMARY,
                    onClick = { startActivity<SpeakerGroupManageActivity>() }
                ),
                action(
                    key = KEY_SYS_TTS_CONFIG,
                    title = getString(R.string.sys_tts_config),
                    summary = getString(R.string.sys_tts_config_summary),
                    onClick = { IntentHelp.openTTSSetting() }
                ),
                action(
                    key = PreferKey.readAloudCacheMb,
                    title = getString(R.string.ra_cache_limit),
                    summary = cacheSummary,
                    onClick = ::showCacheLimitDialog
                ),
                action(
                    key = KEY_READ_ALOUD_CACHE_CLEAR,
                    title = getString(R.string.ra_cache_clear),
                    summary = getString(R.string.ra_cache_clear_summary),
                    onClick = ::confirmClearCache
                )
            )
        }

        private fun switch(
            key: String,
            title: CharSequence,
            summary: CharSequence? = null,
            defaultValue: Boolean,
            enabled: Boolean = true
        ): SettingSwitchSpec {
            return switch(
                key = key,
                title = title,
                summary = summary,
                checked = booleanSetting(key, defaultValue),
                enabled = enabled,
                onCheckedChange = { updateBooleanSetting(key, it) }
            )
        }

        private fun switch(
            key: String,
            title: CharSequence,
            summary: CharSequence? = null,
            checked: Boolean,
            enabled: Boolean = true,
            onCheckedChange: (Boolean) -> Unit
        ): SettingSwitchSpec {
            return SettingSwitchSpec(
                key = key,
                title = title,
                summary = summary,
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        }

        private fun slider(
            key: String,
            title: CharSequence,
            summary: CharSequence,
            value: Int,
            range: IntRange,
            enabled: Boolean = true
        ): SettingSliderSpec {
            return SettingSliderSpec(
                key = key,
                title = title,
                summary = summary,
                value = value.coerceIn(range.first, range.last),
                valueRange = range,
                enabled = enabled,
                onValueChange = { updateIntSetting(key, it) }
            )
        }

        private fun action(
            key: String,
            title: CharSequence,
            summary: CharSequence? = null,
            visible: Boolean = true,
            enabled: Boolean = true,
            onClick: () -> Unit
        ): SettingActionSpec {
            return SettingActionSpec(
                key = key,
                title = title,
                summary = summary,
                visible = visible,
                enabled = enabled,
                onClick = onClick
            )
        }

        private fun choice(
            key: String,
            title: CharSequence,
            entriesRes: Int,
            valuesRes: Int,
            selectedValue: String,
            enabled: Boolean = true
        ): SettingChoiceSpec {
            val options = choiceOptions(entriesRes, valuesRes)
            return SettingChoiceSpec(
                key = key,
                title = title,
                summary = choiceLabel(options, selectedValue),
                options = options,
                selectedValue = selectedValue,
                enabled = enabled,
                onSelected = { updateStringSetting(key, it) }
            )
        }

        private fun choiceOptions(
            entriesRes: Int,
            valuesRes: Int
        ): List<SettingChoiceOption> {
            val entries = resources.getStringArray(entriesRes)
            val values = resources.getStringArray(valuesRes)
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

        private fun firstLineSummary(
            value: String,
            useDefault: Boolean
        ): String {
            val prefix = if (useDefault) getString(R.string.ra_cfg_builtin_prefix) else ""
            return prefix + value.lineSequence()
                .firstOrNull()
                ?.take(40)
                ?.ifBlank { null }
                .orEmpty()
        }

        private fun showAiReadAloudModelRoutingDialog() {
            val options = listOf(
                getString(R.string.ra_cfg_model_option_role, modelLabel(AppConfig.aiReadAloudRoleModelConfig)),
                getString(
                    R.string.ra_cfg_model_option_backup,
                    optionalModelLabel(
                        AppConfig.aiReadAloudRoleBackupModelId,
                        getString(R.string.ra_cfg_none)
                    )
                ),
                getString(
                    R.string.ra_cfg_model_option_timeout,
                    AppConfig.aiReadAloudRoleFirstResponseTimeoutSeconds
                ),
                getString(
                    R.string.ra_cfg_model_option_audio,
                    optionalModelLabel(
                        AppConfig.aiReadAloudAudioModelId,
                        getString(R.string.ra_cfg_follow_role)
                    )
                ),
                getString(
                    R.string.ra_cfg_model_option_audio_backup,
                    optionalModelLabel(
                        AppConfig.aiReadAloudAudioBackupModelId,
                        getString(R.string.ra_cfg_follow_role_backup)
                    )
                )
            )
            showComposeChoiceListDialog(getString(R.string.ra_cfg_ai_model_timeout), options) { index ->
                when (index) {
                    0 -> showAiModelPicker(
                        title = getString(R.string.ra_cfg_role_model),
                        currentModelId = AppConfig.aiReadAloudRoleModelId,
                        noneLabel = null
                    ) {
                        AppConfig.aiReadAloudRoleModelId = it
                        updateAiRolePreferences()
                    }
                    1 -> showAiModelPicker(
                        title = getString(R.string.ra_cfg_role_model_backup),
                        currentModelId = AppConfig.aiReadAloudRoleBackupModelId,
                        noneLabel = getString(R.string.ra_cfg_role_model_backup_none)
                    ) {
                        AppConfig.aiReadAloudRoleBackupModelId = it
                        updateAiRolePreferences()
                    }
                    2 -> showAiRoleNumberDialog(
                        title = getString(R.string.ra_cfg_first_timeout),
                        value = AppConfig.aiReadAloudRoleFirstResponseTimeoutSeconds,
                        min = 5,
                        max = 90
                    ) {
                        AppConfig.aiReadAloudRoleFirstResponseTimeoutSeconds = it
                        updateAiRolePreferences()
                    }
                    3 -> showAiModelPicker(
                        title = getString(R.string.ra_cfg_audio_model),
                        currentModelId = AppConfig.aiReadAloudAudioModelId,
                        noneLabel = getString(R.string.ra_cfg_audio_model_follow)
                    ) {
                        AppConfig.aiReadAloudAudioModelId = it
                        updateAiRolePreferences()
                    }
                    4 -> showAiModelPicker(
                        title = getString(R.string.ra_cfg_audio_model_backup),
                        currentModelId = AppConfig.aiReadAloudAudioBackupModelId,
                        noneLabel = getString(R.string.ra_cfg_audio_model_backup_follow)
                    ) {
                        AppConfig.aiReadAloudAudioBackupModelId = it
                        updateAiRolePreferences()
                    }
                }
            }
        }

        private fun showAiModelPicker(
            title: String,
            currentModelId: String?,
            noneLabel: String?,
            onSelected: (String?) -> Unit
        ) {
            val models = AppConfig.aiModelConfigList
            if (models.isEmpty()) {
                toastOnUi(R.string.ai_no_models)
                return
            }
            val providerNameMap = AppConfig.aiProviderList.associateBy({ it.id }, { it.name })
            val offset = if (noneLabel == null) 0 else 1
            val labels = buildList {
                noneLabel?.let {
                    add(if (currentModelId.isNullOrBlank()) "$it ✓" else it)
                }
                models.forEach { model ->
                    val label = providerNameMap[model.providerId]?.takeIf { it.isNotBlank() }
                        ?.let { "${model.modelId} - $it" }
                        ?: model.modelId
                    add(if (model.id == currentModelId) "$label ✓" else label)
                }
            }
            val selectedIndex = if (currentModelId.isNullOrBlank() && noneLabel != null) {
                0
            } else {
                models.indexOfFirst { it.id == currentModelId }
                    .takeIf { it >= 0 }
                    ?.plus(offset)
                    ?: -1
            }
            showComposeChoiceListDialog(title, labels, selectedIndex = selectedIndex) { index ->
                if (index < offset) {
                    onSelected(null)
                } else {
                    onSelected(models[index - offset].id)
                }
                selectGroup(selectedGroup)
            }
        }

        private fun aiReadAloudModelRoutingSummary(): String {
            val role = modelLabel(AppConfig.aiReadAloudRoleModelConfig)
            val backup = optionalModelLabel(
                AppConfig.aiReadAloudRoleBackupModelId,
                getString(R.string.ra_cfg_no_backup)
            )
            val audio = optionalModelLabel(
                AppConfig.aiReadAloudAudioModelId,
                getString(R.string.ra_cfg_audio_follow)
            )
            return getString(
                R.string.ra_cfg_routing_summary,
                role,
                backup,
                AppConfig.aiReadAloudRoleFirstResponseTimeoutSeconds,
                audio
            )
        }

        private fun optionalModelLabel(modelId: String?, fallback: String): String {
            if (modelId.isNullOrBlank()) return fallback
            return modelLabel(AppConfig.aiModelConfigList.firstOrNull { it.id == modelId })
        }


        private fun modelLabel(model: com.shimmer.reader.ui.main.ai.AiModelConfig?): String {
            model ?: return getString(R.string.common_not_configured)
            val providerName = AppConfig.aiProviderList.firstOrNull { it.id == model.providerId }
                ?.name
                ?.takeIf { it.isNotBlank() }
            return providerName?.let { "${model.modelId} - $it" } ?: model.modelId
        }

        private fun formatSpeechRate(value: Int): String {
            return ((value.coerceIn(0, 45) + 5) / 10f).toString()
        }

        private fun showAiRoleNumberDialog(
            title: String,
            value: Int,
            min: Int,
            max: Int,
            onValue: (Int) -> Unit
        ) {
            val displayTitle = when (max) {
                10 -> getString(R.string.ra_cfg_thread_count)
                40 -> getString(R.string.ra_cfg_batch_paragraphs)
                else -> title
            }
            showComposeNumberPickerDialog(
                title = displayTitle,
                value = value,
                minValue = min,
                maxValue = max,
                onValue = onValue
            )
        }

        private fun showLongTextInputDialog(
            title: String,
            hint: String,
            initialValue: String,
            maxLength: Int? = null,
            maxLengthMessage: String = "",
            minLines: Int,
            onValue: (String) -> Unit,
            neutralText: String? = null,
            onNeutral: (() -> Unit)? = null,
            validateContent: ((String) -> Boolean)? = null
        ) {
            showComposeTextInputDialog(
                title = title,
                hint = hint,
                initialValue = initialValue,
                neutralText = neutralText,
                minLines = minLines,
                maxLines = maxOf(minLines, 10),
                validateInput = { value ->
                    when {
                        maxLength != null && value.length > maxLength -> {
                            toastOnUi(maxLengthMessage)
                            false
                        }
                        validateContent?.invoke(value) == false -> false
                        else -> true
                    }
                },
                onPositive = onValue,
                onNeutral = onNeutral
            )
        }

        private fun showMultiRolePromptDialog() {
            showLongTextInputDialog(
                title = getString(R.string.ra_cfg_role_prompt),
                hint = getString(R.string.ra_cfg_role_prompt_hint),
                initialValue = AppConfig.aiReadAloudRolePrompt,
                maxLength = 4000,
                maxLengthMessage = getString(R.string.ra_cfg_prompt_max_4000),
                minLines = 6,
                onValue = {
                    AppConfig.aiReadAloudRolePrompt = it
                    updateAiRolePreferences()
                },
                neutralText = getString(R.string.ra_cfg_restore_default),
                onNeutral = {
                    AppConfig.aiReadAloudRolePrompt = ""
                    updateAiRolePreferences()
                }
            )
        }

        private fun showAutoCreateCharacterPromptDialog() {
            showLongTextInputDialog(
                title = getString(R.string.ra_cfg_auto_card_prompt),
                hint = getString(R.string.ra_cfg_card_prompt_hint),
                initialValue = AppConfig.aiReadAloudAutoCreateCharacterPrompt,
                maxLength = 4000,
                maxLengthMessage = getString(R.string.ra_cfg_prompt_max_4000),
                minLines = 6,
                onValue = {
                    AppConfig.aiReadAloudAutoCreateCharacterPrompt = it
                    updateAiRolePreferences()
                },
                neutralText = getString(R.string.ra_cfg_restore_default),
                onNeutral = {
                    AppConfig.aiReadAloudAutoCreateCharacterPrompt = ""
                    updateAiRolePreferences()
                }
            )
        }

        private fun showBgmPromptDialog() {
            showLongTextInputDialog(
                title = getString(R.string.ra_cfg_bgm_prompt),
                hint = getString(R.string.ra_cfg_bgm_prompt_hint),
                initialValue = AppConfig.aiReadAloudBgmPrompt,
                maxLength = 4000,
                maxLengthMessage = getString(R.string.ra_cfg_prompt_max_4000),
                minLines = 6,
                onValue = {
                    AppConfig.aiReadAloudBgmPrompt = it
                    updateAiRolePreferences()
                },
                neutralText = getString(R.string.ra_cfg_restore_default),
                onNeutral = {
                    AppConfig.aiReadAloudBgmPrompt = ""
                    updateAiRolePreferences()
                }
            )
        }

        private fun showSoundEffectPromptDialog() {
            showLongTextInputDialog(
                title = getString(R.string.ra_cfg_sfx_prompt),
                hint = getString(R.string.ra_cfg_sfx_prompt_hint),
                initialValue = AppConfig.aiReadAloudSoundEffectPrompt,
                maxLength = 4000,
                maxLengthMessage = getString(R.string.ra_cfg_prompt_max_4000),
                minLines = 6,
                onValue = {
                    AppConfig.aiReadAloudSoundEffectPrompt = it
                    updateAiRolePreferences()
                },
                neutralText = getString(R.string.ra_cfg_restore_default),
                onNeutral = {
                    AppConfig.aiReadAloudSoundEffectPrompt = ""
                    updateAiRolePreferences()
                }
            )
        }

        private fun showMultiRolePreprocessRulesDialog() {
            val config = ReadAloudPreprocessRuleConfig.current()
            val items = listOf(
                getString(R.string.ra_cfg_item_quote_rules, config.quotePairs.size),
                getString(R.string.ra_cfg_item_thought_cues, config.thoughtCuePatterns.size),
                getString(
                    R.string.ra_cfg_item_thresholds,
                    config.dialogueMinLength,
                    config.emphasisMaxLength
                ),
                getString(R.string.ra_cfg_item_sfx_cues_count, config.soundEffectCuePatterns.size),
                getString(R.string.ra_cfg_try_run),
                getString(R.string.ra_cfg_restore_default)
            )
            showComposeChoiceListDialog(getString(R.string.ra_cfg_preprocess_rules), items) { index ->
                when (index) {
                    0 -> showPreprocessQuoteDialog(config)
                    1 -> showRuleListEditor(
                        title = getString(R.string.ra_cfg_thought_cues),
                        values = config.thoughtCuePatterns,
                        hint = getString(R.string.ra_cfg_thought_cues_hint),
                        onSave = { savePreprocessConfig(config.copy(thoughtCuePatterns = it)) }
                    )
                    2 -> showPreprocessThresholdDialog(config)
                    3 -> showSoundEffectRuleDialog(config)
                    4 -> showPreprocessTrialDialog()
                    5 -> {
                        AppConfig.aiReadAloudRolePreprocessRules = ""
                        updateAiRolePreferences()
                        toastOnUi(R.string.toast_builtin_preprocess_restored)
                    }
                }
            }
        }

        private fun showPreprocessQuoteDialog(config: ReadAloudPreprocessRuleConfig) {
            showLongTextInputDialog(
                title = getString(R.string.ra_cfg_quote_rules),
                hint = getString(R.string.ra_cfg_quote_rules_hint),
                initialValue = config.quotePairs.joinToString("\n") { "${it.open} ${it.close}" },
                minLines = 6,
                onValue = { value ->
                    savePreprocessConfig(config.copy(quotePairs = parseQuotePairs(value)))
                },
                neutralText = getString(R.string.ra_cfg_sentence_end_marks),
                onNeutral = { showSentencePunctuationDialog(config) },
                validateContent = { value ->
                    if (parseQuotePairs(value).isEmpty()) {
                        toastOnUi(R.string.toast_keep_one_quote)
                        false
                    } else {
                        true
                    }
                }
            )
        }

        private fun showSentencePunctuationDialog(config: ReadAloudPreprocessRuleConfig) {
            showComposeTextInputDialog(
                title = getString(R.string.ra_cfg_sentence_end_marks),
                hint = getString(R.string.ra_cfg_sentence_end_hint),
                initialValue = config.sentencePunctuation,
                minLines = 1,
                maxLines = 1,
                validateInput = { value ->
                    if (value.trim().isBlank()) {
                        toastOnUi(R.string.toast_end_mark_empty)
                        false
                    } else {
                        true
                    }
                },
                onPositive = { value ->
                    savePreprocessConfig(config.copy(sentencePunctuation = value.trim()))
                }
            )
        }

        private fun showPreprocessThresholdDialog(config: ReadAloudPreprocessRuleConfig) {
            val items = listOf(
                getString(R.string.ra_cfg_item_dialogue_min, config.dialogueMinLength),
                getString(R.string.ra_cfg_item_emphasis_max, config.emphasisMaxLength),
                getString(R.string.ra_cfg_item_colon_cue_max, config.colonCueMaxLength),
                getString(
                    R.string.ra_cfg_item_cross_paragraph,
                    getString(if (config.mergeCrossParagraphQuote) R.string.ra_cfg_on else R.string.ra_cfg_off)
                ),
                getString(R.string.ra_cfg_item_sfx_context_chars, config.soundEffectContextChars)
            )
            showComposeChoiceListDialog(getString(R.string.ra_cfg_thresholds), items) { index ->
                when (index) {
                    0 -> showPreprocessNumberDialog(getString(R.string.ra_cfg_dialogue_min), config.dialogueMinLength, 1, 80) {
                        savePreprocessConfig(config.copy(dialogueMinLength = it))
                    }
                    1 -> showPreprocessNumberDialog(getString(R.string.ra_cfg_emphasis_max), config.emphasisMaxLength, 1, 80) {
                        savePreprocessConfig(config.copy(emphasisMaxLength = it))
                    }
                    2 -> showPreprocessNumberDialog(getString(R.string.ra_cfg_colon_cue_max), config.colonCueMaxLength, 1, 80) {
                        savePreprocessConfig(config.copy(colonCueMaxLength = it))
                    }
                    3 -> savePreprocessConfig(config.copy(mergeCrossParagraphQuote = !config.mergeCrossParagraphQuote))
                    4 -> showPreprocessNumberDialog(getString(R.string.ra_cfg_sfx_context_chars), config.soundEffectContextChars, 8, 120) {
                        savePreprocessConfig(config.copy(soundEffectContextChars = it))
                    }
                }
            }
        }

        private fun showSoundEffectRuleDialog(config: ReadAloudPreprocessRuleConfig) {
            val items = listOf(
                getString(R.string.ra_cfg_item_sfx_rule_cues, config.soundEffectCuePatterns.size),
                getString(R.string.ra_cfg_item_sfx_rule_excludes, config.soundEffectExcludePatterns.size),
                getString(R.string.ra_cfg_item_sfx_rule_context, config.soundEffectContextChars)
            )
            showComposeChoiceListDialog(getString(R.string.ra_cfg_sfx_rules), items) { index ->
                when (index) {
                    0 -> showRuleListEditor(
                        title = getString(R.string.ra_cfg_sfx_cues),
                        values = config.soundEffectCuePatterns,
                        hint = getString(R.string.ra_cfg_sfx_cues_hint),
                        onSave = { savePreprocessConfig(config.copy(soundEffectCuePatterns = it)) }
                    )
                    1 -> showRuleListEditor(
                        title = getString(R.string.ra_cfg_sfx_excludes),
                        values = config.soundEffectExcludePatterns,
                        hint = getString(R.string.ra_cfg_sfx_excludes_hint),
                        onSave = { savePreprocessConfig(config.copy(soundEffectExcludePatterns = it)) }
                    )
                    2 -> showPreprocessNumberDialog(getString(R.string.ra_cfg_sfx_context_chars), config.soundEffectContextChars, 8, 120) {
                        savePreprocessConfig(config.copy(soundEffectContextChars = it))
                    }
                }
            }
        }

        private fun showRuleListEditor(
            title: String,
            values: List<String>,
            hint: String,
            onSave: (List<String>) -> Unit
        ) {
            showLongTextInputDialog(
                title = title,
                hint = hint,
                initialValue = values.joinToString("\n"),
                minLines = 8,
                onValue = { value -> onSave(parseRuleItems(value)) },
                validateContent = { value ->
                    if (parseRuleItems(value).isEmpty()) {
                        toastOnUi(R.string.toast_keep_one_rule)
                        false
                    } else {
                        true
                    }
                }
            )
        }

        private fun showPreprocessNumberDialog(
            title: String,
            value: Int,
            min: Int,
            max: Int,
            onValue: (Int) -> Unit
        ) {
            showComposeNumberPickerDialog(
                title = title,
                value = value,
                minValue = min,
                maxValue = max,
                onValue = onValue
            )
        }

        private fun showPreprocessTrialDialog() {
            val sample = """
                江艳凑在杨间身边小心翼翼地问道：
                “那栋房子真的有鬼啊？”
                门轴忽然吱呀一声响了起来。
                他心道：“事情不太对。”
            """.trimIndent()
            showComposeTextInputDialog(
                title = getString(R.string.ra_cfg_try_run_title),
                hint = getString(R.string.ra_cfg_try_run_hint),
                initialValue = sample,
                minLines = 10,
                maxLines = 14,
                validateInput = { value ->
                    if (value.isBlank()) {
                        toastOnUi(R.string.toast_input_content_first)
                        false
                    } else {
                        true
                    }
                },
                onPositive = { showPreprocessTrialResult(it) }
            )
        }

        private fun showPreprocessTrialResult(text: String) {
            val paragraphs = text.lines().map { it.trim() }.filter { it.isNotBlank() }
            val roleResult = ReadAloudRolePreprocessor.process(paragraphs)
            val sfxResult = ReadAloudSoundEffectPreprocessor.process(paragraphs)
            val message = buildString {
                appendLine(getString(R.string.ra_cfg_unit_count, roleResult.units.size))
                roleResult.units.take(40).forEachIndexed { index, unit ->
                    append(index + 1)
                    append(". ")
                    append(unit.kind)
                    append(" · ")
                    append(if (unit.needsAi) getString(R.string.ra_cfg_need_ai) else unit.characterName.ifBlank { unit.roleType })
                    append(" · P")
                    append(unit.firstParagraphIndex + 1)
                    append(" · ")
                    appendLine(unit.text.replace('\n', ' ').take(80))
                }
                if (roleResult.units.size > 40) appendLine("……")
                appendLine()
                appendLine(getString(R.string.ra_cfg_sfx_candidates, sfxResult.candidates.size))
                sfxResult.candidates.take(30).forEachIndexed { index, item ->
                    append(index + 1)
                    append(". ")
                    append(item.cue)
                    append(" · P")
                    append(item.paragraphIndex + 1)
                    append(" · ")
                    appendLine(item.context.take(80))
                }
            }.take(6000)
            showComposeConfirmDialog(
                title = getString(R.string.ra_cfg_try_run_result),
                message = message,
                showNegative = false,
                messageInContent = true,
                onPositive = {}
            )
        }

        private fun savePreprocessConfig(config: ReadAloudPreprocessRuleConfig) {
            val json = config.toJsonString()
            if (json.length > 8000) {
                toastOnUi(R.string.toast_preprocess_rule_limit)
                return
            }
            AppConfig.aiReadAloudRolePreprocessRules = json
            updateAiRolePreferences()
            toastOnUi(R.string.toast_preprocess_rule_saved)
        }

        private fun parseQuotePairs(value: String): List<ReadAloudQuotePair> {
            return value.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split(Regex("\\s+")).filter { it.isNotBlank() }
                    when {
                        parts.size >= 2 -> ReadAloudQuotePair(parts[0].take(1), parts[1].take(1))
                        line.length >= 2 -> ReadAloudQuotePair(line.take(1), line.takeLast(1))
                        else -> null
                    }
                }
                .distinct()
                .toList()
        }

        private fun parseRuleItems(value: String): List<String> {
            return value.lineSequence()
                .flatMap { it.split(',', '，', '、').asSequence() }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
        }


    }
}
