package com.shimmer.reader.ui.config

import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuProvider
import com.shimmer.reader.R
import com.shimmer.reader.constant.EventBus
import com.shimmer.reader.constant.PreferKey
import com.shimmer.reader.help.LauncherIconHelp
import com.shimmer.reader.help.config.AppConfig
import com.shimmer.reader.help.config.ThemeConfig
import com.shimmer.reader.ui.config.compose.ComposeSettingFragment
import com.shimmer.reader.ui.config.compose.SettingActionSpec
import com.shimmer.reader.ui.config.compose.SettingChoiceOption
import com.shimmer.reader.ui.config.compose.SettingChoiceSpec
import com.shimmer.reader.ui.config.compose.SettingPageSpec
import com.shimmer.reader.ui.config.compose.SettingSectionSpec
import com.shimmer.reader.ui.config.compose.SettingSwitchSpec
import com.shimmer.reader.utils.applyTint
import com.shimmer.reader.utils.postEvent
import com.shimmer.reader.utils.startActivity

class ThemeConfigFragment : ComposeSettingFragment(), MenuProvider {

    override val titleRes: Int = R.string.theme_setting

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.addMenuProvider(this, viewLifecycleOwner)
    }

    override fun buildPageSpec(): SettingPageSpec {
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    items = listOf(
                        SettingChoiceSpec(
                            key = PreferKey.launcherIcon,
                            title = str(R.string.change_icon),
                            summary = str(R.string.change_icon_summary),
                            options = iconOptions(),
                            selectedValue = stringSetting(PreferKey.launcherIcon, DEFAULT_LAUNCHER_ICON),
                            visible = Build.VERSION.SDK_INT >= 26,
                            onSelected = { updateStringSetting(PreferKey.launcherIcon, it) }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.mainTransparentStatusBar,
                            title = str(R.string.main_immersion_status_bar),
                            summary = str(R.string.main_status_bar_immersion),
                            checked = booleanSetting(PreferKey.mainTransparentStatusBar, false),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.mainTransparentStatusBar, it)
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.immersiveManageBar,
                            title = str(R.string.manage_bar_immersion),
                            summary = str(R.string.manage_bar_immersion_summary),
                            checked = booleanSetting(PreferKey.immersiveManageBar, true),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.immersiveManageBar, it)
                            }
                        ),
                        SettingActionSpec(
                            key = KEY_THEME_MANAGE,
                            title = str(R.string.theme_list),
                            summary = str(R.string.theme_list_summary),
                            onClick = { startActivity<ThemeManageActivity>() }
                        ),
                        SettingActionSpec(
                            key = KEY_NAVIGATION_BAR_MANAGE,
                            title = str(R.string.navigation_bar_manage),
                            summary = str(R.string.navigation_bar_manage_summary),
                            onClick = { startActivity<NavigationBarManageActivity>() }
                        ),
                        SettingActionSpec(
                            key = KEY_DISCOVERY_SUBSCRIPTION_SETTINGS,
                            title = str(R.string.discovery_subscription_settings_title),
                            summary = str(R.string.discovery_subscription_settings_summary),
                            onClick = {
                                startActivity<ConfigActivity> {
                                    putExtra("configTag", ConfigTag.DISCOVERY_SUBSCRIPTION_CONFIG)
                                }
                            }
                        ),
                        SettingActionSpec(
                            key = KEY_TOP_BAR_MANAGE,
                            title = str(R.string.top_bar_manage),
                            summary = str(R.string.top_bar_manage_summary),
                            onClick = { startActivity<TopBarManageActivity>() }
                        ),
                        SettingActionSpec(
                            key = KEY_BOOK_INFO_MANAGE,
                            title = str(R.string.book_info_manage),
                            summary = str(R.string.book_info_manage_summary),
                            onClick = { startActivity<BookInfoManageActivity>() }
                        ),
                        SettingActionSpec(
                            key = KEY_BUBBLE_MANAGE,
                            title = str(R.string.bubble_manage),
                            summary = str(R.string.bubble_manage_summary),
                            onClick = { startActivity<BubbleManageActivity>() }
                        ),
                        SettingActionSpec(
                            key = KEY_SHARE_NOTE_TEMPLATE_MANAGE,
                            title = str(R.string.theme_share_note_template),
                            summary = str(R.string.theme_share_note_template_summary),
                            searchKeys = strings.getStringArray(
                                R.array.theme_share_note_template_search_keys
                            ).toList(),
                            onClick = { startActivity<ShareNoteTemplateManageActivity>() }
                        ),
                        SettingActionSpec(
                            key = ConfigTag.COVER_CONFIG,
                            title = str(R.string.cover_config),
                            summary = str(R.string.cover_config_summary),
                            onClick = {
                                startActivity<ConfigActivity> {
                                    putExtra("configTag", ConfigTag.COVER_CONFIG)
                                }
                            }
                        )
                    )
                )
            )
        )
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.theme_config, menu)
        updateThemeModeMenu(menu)
        menu.applyTint(requireContext())
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.menu_theme_mode -> {
                AppConfig.isNightTheme = !AppConfig.isNightTheme
                menuItem.setIcon(themeModeIconRes())
                ThemeConfig.applyDayNight(requireContext())
                return true
            }
        }
        return false
    }

    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            PreferKey.launcherIcon -> LauncherIconHelp.changeIcon(
                stringSetting(PreferKey.launcherIcon, DEFAULT_LAUNCHER_ICON)
            )

            PreferKey.mainTransparentStatusBar,
            PreferKey.transparentStatusBar,
            PreferKey.immersiveManageBar,
            PreferKey.immNavigationBar -> recreateActivities()
        }
    }

    private fun updateThemeModeMenu(menu: Menu) {
        menu.findItem(R.id.menu_theme_mode)?.setIcon(themeModeIconRes())
    }

    private fun themeModeIconRes(): Int {
        return if (AppConfig.isNightTheme) {
            R.drawable.ic_daytime
        } else {
            R.drawable.ic_brightness
        }
    }

    private fun iconOptions(): List<SettingChoiceOption> {
        val entries = strings.getStringArray(R.array.icon_names)
        val values = strings.getStringArray(R.array.icons)
        return values.mapIndexed { index, value ->
            SettingChoiceOption(
                value = value,
                label = entries.getOrElse(index) { value },
                iconName = value
            )
        }
    }

    private fun recreateActivities() {
        postEvent(EventBus.RECREATE, "")
    }

    companion object {
        private const val DEFAULT_LAUNCHER_ICON = "ic_launcher"
        private const val KEY_THEME_MANAGE = "theme_manage"
        private const val KEY_NAVIGATION_BAR_MANAGE = "navigation_bar_manage"
        private const val KEY_DISCOVERY_SUBSCRIPTION_SETTINGS = "discoverySubscriptionSettings"
        private const val KEY_TOP_BAR_MANAGE = "top_bar_manage"
        private const val KEY_BOOK_INFO_MANAGE = "book_info_manage"
        private const val KEY_BUBBLE_MANAGE = "bubble_manage"
        private const val KEY_SHARE_NOTE_TEMPLATE_MANAGE = "share_note_template_manage"
    }
}
