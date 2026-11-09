package com.shimmer.reader.ui.config

import com.shimmer.reader.R
import com.shimmer.reader.constant.EventBus
import com.shimmer.reader.constant.PreferKey
import com.shimmer.reader.ui.config.compose.ComposeSettingFragment
import com.shimmer.reader.ui.config.compose.SettingChoiceOption
import com.shimmer.reader.ui.config.compose.SettingChoiceSpec
import com.shimmer.reader.ui.config.compose.SettingPageSpec
import com.shimmer.reader.ui.config.compose.SettingSectionSpec
import com.shimmer.reader.utils.postEvent

class SubscriptionConfigFragment : ComposeSettingFragment() {

    override val titleRes: Int = R.string.subscription_settings_title

    override fun buildPageSpec(): SettingPageSpec {
        val useModern = booleanSetting(PreferKey.modernRssPage, true)
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    items = listOf(
                        SettingChoiceSpec(
                            key = KEY_RSS_MODE,
                            title = getString(R.string.modern_rss_page),
                            options = pageModeOptions(
                                entriesRes = R.array.rss_page_mode_entries,
                                valuesRes = R.array.page_mode_values
                            ),
                            selectedValue = if (useModern) PAGE_MODE_MODERN else PAGE_MODE_LEGACY,
                            summary = pageModeLabel(
                                entriesRes = R.array.rss_page_mode_entries,
                                valuesRes = R.array.page_mode_values,
                                selectedValue = if (useModern) PAGE_MODE_MODERN else PAGE_MODE_LEGACY
                            ),
                            onSelected = {
                                updateBooleanSetting(
                                    PreferKey.modernRssPage,
                                    it == PAGE_MODE_MODERN
                                )
                            },
                            searchKeys = listOf(
                                PreferKey.modernRssPage,
                                KEY_SEARCH_JUMP_MODERN_RSS_PAGE,
                                KEY_SEARCH_JUMP_RSS_MODE
                            )
                        )
                    )
                )
            )
        )
    }

    override fun normalizeTargetKey(rawKey: String): String {
        return when (rawKey) {
            PreferKey.modernRssPage,
            KEY_SEARCH_JUMP_MODERN_RSS_PAGE,
            KEY_SEARCH_JUMP_RSS_MODE -> KEY_RSS_MODE
            else -> rawKey
        }
    }

    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            PreferKey.modernRssPage -> postEvent(EventBus.NOTIFY_MAIN, false)
        }
    }

    private fun pageModeOptions(
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

    private fun pageModeLabel(
        entriesRes: Int,
        valuesRes: Int,
        selectedValue: String
    ): String {
        return pageModeOptions(entriesRes, valuesRes)
            .firstOrNull { it.value == selectedValue }
            ?.label
            ?.toString()
            .orEmpty()
    }

    companion object {
        private const val PAGE_MODE_MODERN = "modern"
        private const val PAGE_MODE_LEGACY = "legacy"
        private const val KEY_RSS_MODE = "modernRssMode"
        private const val KEY_SEARCH_JUMP_MODERN_RSS_PAGE = "search_jump_modernRssPage"
        private const val KEY_SEARCH_JUMP_RSS_MODE = "search_jump_modernRssMode"
    }
}
