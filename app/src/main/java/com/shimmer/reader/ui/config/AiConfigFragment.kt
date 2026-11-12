package com.shimmer.reader.ui.config

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import com.shimmer.reader.R
import com.shimmer.reader.constant.EventBus
import com.shimmer.reader.constant.PreferKey
import com.shimmer.reader.help.ai.AiToolRegistry
import com.shimmer.reader.help.config.AppConfig
import com.shimmer.reader.ui.config.compose.ComposeSettingFragment
import com.shimmer.reader.ui.config.compose.SettingActionSpec
import com.shimmer.reader.ui.config.compose.SettingPageSpec
import com.shimmer.reader.ui.config.compose.SettingSectionSpec
import com.shimmer.reader.ui.config.compose.SettingSwitchSpec
import com.shimmer.reader.ui.main.ai.AiChatActivity
import com.shimmer.reader.ui.main.ai.AiImageGalleryActivity
import com.shimmer.reader.ui.main.ai.AiMcpServerConfig
import com.shimmer.reader.ui.widget.compose.showComposeActionListDialog
import com.shimmer.reader.ui.widget.compose.showComposeConfirmDialog
import com.shimmer.reader.ui.widget.compose.showComposeMultiChoiceDialog
import com.shimmer.reader.ui.widget.compose.showComposeNumberPickerDialog
import com.shimmer.reader.ui.widget.compose.showComposeTextFormDialogWithChecks
import com.shimmer.reader.ui.widget.compose.showComposeTextInputDialog
import com.shimmer.reader.ui.file.FileManageActivity
import com.shimmer.reader.utils.observeEvent
import com.shimmer.reader.utils.postEvent
import com.shimmer.reader.utils.toastOnUi
import kotlinx.coroutines.launch
import java.io.File

/**
 * AI 设置页。
 *
 * 只保留一条主线：能对话。其余能力由 AI 在对话里自己调用工具完成，
 * 需要时再通过「增强功能 / 对话偏好」两个二级入口调整。
 */
class AiConfigFragment : ComposeSettingFragment() {

    private companion object {
        const val KEY_AI_ABILITY = "aiAbility"
        const val KEY_OPEN_CHAT = "aiOpenChat"
        const val KEY_AI_FEATURES = "aiFeatures"
        const val KEY_AI_PREFS = "aiPrefs"
        const val KEY_MANAGE_PROVIDERS = "aiManageProviders"
        const val KEY_MCP = "aiMcp"
        const val KEY_AI_WORKSPACE = "aiWorkspace"
    }

    override val titleRes: Int = R.string.ai_settings_title

