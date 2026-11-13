package com.shimmer.reader.ui.main.ai

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import com.shimmer.reader.R
import com.shimmer.reader.base.BaseActivity
import com.shimmer.reader.data.appDb
import com.shimmer.reader.databinding.ActivityAiChatBinding
import com.shimmer.reader.help.ai.AiImageGalleryManager
import com.shimmer.reader.help.config.AppConfig
import com.shimmer.reader.lib.dialogs.alert
import com.shimmer.reader.lib.dialogs.selector
import com.shimmer.reader.ui.config.ConfigActivity
import com.shimmer.reader.ui.config.ConfigTag
import com.shimmer.reader.ui.main.ai.compose.AiChatRoute
import com.shimmer.reader.ui.main.ai.compose.AiChatScreenActions
import com.shimmer.reader.utils.toastOnUi
import com.shimmer.reader.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AiChatActivity : BaseActivity<ActivityAiChatBinding>(
    fullScreen = false,
    imageBg = false
) {

    override val binding by viewBinding(ActivityAiChatBinding::inflate)

    private val viewModel by viewModels<AiChatViewModel>()
    private val historyTimeFormat by lazy { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    private val refreshToken = mutableIntStateOf(0)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeRoot.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeRoot.setContent {
            AiChatRoute(
                viewModel = viewModel,
                lifecycleOwner = this,
                compactHeader = false,
                refreshToken = refreshToken.intValue,
                actions = AiChatScreenActions(
                    onSend = ::dispatchSend,
                    onStop = ::cancelCurrentRequest,
                    onOpenSettings = ::openAiSettings,
                    onNewChat = ::startNewChatFromMenu,
                    onOpenHistory = ::openHistoryFromMenu,
                    onSelectModel = ::showModelSelectorDialog,
                    onOpenWindowAbilities = ::showWindowAbilityDialog,
                    onToggleAutoSpeak = ::toggleAutoSpeak,
                    onSpeakMessage = { text, companion, playbackKey ->
                        speakCompanionMessage(text, companion, playbackKey)
                    },
                    onAddCompanion = null,
                    onSelectCompanion = ::selectCompanion,
                    onSelectSession = ::loadSessionFromDrawer,
                    onSelectCompanionSession = ::loadCompanionSessionFromDrawer,
                    onNewCompanionChat = ::startNewChatFromDrawer,
                    onDeleteSession = ::confirmDeleteHistorySession,
                    onCompanionLongPress = ::showCompanionActions,
                    onDeleteMessage = ::confirmDeleteMessageFromHere,
                    onRetryMessage = ::dispatchRetryMessage,
                    onSelectAssistantVariant = ::selectAssistantVariant,
                    onAssistantAvatarLongPress = ::showAgentModeDialog
                )
            )
        }
        lifecycleScope.launch(Dispatchers.IO) {
            AiImageGalleryManager.cleanupExpiredTemporary()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshToken.intValue += 1
    }

    override fun onDestroy() {
        AiChatSpeechPlayer.stop()
        super.onDestroy()
    }

    private fun dispatchSend(content: String): Boolean {
        if (content.isBlank() || viewModel.isRequesting) return false
        val provider = AppConfig.aiCurrentProvider
        if (provider?.baseUrl.isNullOrBlank() || AppConfig.aiCurrentModelConfig == null) {
            toastOnUi(R.string.ai_missing_config)
            return false
        }
        viewModel.startRequest(
            userContent = content.trim(),
            thinkingText = getString(R.string.ai_chat_thinking),
            cancelledText = getString(R.string.ai_chat_cancelled),
            failureMessage = { getString(R.string.ai_request_failed, it) }
        )
        return true
    }

    private fun cancelCurrentRequest() {
        viewModel.stopRequest(getString(R.string.ai_chat_cancelled))
    }

    private fun showAgentModeDialog() {
        if (viewModel.isRequesting) {
            toastOnUi(R.string.ai_chat_wait_current)
            return
        }
        val modes = listOf(
            AiAgentMode.NORMAL,
            AiAgentMode.GOAL,
            AiAgentMode.PLAN
        )
        val labels = listOf(
            getString(R.string.ai_chat_mode_normal_desc),
            getString(R.string.ai_chat_mode_goal_desc),
            getString(R.string.ai_chat_mode_plan_desc)
        )
        selector(getString(R.string.ai_chat_mode_title), labels) { _, _, index ->
            val mode = modes.getOrNull(index) ?: return@selector
            viewModel.setAgentMode(mode)
            refreshToken.intValue += 1
            toastOnUi(
                when (mode) {
                    AiAgentMode.NORMAL -> getString(
                        R.string.ai_chat_mode_switched,
                        getString(R.string.ai_chat_mode_normal)
                    )
                    AiAgentMode.GOAL -> getString(
                        R.string.ai_chat_mode_switched,
                        getString(R.string.ai_chat_mode_goal)
                    )
                    AiAgentMode.PLAN -> getString(
                        R.string.ai_chat_mode_switched,
                        getString(R.string.ai_chat_mode_plan)
                    )
                }
            )
        }
    }

    private fun dispatchRetryMessage(messageId: String) {
        if (viewModel.isRequesting) {
            toastOnUi(R.string.ai_chat_wait_current)
            return
        }
        val provider = AppConfig.aiCurrentProvider
        if (provider?.baseUrl.isNullOrBlank() || AppConfig.aiCurrentModelConfig == null) {
            toastOnUi(R.string.ai_missing_config)
            return
        }
        val started = viewModel.retryFromMessage(
            messageId = messageId,
            thinkingText = getString(R.string.ai_chat_thinking),
            cancelledText = getString(R.string.ai_chat_cancelled),
            failureMessage = { getString(R.string.ai_request_failed, it) }
        )
        if (!started) {
            toastOnUi(R.string.toast_cannot_retry_message)
        }
        refreshToken.intValue += 1
    }

    private fun selectAssistantVariant(variantGroupId: String, variantIndex: Int) {
        if (viewModel.selectAssistantVariant(variantGroupId, variantIndex)) {
            refreshToken.intValue += 1
        }
    }

    private fun confirmDeleteMessageFromHere(messageId: String) {
        if (viewModel.isRequesting) {
            toastOnUi(R.string.ai_chat_wait_current)
            return
        }
        alert(
            title = getString(R.string.delete),
            message = getString(R.string.ai_chat_delete_from_here)
        ) {
            okButton {
                if (viewModel.deleteFromMessage(messageId)) {
                    refreshToken.intValue += 1
                }
            }
            cancelButton()
        }
    }

    private fun openHistoryFromMenu() {
        if (viewModel.isRequesting) {
            toastOnUi(R.string.ai_chat_wait_current)
            return
        }
        showHistoryDialog()
    }

    private fun startNewChatFromMenu() {
        if (viewModel.isRequesting) {
            toastOnUi(R.string.ai_chat_wait_current)
            return
        }
        viewModel.startNewSession()
        refreshToken.intValue += 1
    }

    private fun openAiSettings() {
        android.content.Intent(this, ConfigActivity::class.java).apply {
            putExtra("configTag", ConfigTag.AI_CONFIG)
        }.also(::startActivity)
    }


    private fun selectCompanion(companionId: String) {
        if (!viewModel.switchCompanion(companionId)) {
            toastOnUi(R.string.ai_chat_wait_current)
            return
        }
        refreshToken.intValue += 1
    }

    private fun startNewChatFromDrawer(companionId: String) {
        if (viewModel.isRequesting) {
            toastOnUi(R.string.ai_chat_wait_current)
            return
        }
        viewModel.startNewSession(companionId)
        refreshToken.intValue += 1
    }

    private fun loadSessionFromDrawer(sessionId: String) {
        if (viewModel.isRequesting) {
            toastOnUi(R.string.ai_chat_wait_current)
            return
        }
        viewModel.loadSession(sessionId)
        refreshToken.intValue += 1
    }

    private fun loadCompanionSessionFromDrawer(companionId: String, sessionId: String) {
        if (viewModel.isRequesting) {
            toastOnUi(R.string.ai_chat_wait_current)
            return
        }
        if (!viewModel.loadSession(companionId, sessionId)) {
            toastOnUi(R.string.ai_chat_wait_current)
            return
        }
        refreshToken.intValue += 1
    }

    private fun toggleAutoSpeak() {
        AppConfig.aiChatAutoSpeakEnabled = !AppConfig.aiChatAutoSpeakEnabled
        refreshToken.intValue += 1
    }

    private fun speakCompanionMessage(
        text: String,
        companion: AiChatCompanionConfig,
        playbackKey: String
    ) {
        if (AiChatSpeechPlayer.stopIfActive(playbackKey)) {
            return
        }
        lifecycleScope.launch {
            val routeJson = withContext(Dispatchers.IO) {
                resolveCompanionSpeechRouteJson(companion)
            }
            AiChatSpeechPlayer.speak(text, routeJson, playbackKey)
        }
    }

    private fun resolveCompanionSpeechRouteJson(companion: AiChatCompanionConfig): String {
        if (companion.type != AiChatCompanionConfig.TYPE_CHARACTER) {
            return companion.ttsRouteJson
        }
        val characterId = companion.characterId.toLongOrNull() ?: return companion.ttsRouteJson
        val character = appDb.bookCharacterDao.getCharacter(characterId) ?: return companion.ttsRouteJson
        if (companion.bookKey.isNotBlank() && character.bookUrl != companion.bookKey) {
            return companion.ttsRouteJson
        }
        return character.speechRouteJson.ifBlank { companion.ttsRouteJson }
    }

    private fun showCompanionActions(companion: AiChatCompanionConfig) {
        if (viewModel.isRequesting) {
            toastOnUi(R.string.ai_chat_wait_current)
            return
        }
        if (companion.id == AiChatCompanionConfig.DEFAULT_COMPANION_ID) {
            startNewChatForCompanion(companion.id)
            return
        }
        val items = listOf(
            getString(R.string.ai_chat_new_chat),
            getString(R.string.ai_chat_remove_character_assistant)
        )
        selector(companion.name.ifBlank { getString(R.string.ai_chat_assistant) }, items) { _, _, index ->
            when (index) {
                0 -> startNewChatForCompanion(companion.id)
                1 -> confirmDeleteCompanion(companion)
            }
        }
    }

    private fun startNewChatForCompanion(companionId: String) {
        if (!viewModel.switchCompanion(companionId)) {
            toastOnUi(R.string.ai_chat_wait_current)
            return
        }
        viewModel.startNewSession()
        refreshToken.intValue += 1
    }

    private fun confirmDeleteCompanion(companion: AiChatCompanionConfig) {
        if (companion.id == AiChatCompanionConfig.DEFAULT_COMPANION_ID) {
            toastOnUi(R.string.toast_default_assistant_undeletable)
            return
        }
        alert(
            title = getString(R.string.ai_chat_remove_character_assistant),
            message = getString(R.string.ai_chat_remove_companion_message, companion.name)
        ) {
            okButton {
                AppConfig.removeAiChatCompanion(companion.id)
                viewModel.switchCompanion(AiChatCompanionConfig.DEFAULT_COMPANION_ID)
                refreshToken.intValue += 1
            }
            cancelButton()
        }
    }

    private fun showWindowAbilityDialog() {
        if (viewModel.isRequesting) {
            toastOnUi(R.string.ai_chat_wait_current)
            return
        }
        selector(
            getString(R.string.ai_chat_window_abilities),
            listOf(
                getString(R.string.ai_chat_skill_count, viewModel.activeWindowSkillIds().size),
                getString(R.string.ai_chat_mcp_count, viewModel.activeWindowMcpServerIds().size),
                getString(R.string.ai_chat_clear_skill_mcp)
            )
        ) { _, _, index ->
            when (index) {
                0 -> showWindowSkillDialog()
                1 -> showWindowMcpDialog()
                2 -> {
                    viewModel.setActiveWindowSkillIds(emptySet())
                    viewModel.setActiveWindowMcpServerIds(emptySet())
                    refreshToken.intValue += 1
                }
            }
        }
    }

    private fun showWindowSkillDialog() {
        val skills = AppConfig.aiSkillList.filter { it.enabled }
        if (skills.isEmpty()) {
            toastOnUi(R.string.toast_no_skill)
            return
        }
        val selected = viewModel.activeWindowSkillIds().toMutableSet()
        alert(title = getString(R.string.ai_chat_window_skill)) {
            multiChoiceItems(
                items = skills.map { skill -> skill.name.ifBlank { "Skill" } }.toTypedArray(),
                checkedItems = BooleanArray(skills.size) { index -> skills[index].id in selected }
            ) { _, which, isChecked ->
                if (isChecked) selected += skills[which].id else selected -= skills[which].id
            }
            okButton {
                viewModel.setActiveWindowSkillIds(selected)
                refreshToken.intValue += 1
            }
            neutralButton(getString(R.string.ai_chat_clear)) {
                viewModel.setActiveWindowSkillIds(emptySet())
                refreshToken.intValue += 1
            }
            cancelButton()
        }
    }

    private fun showWindowMcpDialog() {
        val servers = AppConfig.aiMcpServerList.filter { it.enabled }
        if (servers.isEmpty()) {
            toastOnUi(R.string.toast_no_mcp)
            return
        }
        val selected = viewModel.activeWindowMcpServerIds().toMutableSet()
        alert(title = getString(R.string.ai_chat_window_mcp)) {
            multiChoiceItems(
                items = servers.map { server -> server.name.ifBlank { "MCP" } }.toTypedArray(),
                checkedItems = BooleanArray(servers.size) { index -> servers[index].id in selected }
            ) { _, which, isChecked ->
                if (isChecked) selected += servers[which].id else selected -= servers[which].id
            }
            okButton {
                viewModel.setActiveWindowMcpServerIds(selected)
                refreshToken.intValue += 1
            }
            neutralButton(getString(R.string.ai_chat_clear)) {
                viewModel.setActiveWindowMcpServerIds(emptySet())
                refreshToken.intValue += 1
            }
            cancelButton()
        }
    }

    private fun showHistoryDialog() {
        val sessions = viewModel.historySessions()
        if (sessions.isEmpty()) {
            toastOnUi(R.string.ai_history_empty)
            return
        }
        val items = mutableListOf(getString(R.string.ai_history_clear_all))
        items += sessions.map { session ->
            "${session.title}\n${historyTimeFormat.format(Date(session.updatedAt))}"
        }
        selector(getString(R.string.ai_chat_history), items) { _, _, index ->
            if (index == 0) {
                confirmClearAllHistory()
            } else {
                showHistorySessionActions(sessions[index - 1])
            }
        }
    }

    private fun showHistorySessionActions(session: AiChatSession) {
        selector(
            session.title,
            listOf(
                getString(R.string.ai_history_open),
                getString(R.string.ai_history_delete)
            )
        ) { _, _, index ->
            when (index) {
                0 -> {
                    viewModel.loadSession(session.id)
                    refreshToken.intValue += 1
                }
                1 -> confirmDeleteHistorySession(session)
            }
        }
    }

    private fun confirmDeleteHistorySession(session: AiChatSession) {
        alert(
            title = getString(R.string.ai_history_delete),
            message = getString(R.string.ai_history_delete_confirm, session.title)
        ) {
            okButton {
                viewModel.deleteSession(session.id)
                refreshToken.intValue += 1
            }
            cancelButton()
        }
    }

    private fun confirmClearAllHistory() {
        alert(
            title = getString(R.string.ai_history_clear_all),
            message = getString(R.string.ai_history_clear_all_confirm)
        ) {
            okButton {
                viewModel.clearAllSessions()
                refreshToken.intValue += 1
            }
            cancelButton()
        }
    }

    private fun showModelSelectorDialog() {
        if (viewModel.isRequesting) {
            toastOnUi(R.string.ai_chat_wait_current)
            return
        }
        val models = AppConfig.aiModelConfigList
        if (models.isEmpty()) {
            toastOnUi(R.string.ai_no_models)
            return
        }
        val providerNameMap = AppConfig.aiProviderList.associateBy({ it.id }, { it.name })
        selector(
            getString(R.string.ai_current_model),
            models.map { model ->
                providerNameMap[model.providerId]?.takeIf { it.isNotBlank() }
                    ?.let { "${model.modelId} · $it" }
                    ?: model.modelId
            }
        ) { _, _, index ->
            AppConfig.aiCurrentModelId = models[index].id
            refreshToken.intValue += 1
        }
    }
}
