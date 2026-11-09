package io.legado.app.ui.config

import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.ai.AiToolRegistry
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.read.ContentSelectConfig
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefStringSet
import splitties.init.appCtx

/**
 * 内置技能：把散落在菜单和工具注册表里的能力，按「用户能理解的一件事」重新打包。
 *
 * APP 类技能在本地直接执行，Agent 类技能由 AI 在对话中按需调用。
 * 开关都落到真实配置上，不存在只改界面不改行为的情况。
 */
data class AiBuiltinSkill(
    val id: String,
    val titleRes: Int,
    val descriptionRes: Int,
    val tag: String,
    val toolNames: List<String>
)

object AiBuiltinSkills {

    const val TAG_APP = "APP"
    const val TAG_AGENT = "Agent"

    private const val BOOKSHELF_TOOLS = "query_bookshelf,get_bookshelf_book_info," +
            "manage_bookshelf_group,manage_bookshelf_tag," +
            "set_bookshelf_book_group,set_bookshelf_book_tags"

    private const val CHARACTER_TOOLS = "list_book_characters,upsert_book_character," +
            "delete_book_character,list_book_character_relations," +
            "upsert_book_character_relation,delete_book_character_relation"

    private const val READING_TOOLS = "list_book_chapters,search_book_chapter_content," +
            "read_book_chapter_content,query_read_records"

    val all: List<AiBuiltinSkill> = listOf(
        AiBuiltinSkill(
            id = "paragraph_purify",
            titleRes = R.string.ai_skill_purify,
            descriptionRes = R.string.ai_skill_purify_desc,
            tag = TAG_APP,
            toolNames = emptyList()
        ),
        AiBuiltinSkill(
            id = "bookshelf",
            titleRes = R.string.ai_skill_bookshelf,
            descriptionRes = R.string.ai_skill_bookshelf_desc,
            tag = TAG_AGENT,
            toolNames = BOOKSHELF_TOOLS.split(",")
        ),
        AiBuiltinSkill(
            id = "characters",
            titleRes = R.string.ai_skill_characters,
            descriptionRes = R.string.ai_skill_characters_desc,
            tag = TAG_AGENT,
            toolNames = CHARACTER_TOOLS.split(",")
        ),
        AiBuiltinSkill(
            id = "reading",
            titleRes = R.string.ai_skill_reading,
            descriptionRes = R.string.ai_skill_reading_desc,
            tag = TAG_AGENT,
            toolNames = READING_TOOLS.split(",")
        )
    )

    fun isEnabled(skill: AiBuiltinSkill): Boolean {
        if (skill.toolNames.isEmpty()) {
            return ContentSelectConfig.selectedActionIds(appCtx)
                .contains(ContentSelectConfig.ACTION_AI_PURIFY)
        }
        val enabledTools = AiToolRegistry.effectiveEnabledToolNames()
        return skill.toolNames.all { it in enabledTools }
    }

    fun setEnabled(skill: AiBuiltinSkill, enabled: Boolean) {
        if (skill.toolNames.isEmpty()) {
            val actions = ContentSelectConfig.selectedActionIds(appCtx).toMutableSet()
            if (enabled) {
                actions.add(ContentSelectConfig.ACTION_AI_PURIFY)
            } else {
                actions.remove(ContentSelectConfig.ACTION_AI_PURIFY)
            }
            appCtx.putPrefStringSet(PreferKey.contentSelectActions, actions)
            postEvent(EventBus.CONTENT_SELECT_MENU_CONFIG_CHANGED, true)
            return
        }
        // effectiveEnabledToolNames 会先完成历史版本的默认工具迁移，
        // 在它的结果上增删，避免把迁移补进来的工具又丢掉。
        val enabledTools = AiToolRegistry.effectiveEnabledToolNames().toMutableSet()
        if (enabled) {
            enabledTools.addAll(skill.toolNames)
        } else {
            enabledTools.removeAll(skill.toolNames.toSet())
        }
        AppConfig.aiEnabledToolNames = enabledTools
        postEvent(EventBus.AI_CONFIG_CHANGED, true)
    }

    fun enabledCount(): Int = all.count { isEnabled(it) }
}
