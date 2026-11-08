package io.legado.app.model.login

import com.google.gson.JsonObject
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject

/**
 * 登录界面协议 v2。
 *
 * 书源在 loginUi 里写 `{"version":2}` 作为标记，真正的界面由脚本里的
 * `loginUi(state)` 返回 `{"rows":[...]}` 动态生成；用户操作通过
 * `loginAction(action, state, form)` 回传，返回 `state` 则重渲染、
 * 返回 `login` 则保存登录信息、返回 `close` 则关闭对话框。
 */
object LoginUiV2 {

    const val MARKER = """{"version":2}"""

    private val knownCommands = setOf("state", "error", "login", "close")

    fun isV2(loginUi: String?): Boolean {
        val text = loginUi?.trim() ?: return false
        if (!text.startsWith("{")) return false
        val obj = GSON.fromJsonObject<JsonObject>(text).getOrNull() ?: return false
        return runCatching { obj.get("version")?.asInt == 2 }.getOrDefault(false)
    }

    /**
     * 解析 loginUi 脚本返回的渲染结果，格式非法时返回 null（调用方按渲染失败处理）
     */
    fun parseRender(json: String?): List<RowUi>? {
        if (json.isNullOrBlank()) return null
        val obj = GSON.fromJsonObject<JsonObject>(json).getOrNull() ?: return null
        val rowsJson = obj.get("rows")?.takeIf { it.isJsonArray } ?: return null
        val rows = GSON.fromJsonArray<RowUi>(rowsJson.toString()).getOrNull() ?: return null
        if (rows.isEmpty() || rows.any { !it.isValid() }) return null
        val keys = rows.filter {
            it.type == RowUi.Type.text ||
                it.type == RowUi.Type.password ||
                it.type == RowUi.Type.select ||
                it.type == RowUi.Type.toggle
        }.mapNotNull { it.key }
        val actions = rows.filter {
            it.type == RowUi.Type.button || it.type == RowUi.Type.toggle
        }.mapNotNull { it.action }
        if (keys.size != keys.distinct().size || actions.size != actions.distinct().size) {
            return null
        }
        return rows
    }

    private fun RowUi.isValid(): Boolean {
        if (name.isBlank() || countdown?.let { it < 0 } == true) return false
        return when (type) {
            RowUi.Type.text, RowUi.Type.password -> !key.isNullOrBlank()
            RowUi.Type.label -> true
            RowUi.Type.select -> !key.isNullOrBlank() && !options.isNullOrEmpty()
            RowUi.Type.toggle -> !key.isNullOrBlank() &&
                (value == null || value == "true" || value == "false") &&
                (action == null || action.isNotBlank())
            RowUi.Type.button -> !action.isNullOrBlank()
            else -> false
        }
    }

    data class ActionResult(
        val stateJson: String? = null,
        val error: Map<String, String>? = null,
        val loginJson: String? = null,
        val close: Boolean = false,
        val unknownKeys: List<String> = emptyList(),
        val malformed: Boolean = false,
    )

    fun parseActionResult(json: String?): ActionResult {
        if (json.isNullOrBlank()) return ActionResult()
        val obj = GSON.fromJsonObject<JsonObject>(json).getOrNull()
            ?: return ActionResult(malformed = true)
        val state = obj.get("state")?.takeUnless { it.isJsonNull }
        val error = obj.get("error")?.takeUnless { it.isJsonNull }
        val login = obj.get("login")?.takeUnless { it.isJsonNull }
        val close = obj.get("close")?.takeUnless { it.isJsonNull }
        if (state != null && !state.isJsonObject ||
            error != null && !error.isJsonObject ||
            login != null && !login.isJsonObject ||
            close != null && (!close.isJsonPrimitive || !close.asJsonPrimitive.isBoolean)
        ) {
            return ActionResult(malformed = true)
        }
        val errors = error?.let {
            GSON.fromJsonObject<Map<String, String>>(it.toString()).getOrNull()
                ?: return ActionResult(malformed = true)
        }
        val closeValue = close?.let {
            runCatching { it.asBoolean }.getOrNull()
                ?: return ActionResult(malformed = true)
        } ?: false
        return ActionResult(
            stateJson = state?.toString(),
            error = errors,
            loginJson = login?.toString(),
            close = closeValue,
            unknownKeys = obj.keySet().filterNot { it in knownCommands },
        )
    }

    /**
     * 字段初值优先级：本次渲染给定的值 > 用户已输入但尚未提交的值 > 已保存的登录信息
     */
    fun resolveFieldValue(renderValue: String?, sessionInput: String?, stored: String?): String? {
        return renderValue ?: sessionInput ?: stored
    }
}
