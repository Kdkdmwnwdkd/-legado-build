package com.shimmer.reader.ui.main.my

import android.content.SharedPreferences
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.shimmer.reader.R
import com.shimmer.reader.base.BaseFragment
import com.shimmer.reader.constant.EventBus
import com.shimmer.reader.constant.PreferKey
import com.shimmer.reader.databinding.FragmentMyConfigBinding
import com.shimmer.reader.help.config.ThemeConfig
import com.shimmer.reader.lib.theme.TopBarSearchStyle
import com.shimmer.reader.lib.theme.applyUiBodyTypefaceDeep
import com.shimmer.reader.lib.theme.uiTypeface
import com.shimmer.reader.model.AutoTask
import com.shimmer.reader.service.WebService
import com.shimmer.reader.ui.about.AboutActivity
import com.shimmer.reader.ui.about.ReadRecordActivity
import com.shimmer.reader.ui.book.bookmark.AllBookmarkActivity
import com.shimmer.reader.ui.book.cache.CacheManageActivity
import com.shimmer.reader.ui.book.source.manage.BookSourceActivity
import com.shimmer.reader.ui.book.toc.rule.TxtTocRuleActivity
import com.shimmer.reader.ui.config.ConfigActivity
import com.shimmer.reader.ui.config.ConfigTag
import com.shimmer.reader.ui.config.compose.SettingSearchIndex
import com.shimmer.reader.ui.config.AppearanceKitActivity
import com.shimmer.reader.ui.config.RelaySettingsActivity
import com.shimmer.reader.ui.dict.rule.DictRuleActivity
import com.shimmer.reader.ui.file.FileManageActivity
import com.shimmer.reader.ui.main.MainFragmentInterface
import com.shimmer.reader.ui.autoTask.AutoTaskActivity
import com.shimmer.reader.ui.replace.ReplaceRuleActivity
import com.shimmer.reader.ui.rss.source.manage.RssSourceActivity
import com.shimmer.reader.ui.widget.compose.ComposeActionListDialog
import com.shimmer.reader.utils.defaultSharedPreferences
import com.shimmer.reader.utils.getPrefBoolean
import com.shimmer.reader.utils.getPrefString
import com.shimmer.reader.utils.observeEventSticky
import com.shimmer.reader.utils.openUrl
import com.shimmer.reader.utils.putPrefBoolean
import com.shimmer.reader.utils.putPrefString
import com.shimmer.reader.utils.sendToClip
import com.shimmer.reader.utils.showDialogFragment
import com.shimmer.reader.utils.showHelp
import com.shimmer.reader.utils.startActivity
import com.shimmer.reader.utils.viewbindingdelegate.viewBinding
import com.shimmer.reader.utils.LogUtils

