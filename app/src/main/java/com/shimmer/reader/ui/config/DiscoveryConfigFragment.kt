package com.shimmer.reader.ui.config

import com.shimmer.reader.R
import com.shimmer.reader.constant.EventBus
import com.shimmer.reader.constant.PreferKey
import com.shimmer.reader.help.config.AppConfig
import com.shimmer.reader.ui.config.compose.ComposeSettingFragment
import com.shimmer.reader.ui.config.compose.SettingActionSpec
import com.shimmer.reader.ui.config.compose.SettingChoiceOption
import com.shimmer.reader.ui.config.compose.SettingChoiceSpec
import com.shimmer.reader.ui.config.compose.SettingPageSpec
import com.shimmer.reader.ui.config.compose.SettingSectionSpec
import com.shimmer.reader.ui.widget.compose.showComposeChoiceListDialog
import com.shimmer.reader.utils.postEvent

class DiscoveryConfigFragment : ComposeSettingFragment() {

    override val titleRes: Int = R.string.discovery_settings_title

    override fun buildPageSpec(): SettingPageSpec {
        val discoveryMode = AppConfig.discoveryPageMode
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    items = listOf(
                        SettingChoiceSpec(
                            key = KEY_DISCOVERY_MODE,
                            title = str(R.string.modern_discovery_page),
                            options = pageModeOptions(
                                entriesRes = R.array.discovery_page_mode_entries,
                                valuesRes = R.array.discovery_page_mode_values
                            ),
                            selectedValue = discoveryMode,
                            summary = pageModeLabel(
                                entriesRes = R.array.discovery_page_mode_entries,
                                valuesRes = R.array.discovery_page_mode_values,
                                selectedValue = discoveryMode
                            ),
                            onSelected = {
                                AppConfig.discoveryPageMode = it
                            },
                            searchKeys = listOf(
                                PreferKey.discoveryPageMode,
                                PreferKey.modernDiscoveryPage,
                                KEY_SEARCH_JUMP_DISCOVERY_MODE
                            )
                        ),
                        SettingActionSpec(
                            key = PreferKey.discoveryPageLayout,
                            title = str(R.string.discovery_page_layout),
                            summary = discoveryLayoutSummary(),
                            visible = discoveryMode == AppConfig.DISCOVERY_PAGE_MODE_MODERN,
                            onClick = ::showDiscoveryLayoutDialog
                        )
                    )
                )
            )
        )
    }

    override fun normalizeTargetKey(rawKey: String): String {
        return when (rawKey) {
            PreferKey.discoveryPageMode,
            PreferKey.modernDiscoveryPage,
            KEY_SEARCH_JUMP_DISCOVERY_MODE -> KEY_DISCOVERY_MODE
            else -> rawKey
        }
    }

    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            PreferKey.discoveryPageMode,
            PreferKey.modernDiscoveryPage,
            PreferKey.discoveryPageLayout -> postEvent(EventBus.NOTIFY_MAIN, false)
        }
    }

    private fun showDiscoveryLayoutDialog() {
        showComposeChoiceListDialog(
            title = str(R.string.discovery_page_layout),
            labels = DISCOVERY_LAYOUT_VALUES.map(::discoveryLayoutLabel),
            selectedIndex = DISCOVERY_LAYOUT_VALUES.indexOf(AppConfig.discoveryPageLayout),
            negativeText = str(R.string.cancel),
            onSelected = { index ->
                val value = DISCOVERY_LAYOUT_VALUES.getOrNull(index) ?: return@showComposeChoiceListDialog
                AppConfig.discoveryPageLayout = value
                refreshSettings()
            }
        )
    }

    private fun discoveryLayoutSummary(): String {
        return str(
            R.string.discovery_page_layout_summary,
            discoveryLayoutLabel(AppConfig.discoveryPageLayout)
        )
    }

    private fun discoveryLayoutLabel(value: Int): String {
        return when (value) {
            2 -> str(R.string.discovery_page_layout_waterfall)
            3 -> str(R.string.discovery_page_layout_grid)
            else -> str(R.string.discovery_page_layout_list)
        }
    }

    private fun pageModeOptions(
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
        private const val KEY_DISCOVERY_MODE = "modernDiscoveryMode"
        private const val KEY_SEARCH_JUMP_DISCOVERY_MODE = "search_jump_modernDiscoveryMode"
        private val DISCOVERY_LAYOUT_VALUES = listOf(1, 2, 3)
    }
}
