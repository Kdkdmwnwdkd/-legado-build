package io.legado.app.model

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.data.entities.Book

/** Configuration shared by the book-details and bookshelf scheduling entry points. */
data class AutoTaskBookSettings(
    val enabled: Boolean = true,
    val notifyEnabled: Boolean = true,
    val cacheEnabled: Boolean = false,
    val intervalHours: Int = DEFAULT_INTERVAL_HOURS
) {
    fun normalized(): AutoTaskBookSettings = copy(
        intervalHours = intervalHours.coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS)
    )

    companion object {
        const val DEFAULT_INTERVAL_HOURS = 1
        const val MIN_INTERVAL_HOURS = 1
        const val MAX_INTERVAL_HOURS = 9_999
    }
}

/** Pure parsing/building helpers for the built-in book update task. */
object AutoTaskBookConfig {

    fun load(bookUrl: String, fallbackIntervalHours: Int = AutoTaskBookSettings.DEFAULT_INTERVAL_HOURS):
        AutoTaskBookSettings {
        val fallback = fallbackIntervalHours.coerceIn(
            AutoTaskBookSettings.MIN_INTERVAL_HOURS,
            AutoTaskBookSettings.MAX_INTERVAL_HOURS
        )
        val rule = AutoTask.get(AutoTask.bookTaskId(bookUrl)) ?: return AutoTaskBookSettings(
            intervalHours = fallback
        )
        return fromRule(rule, bookUrl, fallback)
    }

    fun fromRule(
        rule: AutoTaskRule?,
        bookUrl: String,
        fallbackIntervalHours: Int = AutoTaskBookSettings.DEFAULT_INTERVAL_HOURS
    ): AutoTaskBookSettings {
        val fallback = fallbackIntervalHours.coerceIn(
            AutoTaskBookSettings.MIN_INTERVAL_HOURS,
            AutoTaskBookSettings.MAX_INTERVAL_HOURS
        )
        if (rule == null) return AutoTaskBookSettings(intervalHours = fallback)
        val action = findRefreshAction(rule.script, bookUrl)
        return AutoTaskBookSettings(
            enabled = rule.enable,
            notifyEnabled = action?.nestedBoolean("notify", "enable") ?: true,
            cacheEnabled = action?.nestedBoolean("cache", "enable") ?: false,
            intervalHours = parseCronHours(rule.cron) ?: fallback
        ).normalized()
    }

    fun buildRule(
        book: Book,
        settings: AutoTaskBookSettings,
        name: String
    ): AutoTaskRule {
        val normalized = settings.normalized()
        val id = AutoTask.bookTaskId(book.bookUrl)
        val existing = AutoTask.get(id)
        return (existing ?: AutoTaskRule(id = id)).copy(
            id = id,
            name = name.trim().take(AutoTaskRuleValidator.MAX_NAME_LENGTH),
            enable = normalized.enabled,
            cron = "0 */${normalized.intervalHours} * * *",
            script = AutoTask.buildBookUpdateScript(
                bookUrl = book.bookUrl,
                notifyEnabled = normalized.notifyEnabled,
                cacheEnabled = normalized.cacheEnabled
            )
        )
    }

    fun parseCronHours(cron: String?): Int? {
        val value = cron?.trim().orEmpty()
        if (value.isEmpty()) return null
        Regex("^0\\s+\\*/(\\d+)\\s+\\*\\s+\\*\\s+\\*$")
            .matchEntire(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.let { return it.coerceIn(AutoTaskBookSettings.MIN_INTERVAL_HOURS, AutoTaskBookSettings.MAX_INTERVAL_HOURS) }
        // Older builds stored the interval as minutes. Preserve the setting
        // when reopening those tasks, rounding up to the smallest hour.
        Regex("^\\*/(\\d+)\\s+\\*\\s+\\*\\s+\\*\\s+\\*$")
            .matchEntire(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.let { minutes ->
                return ((minutes + 59) / 60).coerceIn(
                    AutoTaskBookSettings.MIN_INTERVAL_HOURS,
                    AutoTaskBookSettings.MAX_INTERVAL_HOURS
                )
            }
        return null
    }

    private fun findRefreshAction(script: String?, bookUrl: String): JsonObject? {
        val json = extractJson(script) ?: return null
        val root = runCatching { JsonParser.parseString(json) }.getOrNull()
            ?.takeIf(JsonElement::isJsonObject)
            ?.asJsonObject
            ?: return null
        val candidates = root.get("actions")
            ?.takeUnless(JsonElement::isJsonNull)
            ?.takeIf(JsonElement::isJsonArray)
            ?.asJsonArray
            ?.toList()
            ?: listOf(root)
        return candidates.asSequence()
            .mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
            .firstOrNull { action ->
                action.stringValue("type")?.equals("refreshToc", ignoreCase = true) == true &&
                    action.stringValue("bookUrl") == bookUrl
            }
    }

    private fun extractJson(script: String?): String? {
        val source = script?.let(AutoTaskRule::normalizeScript)?.trim().orEmpty()
        val start = source.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until source.length) {
            val character = source[index]
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
                continue
            }
            when (character) {
                '"' -> quoted = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                    if (depth < 0) return null
                }
            }
        }
        return null
    }

    private fun JsonObject.stringValue(key: String): String? {
        val element = get(key) ?: entrySet().firstOrNull { it.key.equals(key, true) }?.value
        return element?.takeUnless(JsonElement::isJsonNull)
            ?.takeIf(JsonElement::isJsonPrimitive)
            ?.asString
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    private fun JsonObject.nestedBoolean(parent: String, key: String): Boolean? {
        val value = get(parent)
            ?.takeUnless(JsonElement::isJsonNull)
            ?.takeIf(JsonElement::isJsonObject)
            ?.asJsonObject
            ?.let { jsonObject ->
                val element = jsonObject.get(key)
                    ?: jsonObject.entrySet().firstOrNull { it.key.equals(key, true) }?.value
                element?.takeUnless(JsonElement::isJsonNull)
            }
            ?: return null
        return runCatching {
            when {
                value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> value.asBoolean
                value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> value.asInt != 0
                value.isJsonPrimitive -> value.asString.equals("true", true) || value.asString == "1"
                else -> null
            }
        }.getOrNull()
    }
}