class MyFragment() : BaseFragment(R.layout.fragment_my_config),
    MainFragmentInterface,
    SharedPreferences.OnSharedPreferenceChangeListener {

    constructor(position: Int) : this() {
        arguments = Bundle().apply {
            putInt("position", position)
        }
    }

    override val position: Int? get() = arguments?.getInt("position")

    private val binding by viewBinding(FragmentMyConfigBinding::bind)
    private val settingsSearchView by lazy(LazyThreadSafetyMode.NONE) {
        binding.root.findViewById<SearchView>(R.id.search_view)
    }
    private val searchQueryState = mutableStateOf("")
    private val themeModeState = mutableStateOf("0")
    private val webServiceState = mutableStateOf(
        MyWebServiceUiState(checked = false, summary = "")
    )
    private val autoTaskServiceState = mutableStateOf(
        MyWebServiceUiState(checked = false, summary = "")
    )
    private val sections by lazy(LazyThreadSafetyMode.NONE) { buildSections() }
    private val themeOptions by lazy(LazyThreadSafetyMode.NONE) { buildThemeOptions() }
    private val subSearchItems by lazy(LazyThreadSafetyMode.NONE) { buildSubSearchItems() }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        requireContext().putPrefBoolean(PreferKey.webService, WebService.isRun)
        initSearchView()
        applySearchBarStyle()
        installComposeContent()
        updateSettingsState()
    }

    override fun observeLiveBus() {
        observeEventSticky<String>(EventBus.WEB_SERVICE) {
            updateWebServiceState()
        }
        observeEventSticky<String>(EventBus.AUTO_TASK_SERVICE) {
            updateAutoTaskServiceState()
        }
    }

    override fun onResume() {
        super.onResume()
        requireContext().defaultSharedPreferences.registerOnSharedPreferenceChangeListener(this)
        updateSettingsState()
    }

    override fun onPause() {
        requireContext().defaultSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.main_my, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_help -> showHelp("appHelp")
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.webService -> {
                if (requireContext().getPrefBoolean(PreferKey.webService)) {
                    WebService.start(requireContext())
                } else {
                    WebService.stop(requireContext())
                }
                updateWebServiceState()
            }

            PreferKey.themeMode -> {
                themeModeState.value = requireContext().getPrefString(PreferKey.themeMode, "0") ?: "0"
            }

            PreferKey.autoTaskService -> updateAutoTaskServiceState()

            "recordLog" -> LogUtils.upLevel()
        }
    }

    private fun installComposeContent() {
        binding.preFragment.removeAllViews()
        val composeView = ComposeView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MySettingsScreen(
                    sections = sections,
                    subSearchItems = subSearchItems,
                    searchQuery = searchQueryState.value,
                    themeModeLabel = currentThemeModeLabel(),
                    webServiceState = webServiceState.value,
                    autoTaskServiceState = autoTaskServiceState.value,
                    onThemeModeClick = ::showThemeModeActions,
                    onWebServiceCheckedChange = ::setWebServiceEnabled,
                    onWebServiceClick = ::handleWebServiceClick,
                    onAutoTaskServiceCheckedChange = ::setAutoTaskServiceEnabled,
                    onAutoTaskServiceClick = ::handleAutoTaskServiceClick,
                    onRowClick = ::handleRowClick
                )
            }
        }
        binding.preFragment.addView(composeView)
    }

    private fun initSearchView() {
        settingsSearchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                applySearchQuery(query)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                applySearchQuery(newText)
                return true
            }
        })
        settingsSearchView.setOnCloseListener {
            applySearchQuery("")
            false
        }
    }

    private fun applySearchQuery(query: String?) {
        searchQueryState.value = query?.trim().orEmpty()
    }

    private fun applySearchBarStyle() {
        settingsSearchView.applyUiBodyTypefaceDeep(requireContext().uiTypeface())
        TopBarSearchStyle.apply(settingsSearchView)
    }

    private fun updateSettingsState() {
        themeModeState.value = requireContext().getPrefString(PreferKey.themeMode, "0") ?: "0"
        updateWebServiceState()
        updateAutoTaskServiceState()
    }

    private fun updateWebServiceState() {
        webServiceState.value = MyWebServiceUiState(
            checked = WebService.isRun,
            summary = if (WebService.isRun) {
                WebService.hostAddress
            } else {
                getString(R.string.web_service_desc)
            }
        )
    }

    private fun updateAutoTaskServiceState() {
        val enabled = requireContext().getPrefBoolean(PreferKey.autoTaskService)
        autoTaskServiceState.value = MyWebServiceUiState(
            checked = enabled,
            summary = getString(R.string.auto_task_service_desc)
        )
    }

    private fun setThemeMode(value: String) {
        requireContext().putPrefString(PreferKey.themeMode, value)
        themeModeState.value = value
        view?.post {
            ThemeConfig.applyDayNight(requireContext())
        }
    }

    private fun currentThemeModeLabel(): String {
        return themeOptions.firstOrNull { it.value == themeModeState.value }?.label
            ?: themeOptions.firstOrNull()?.label
            ?: getString(R.string.theme_mode)
    }

    private fun showThemeModeActions() {
        showDialogFragment(
            ComposeActionListDialog.create(
                title = getString(R.string.theme_mode),
                labels = themeOptions.map { option ->
                    if (option.value == themeModeState.value) {
                        "${option.label}  ✓"
                    } else {
                        option.label
                    }
                },
                negativeText = getString(R.string.cancel),
                onSelected = { index ->
                    themeOptions.getOrNull(index)?.value?.let(::setThemeMode)
                }
            )
        )
    }

    private fun setWebServiceEnabled(enabled: Boolean) {
        requireContext().putPrefBoolean(PreferKey.webService, enabled)
        if (enabled) {
            WebService.start(requireContext())
        } else {
            WebService.stop(requireContext())
        }
        updateWebServiceState()
    }

    private fun handleWebServiceClick() {
        if (WebService.isRun) {
            showWebServiceActions()
        } else {
            setWebServiceEnabled(true)
        }
    }

    private fun setAutoTaskServiceEnabled(enabled: Boolean) {
        requireContext().putPrefBoolean(PreferKey.autoTaskService, enabled)
        if (enabled) {
            AutoTask.start(requireContext())
        } else {
            AutoTask.stop(requireContext())
        }
        updateAutoTaskServiceState()
    }

    private fun handleAutoTaskServiceClick() {
        setAutoTaskServiceEnabled(!requireContext().getPrefBoolean(PreferKey.autoTaskService))
    }

    private fun showWebServiceActions() {
        val url = WebService.hostAddress.takeIf { WebService.isRun } ?: return
        showDialogFragment(
            ComposeActionListDialog.create(
                title = getString(R.string.web_service),
                labels = listOf(
                    getString(R.string.copy_text),
                    getString(R.string.open_in_browser)
                ),
                descriptions = listOf(url, url),
                negativeText = getString(R.string.cancel),
                onSelected = { index ->
                    when (index) {
                        0 -> requireContext().sendToClip(url)
                        1 -> requireContext().openUrl(url)
                    }
                }
            )
        )
    }

    private fun handleRowClick(key: String, searchTarget: MySettingsSubSearchItem?) {
        if (searchTarget != null) {
            startActivity<ConfigActivity> {
                putExtra("configTag", searchTarget.ownerConfigTag)
                putExtra("targetKey", searchTarget.key)
            }
            return
        }
        when (key) {
            "bookSourceManage" -> startActivity<BookSourceActivity>()
            "rssSourceManage" -> startActivity<RssSourceActivity>()
            "replaceManage" -> startActivity<ReplaceRuleActivity>()
            "dictRuleManage" -> startActivity<DictRuleActivity>()
            "txtTocRuleManage" -> startActivity<TxtTocRuleActivity>()
            "autoTaskManage" -> startActivity<AutoTaskActivity>()
            "bookmark" -> startActivity<AllBookmarkActivity>()
            "setting" -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.OTHER_CONFIG)
            }

            "web_dav_setting" -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.BACKUP_CONFIG)
            }

            "publicWebRelay" -> startActivity<RelaySettingsActivity>()

            "cacheManage" -> startActivity<CacheManageActivity>()
            "theme_setting" -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.THEME_CONFIG)
            }

            "appearanceKit" -> startActivity<AppearanceKitActivity>()

            "ai_setting" -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.AI_CONFIG)
            }

            "fileManage" -> startActivity<FileManageActivity>()
            "readRecord" -> startActivity<ReadRecordActivity>()
            "about" -> startActivity<AboutActivity>()
            "exit" -> activity?.finish()
        }
    }

    private fun buildSections(): List<MySettingsSectionModel> {
        return listOf(
            MySettingsSectionModel(
                title = getString(R.string.config_category_content),
                rows = listOf(
                    actionRow("bookSourceManage", R.string.book_source_manage, R.string.book_source_manage_desc),
                    actionRow("rssSourceManage", R.string.rss_source_manage, R.string.rss_source_manage_summary),
                    actionRow("txtTocRuleManage", R.string.txt_toc_rule, R.string.config_txt_toc_rule),
                    actionRow("replaceManage", R.string.replace_purify, R.string.replace_purify_desc),
                    actionRow("dictRuleManage", R.string.dict_rule, R.string.config_dict_rule)
                )
            ),
            MySettingsSectionModel(
                title = getString(R.string.config_category_appearance),
                rows = listOf(
                    MySettingsRowModel(
                        key = PreferKey.themeMode,
                        title = getString(R.string.theme_mode),
                        summary = getString(R.string.theme_mode_desc),
                        kind = MySettingsRowKind.ThemeMode
                    ),
                    MySettingsRowModel(
                        key = "appearanceKit",
                        title = getString(R.string.appearance_kit_manage),
                        summary = getString(R.string.appearance_kit_summary)
                    ),
                    actionRow("theme_setting", R.string.theme_setting, R.string.theme_setting_s),
                    actionRow("ai_setting", R.string.ai_setting, R.string.ai_setting_summary)
                )
            ),
            MySettingsSectionModel(
                title = getString(R.string.config_category_sync),
                rows = listOf(
                    actionRow("web_dav_setting", R.string.backup_restore, R.string.web_dav_set_import_old),
                    actionRow("cacheManage", R.string.cache_manage_title, R.string.cache_manage_summary),
                    actionRow(
                        "publicWebRelay",
                        R.string.public_web_relay,
                        R.string.public_web_relay_summary
                    ),
                    MySettingsRowModel(
                        key = PreferKey.webService,
                        title = getString(R.string.web_service),
                        summary = getString(R.string.web_service_desc),
                        kind = MySettingsRowKind.WebService
                    )
                )
            ),
            MySettingsSectionModel(
                title = getString(R.string.auto_task_group),
                rows = listOf(
                    MySettingsRowModel(
                        key = PreferKey.autoTaskService,
                        title = getString(R.string.auto_task_service),
                        summary = getString(R.string.auto_task_service_desc),
                        kind = MySettingsRowKind.AutoTaskService
                    ),
                    actionRow(
                        key = "autoTaskManage",
                        titleRes = R.string.auto_task_manage,
                        summaryRes = R.string.auto_task_manage_desc
                    )
                )
            ),
            MySettingsSectionModel(
                title = getString(R.string.config_category_tools),
                rows = listOf(
                    actionRow("setting", R.string.other_setting, R.string.other_setting_s),
                    actionRow("bookmark", R.string.bookmark, R.string.all_bookmark),
                    actionRow("readRecord", R.string.read_record, R.string.read_record_summary),
                    actionRow("fileManage", R.string.file_manage, R.string.file_manage_summary),
                    actionRow("about", R.string.about, null),
                    actionRow("exit", R.string.exit, null)
                )
            )
        )
    }

    private fun actionRow(
        key: String,
        titleRes: Int,
        summaryRes: Int?,
        danger: Boolean = false
    ): MySettingsRowModel {
        return MySettingsRowModel(
            key = key,
            title = getString(titleRes),
            summary = summaryRes?.let(::getString),
            danger = danger
        )
    }

    private fun buildThemeOptions(): List<MySettingsThemeOption> {
        val labels = resources.getStringArray(R.array.theme_mode)
        val values = resources.getStringArray(R.array.theme_mode_v)
        return labels.mapIndexed { index, label ->
            MySettingsThemeOption(
                value = values.getOrElse(index) { index.toString() },
                label = label
            )
        }.ifEmpty {
            listOf(MySettingsThemeOption("0", getString(R.string.theme_mode)))
        }
    }

    private fun buildSubSearchItems(): List<MySettingsSubSearchItem> {
        val ownerKeys = mapOf(
            ConfigTag.THEME_CONFIG to "theme_setting",
            ConfigTag.COVER_CONFIG to "theme_setting",
            ConfigTag.WELCOME_CONFIG to "theme_setting",
            ConfigTag.DISCOVERY_SUBSCRIPTION_CONFIG to "theme_setting",
            ConfigTag.BACKUP_CONFIG to "web_dav_setting",
            ConfigTag.AI_CONFIG to "ai_setting",
            ConfigTag.OTHER_CONFIG to "setting",
            ConfigTag.NETWORK_DNS_CONFIG to "setting"
        )
        return SettingSearchIndex.build(requireContext()).mapNotNull { entry ->
            val ownerKey = ownerKeys[entry.pageConfigTag] ?: return@mapNotNull null
            MySettingsSubSearchItem(
                ownerKey = ownerKey,
                title = entry.title,
                summary = entry.summary,
                key = entry.key,
                ownerConfigTag = entry.pageConfigTag
            )
        }
    }
}