    override fun onViewCreated(view: android.view.View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeEvent<Boolean>(EventBus.AI_CONFIG_CHANGED) {
            refreshUi()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun buildPageSpec(): SettingPageSpec {
        val canEnable = AppConfig.aiCurrentModelConfig != null
        val toolCount = AiToolRegistry.effectiveEnabledToolNames().size
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    title = str(R.string.ai_chat_group),
                    items = listOf(
                        SettingActionSpec(
                            key = KEY_OPEN_CHAT,
                            title = str(R.string.ai_open_chat),
                            summary = str(R.string.ai_open_chat_summary),
                            onClick = ::openChat
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.aiAssistantEnabled,
                            title = str(R.string.ai_enable),
                            summary = str(
                                when {
                                    !canEnable -> R.string.ai_enable_summary_disabled
                                    AppConfig.aiAssistantEnabled -> R.string.ai_enable_summary
                                    else -> R.string.ai_enable_summary_off
                                }
                            ),
                            checked = AppConfig.aiAssistantEnabled,
                            enabled = canEnable,
                            onCheckedChange = { AppConfig.aiAssistantEnabled = it }
                        ),
                        SettingActionSpec(
                            key = KEY_MANAGE_PROVIDERS,
                            title = str(R.string.ai_provider),
                            summary = modelSummary(),
                            onClick = {
                                startActivity(
                                    Intent(requireContext(), AiProviderManageActivity::class.java)
                                )
                            }
                        ),
                        SettingActionSpec(
                            key = PreferKey.aiHomeEntryEnabled,
                            title = str(R.string.ai_entry_row),
                            summary = str(
                                if (booleanSetting(PreferKey.aiHomeEntryEnabled, true)) {
                                    R.string.ai_entry_summary_on
                                } else {
                                    R.string.ai_entry_summary_off
                                }
                            ),
                            onClick = ::showChatEntryMenu
                        ),
                        SettingActionSpec(
                            key = KEY_AI_ABILITY,
                            title = str(R.string.ai_ability_entry),
                            summary = str(R.string.ai_ability_summary, toolCount),
                            onClick = ::showManageNativeToolsDialog
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.ai_more_group),
                    items = listOf(
                        SettingActionSpec(
                            key = KEY_AI_FEATURES,
                            title = str(R.string.ai_features),
                            summary = str(R.string.ai_features_summary),
                            onClick = ::showFeatureMenu
                        ),
                        SettingActionSpec(
                            key = KEY_MCP,
                            title = str(R.string.ai_mcp),
                            summary = mcpSummary(),
                            onClick = ::showMcpMenu
                        ),
                        SettingActionSpec(
                            key = KEY_AI_PREFS,
                            title = str(R.string.ai_prefs),
                            summary = str(
                                R.string.ai_prefs_summary,
                                AppConfig.aiAgentMaxToolRounds,
                                str(
                                    if (AppConfig.aiContextCompressionEnabled) {
                                        R.string.ai_state_on
                                    } else {
                                        R.string.ai_state_off
                                    }
                                )
                            ),
                            onClick = ::showPrefsMenu
                        )
                    )
                )
            )
        )
    }

    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            PreferKey.aiAssistantEnabled -> refreshUi(notifyMain = true)
            PreferKey.aiHomeEntryEnabled -> refreshUi(notifyMain = true)
            PreferKey.aiAskModelId,
            PreferKey.aiCurrentImageProviderId -> refreshUi()
        }
    }

    private fun modelSummary(): String {
        val models = AppConfig.aiModelConfigList
        if (models.isEmpty()) {
            return str(R.string.ai_provider_none_hint)
        }
        val model = AppConfig.aiCurrentModelConfig ?: models.first()
        val provider = AppConfig.aiProviderList.firstOrNull { it.id == model.providerId }?.name
        return if (provider.isNullOrBlank()) {
            model.modelId
        } else {
            "${model.modelId} · $provider"
        }
    }

    private fun mcpSummary(): String {
        val servers = AppConfig.aiMcpServerList
        if (servers.isEmpty()) {
            return str(R.string.ai_no_mcp_servers)
        }
        return str(
            R.string.ai_manage_mcp_servers_summary,
            servers.count { it.enabled },
            servers.size
        )
    }

    private fun openChat() {
        startActivity(Intent(requireContext(), AiChatActivity::class.java))
    }

    private fun showChatEntryMenu() {
        val enabled = booleanSetting(PreferKey.aiHomeEntryEnabled, true)
        showComposeActionListDialog(
            title = str(R.string.ai_entry_row),
            labels = listOf(
                str(R.string.ai_entry_summary_on).let {
                    if (enabled) "$it ✓" else it
                },
                str(R.string.ai_entry_summary_off).let {
                    if (!enabled) "$it ✓" else it
                }
            )
        ) { index ->
            updateBooleanSetting(PreferKey.aiHomeEntryEnabled, index == 0)
            refreshUi(notifyMain = true)
        }
    }

    private fun showFeatureMenu() {
        showComposeActionListDialog(
            title = str(R.string.ai_features),
            labels = listOf(
                str(R.string.ai_listen),
                str(R.string.ai_tavily_enable),
                str(R.string.ai_image_provider_manage),
                str(R.string.ai_image_gallery),
                str(R.string.ai_world_book_manage),
                str(R.string.ai_workspace)
            )
        ) { index ->
            when (index) {
                0 -> selectDefaultModel(str(R.string.ai_listen), AppConfig.aiReadAloudRoleModelId) {
                    AppConfig.aiReadAloudRoleModelId = it
                }

                1 -> showTavilyMenu()
                2 -> startActivity(
                    Intent(requireContext(), AiImageProviderManageActivity::class.java)
                )

                3 -> startActivity(Intent(requireContext(), AiImageGalleryActivity::class.java))
                4 -> startActivity(Intent(requireContext(), AiWorldBookManageActivity::class.java))
                5 -> openAiWorkspace()
            }
        }
    }

    private fun showPrefsMenu() {
        showComposeActionListDialog(
            title = str(R.string.ai_prefs),
            labels = listOf(
                switchLabel(
                    str(R.string.ai_enter_to_send),
                    booleanSetting(PreferKey.aiEnterToSend, true)
                ),
                switchLabel(
                    str(R.string.ai_thinking_toolbar),
                    booleanSetting(PreferKey.aiThinkingToolbarEnabled, true)
                ),
                str(R.string.ai_context_compression),
                str(R.string.ai_tool_rounds_limit),
                str(R.string.ai_read_tool_mode)
            )
        ) { index ->
            when (index) {
                0 -> {
                    updateBooleanSetting(
                        PreferKey.aiEnterToSend,
                        !booleanSetting(PreferKey.aiEnterToSend, true)
                    )
                    showPrefsMenu()
                }

                1 -> {
                    updateBooleanSetting(
                        PreferKey.aiThinkingToolbarEnabled,
                        !booleanSetting(PreferKey.aiThinkingToolbarEnabled, true)
                    )
                    showPrefsMenu()
                }

                2 -> showContextCompressionDialog()
                3 -> showAgentMaxToolRoundsDialog()
                4 -> showReadToolModeDialog()
            }
        }
    }

    private fun switchLabel(title: String, on: Boolean): String {
        return str(
            R.string.ai_switch_state,
            title,
            str(if (on) R.string.ai_state_on else R.string.ai_state_off)
        )
    }

    private fun showMcpMenu() {
        showComposeActionListDialog(
            title = str(R.string.ai_mcp),
            labels = listOf(
                str(R.string.ai_add_mcp_server),
                str(R.string.ai_manage_mcp_servers)
            )
        ) { index ->
            if (index == 0) showEditMcpServerDialog() else showManageMcpServersDialog()
        }
    }

    private fun showTavilyMenu() {
        val enabled = booleanSetting(PreferKey.aiTavilyEnabled, false)
        showComposeActionListDialog(
            title = str(R.string.ai_tavily_enable),
            labels = listOf(
                switchLabel(str(R.string.ai_tavily_enable), enabled),
                str(R.string.ai_tavily_api_key),
                str(R.string.ai_tavily_base_url),
                str(R.string.ai_tavily_topic),
                str(R.string.ai_tavily_search_depth),
                str(R.string.ai_tavily_max_results)
            )
        ) { index ->
            when (index) {
                0 -> {
                    if (!enabled && AppConfig.aiTavilyApiKey.isBlank()) {
                        toastOnUi(R.string.ai_tavily_enable_summary_missing)
                        return@showComposeActionListDialog
                    }
                    updateBooleanSetting(PreferKey.aiTavilyEnabled, !enabled)
                    refreshUi()
                }

                1 -> showTavilyApiKeyDialog()
                2 -> showTavilyBaseUrlDialog()
                3 -> showTavilyTopicDialog()
                4 -> showTavilySearchDepthDialog()
                5 -> showTavilyMaxResultsDialog()
            }
        }
    }

    private fun openAiWorkspace() {
        val root = File(requireContext().filesDir, "ai_workspace").apply { mkdirs() }
        startActivity(
            Intent(requireContext(), FileManageActivity::class.java)
                .putExtra(FileManageActivity.EXTRA_ROOT_PATH, root.absolutePath)
                .putExtra(FileManageActivity.EXTRA_TITLE, str(R.string.ai_workspace))
        )
    }

    private fun showAgentMaxToolRoundsDialog() {
        showComposeNumberPickerDialog(
            title = str(R.string.ai_tool_rounds_limit),
            value = AppConfig.aiAgentMaxToolRounds,
            minValue = 4,
            maxValue = 64,
            onValue = { value ->
                AppConfig.aiAgentMaxToolRounds = value
                refreshUi()
            }
        )
    }

    private fun showReadToolModeDialog() {
        val values = listOf(
            AppConfig.AI_READ_TOOL_MODE_ENABLED,
            AppConfig.AI_READ_TOOL_MODE_SAFE,
            AppConfig.AI_READ_TOOL_MODE_ALL
        )
        val labels = listOf(
            str(R.string.ai_read_tool_enabled),
            str(R.string.ai_read_tool_safe),
            str(R.string.ai_read_tool_all)
        )
        val current = AppConfig.aiReadToolMode
        showComposeActionListDialog(
            title = str(R.string.ai_read_tool_mode),
            labels = labels.mapIndexed { index, label ->
                if (values[index] == current) "$label ✓" else label
            }
        ) { index ->
            AppConfig.aiReadToolMode = values[index]
            refreshUi()
        }
    }

    private fun selectDefaultModel(
        title: String,
        currentId: String?,
        onSelect: (String) -> Unit
    ) {
        val models = AppConfig.aiModelConfigList
        if (models.isEmpty()) {
            toastOnUi(R.string.ai_no_models)
            return
        }
        val providerNameMap = AppConfig.aiProviderList.associateBy({ it.id }, { it.name })
        showComposeActionListDialog(
            title = title,
            labels = models.map { model ->
                val label = providerNameMap[model.providerId]?.takeIf { it.isNotBlank() }
                    ?.let { "${model.modelId} - $it" }
                    ?: model.modelId
                if (model.id == currentId) "$label ✓" else label
            }
        ) { index ->
            onSelect(models[index].id)
            refreshUi()
        }
    }

    private fun showEditMcpServerDialog(server: AiMcpServerConfig? = null) {
        showComposeTextFormDialogWithChecks(
            title = str(
                if (server == null) R.string.ai_add_mcp_server else R.string.ai_edit_mcp_server
            ),
            labels = listOf(
                str(R.string.ai_mcp_server_name),
                str(R.string.ai_mcp_server_endpoint),
                str(R.string.ai_api_key)
            ),
            initialValues = listOf(
                server?.name.orEmpty(),
                server?.endpoint.orEmpty(),
                server?.apiKey.orEmpty()
            ),
            passwordFields = setOf(2),
            checkboxLabels = listOf(str(R.string.ai_mcp_server_enabled)),
            checkedIndices = if (server?.enabled != false) setOf(0) else emptySet(),
            validateInput = { values ->
                val name = values.getOrNull(0).orEmpty().trim()
                val endpoint = values.getOrNull(1).orEmpty().trim()
                when {
                    name.isEmpty() -> {
                        toastOnUi(R.string.ai_mcp_server_name_required)
                        false
                    }

                    endpoint.isEmpty() -> {
                        toastOnUi(R.string.ai_mcp_server_endpoint_required)
                        false
                    }

                    else -> true
                }
            },
            onPositive = { values, checked ->
                val name = values[0].trim()
                val endpoint = values[1].trim()
                val apiKey = values[2].trim()
                val enabled = checked.getOrElse(0) { true }
                val servers = AppConfig.aiMcpServerList.toMutableList()
                val updated = server?.copy(
                    name = name,
                    endpoint = endpoint,
                    apiKey = apiKey,
                    enabled = enabled
                ) ?: AiMcpServerConfig(
                    name = name,
                    endpoint = endpoint,
                    apiKey = apiKey,
                    enabled = enabled
                )
                val targetIndex = servers.indexOfFirst { it.id == updated.id }
                if (targetIndex >= 0) {
                    servers[targetIndex] = updated
                } else {
                    servers.add(updated)
                }
                AppConfig.aiMcpServerList = servers
                refreshUi()
                toastOnUi(R.string.ai_mcp_server_saved)
            }
        )
    }

    private fun showManageMcpServersDialog() {
        val servers = AppConfig.aiMcpServerList
        if (servers.isEmpty()) {
            toastOnUi(R.string.ai_no_mcp_servers)
            return
        }
        showComposeActionListDialog(
            title = str(R.string.ai_manage_mcp_servers),
            labels = servers.map { server ->
                buildString {
                    append(server.name)
                    if (!server.enabled) append(" (off)")
                }
            }
        ) { index ->
            val server = servers[index]
            showComposeActionListDialog(
                title = server.name,
                labels = listOf(
                    str(
                        if (server.enabled) {
                            R.string.ai_disable_mcp_server
                        } else {
                            R.string.ai_enable_mcp_server
                        }
                    ),
                    str(R.string.ai_edit_mcp_server),
                    str(R.string.ai_remove_mcp_server)
                )
            ) { action ->
                when (action) {
                    0 -> {
                        AppConfig.aiMcpServerList = AppConfig.aiMcpServerList.map {
                            if (it.id == server.id) it.copy(enabled = !it.enabled) else it
                        }
                        refreshUi()
                    }

                    1 -> showEditMcpServerDialog(server)
                    2 -> confirmRemoveMcpServer(server)
                }
            }
        }
    }

    private fun confirmRemoveMcpServer(server: AiMcpServerConfig) {
        showComposeConfirmDialog(
            title = server.name,
            message = str(R.string.ai_remove_mcp_server_confirm),
            onPositive = {
                AppConfig.aiMcpServerList = AppConfig.aiMcpServerList.filterNot { it.id == server.id }
                refreshUi()
                toastOnUi(R.string.ai_mcp_server_removed)
            }
        )
    }

    private fun showContextCompressionDialog() {
        val enabledText = str(
            if (AppConfig.aiContextCompressionEnabled) {
                R.string.ai_context_compression_disable
            } else {
                R.string.ai_context_compression_enable
            }
        )
        showComposeActionListDialog(
            title = str(R.string.ai_context_compression),
            labels = listOf(
                enabledText,
                str(R.string.ai_context_length, AppConfig.aiContextWindowTokens),
                str(R.string.ai_thinking_context_length, AppConfig.aiThinkingContextTokens)
            )
        ) { index ->
            when (index) {
                0 -> {
                    AppConfig.aiContextCompressionEnabled = !AppConfig.aiContextCompressionEnabled
                    refreshUi()
                }

                1 -> showTokenSelector(true)
                2 -> showTokenSelector(false)
            }
        }
    }

    private fun showTokenSelector(contextWindow: Boolean) {
        val values = if (contextWindow) {
            listOf(32_000, 64_000, 128_000, 258_000, 512_000, 1_000_000)
        } else {
            listOf(0, 32_000, 64_000, 128_000, 258_000)
        }
        showComposeActionListDialog(
            title = if (contextWindow) {
                str(R.string.ai_context_tokens)
            } else {
                str(R.string.ai_thinking_context_tokens)
            },
            labels = values.map { it.toString() }
        ) { index ->
            if (contextWindow) AppConfig.aiContextWindowTokens = values[index]
            else AppConfig.aiThinkingContextTokens = values[index]
            refreshUi()
        }
    }

    private fun showManageNativeToolsDialog() {
        lifecycleScope.launch {
            val tools = runCatching { AiToolRegistry.resolveAllToolNamesForManage() }
                .getOrDefault(emptyList())
            if (tools.isEmpty()) {
                toastOnUi(R.string.not_available)
                return@launch
            }
            val groupedTools = tools.map { AiToolRegistry.metaOfTool(it) }
                .groupBy { it.group }
                .toSortedMap(compareBy { groupOrder(it) })
            val toolNames = mutableListOf<String>()
            val toolLabels = mutableListOf<String>()
            groupedTools.forEach { (group, groupTools) ->
                toolNames.add("__group_$group")
                toolLabels.add("--- ${groupDisplayName(group)} ---")
                groupTools.sortedBy { it.label }.forEach { tool ->
                    toolNames.add(tool.name)
                    toolLabels.add(tool.label)
                }
            }
            val enabledToolNames = AiToolRegistry.effectiveEnabledToolNames()
            val checkedIndices = toolNames.indices
                .filter { i -> toolNames[i] in enabledToolNames }
                .toSet()
            showComposeMultiChoiceDialog(
                title = str(R.string.ai_ability_entry),
                labels = toolLabels,
                checkedIndices = checkedIndices,
                positiveText = str(android.R.string.ok),
                negativeText = str(R.string.cancel),
                onPositive = { checked ->
                    val newEnabled = mutableSetOf<String>()
                    checked.forEachIndexed { index, isChecked ->
                        if (isChecked && !toolNames[index].startsWith("__group_")) {
                            newEnabled.add(toolNames[index])
                        }
                    }
                    AppConfig.aiEnabledToolNames = newEnabled
                    refreshUi()
                }
            )
        }
    }

    private fun groupDisplayName(group: String): String {
        val res = when (group) {
            "书架" -> R.string.ai_tool_group_bookshelf
            "AI workspace" -> R.string.ai_tool_group_workspace
            "书源" -> R.string.ai_tool_group_book_source
            "阅读" -> R.string.ai_tool_group_reading
            "阅读网络" -> R.string.ai_tool_group_reading_network
            "联网搜索" -> R.string.ai_tool_group_web_search
            "AI 生图" -> R.string.ai_tool_group_image
            "角色资料" -> R.string.ai_tool_group_character
            "AI 图片库" -> R.string.ai_tool_group_gallery
            "角色配音" -> R.string.ai_tool_group_voice
            "智能配乐" -> R.string.ai_tool_group_bgm
            "世界书" -> R.string.ai_tool_group_world_book
            "设置" -> R.string.ai_tool_group_settings
            else -> null
        }
        return res?.let { str(it) } ?: group
    }

    private fun groupOrder(group: String): Int {
        return when (group) {
            "书架" -> 0
            "AI workspace" -> 1
            "书源" -> 2
            "阅读" -> 3
            "阅读网络" -> 4
            "联网搜索" -> 5
            "AI 生图" -> 6
            "角色资料" -> 7
            "设置" -> 8
            "MCP 工具" -> 9
            else -> 9
        }
    }

    private fun showTavilyApiKeyDialog() {
        showComposeTextInputDialog(
            title = str(R.string.ai_tavily_api_key),
            hint = str(R.string.ai_tavily_api_key_hint),
            initialValue = AppConfig.aiTavilyApiKey,
            onPositive = { text ->
                AppConfig.aiTavilyApiKey = text
                refreshUi()
            }
        )
    }

    private fun showTavilyBaseUrlDialog() {
        showComposeTextInputDialog(
            title = str(R.string.ai_tavily_base_url),
            hint = "https://api.tavily.com/search",
            initialValue = AppConfig.aiTavilyBaseUrl,
            neutralText = str(R.string.restore_default),
            onPositive = { text ->
                AppConfig.aiTavilyBaseUrl = text
                refreshUi()
            },
            onNeutral = {
                AppConfig.aiTavilyBaseUrl = "https://api.tavily.com/search"
                refreshUi()
            }
        )
    }

    private fun showTavilyTopicDialog() {
        val values = listOf("general", "news", "finance")
        val labels = listOf(
            str(R.string.ai_tavily_topic_general),
            str(R.string.ai_tavily_topic_news),
            str(R.string.ai_tavily_topic_finance)
        )
        showComposeActionListDialog(
            title = str(R.string.ai_tavily_topic),
            labels = labels
        ) { index ->
            AppConfig.aiTavilyTopic = values[index]
            refreshUi()
        }
    }

    private fun showTavilySearchDepthDialog() {
        val values = listOf("basic", "advanced", "ultra-fast")
        val labels = listOf(
            str(R.string.ai_tavily_search_depth_basic),
            str(R.string.ai_tavily_search_depth_advanced),
            str(R.string.ai_tavily_search_depth_ultra_fast)
        )
        showComposeActionListDialog(
            title = str(R.string.ai_tavily_search_depth),
            labels = labels
        ) { index ->
            AppConfig.aiTavilySearchDepth = values[index]
            refreshUi()
        }
    }

    private fun showTavilyMaxResultsDialog() {
        showComposeNumberPickerDialog(
            title = str(R.string.ai_tavily_max_results),
            value = AppConfig.aiTavilyMaxResults,
            minValue = 1,
            maxValue = 10,
            onValue = { value ->
                AppConfig.aiTavilyMaxResults = value
                refreshUi()
            }
        )
    }

    private fun refreshUi(notifyMain: Boolean = false) {
        val canEnable = AppConfig.aiCurrentModelConfig != null
        val storedEnabled = booleanSetting(PreferKey.aiAssistantEnabled, false)
        if (!canEnable && storedEnabled) {
            AppConfig.aiAssistantEnabled = false
        }
        refreshSettings()
        if (notifyMain || (!canEnable && storedEnabled)) {
            postEvent(EventBus.NOTIFY_MAIN, false)
        }
    }
}
