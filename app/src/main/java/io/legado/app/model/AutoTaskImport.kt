package io.legado.app.model

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.utils.GSON
import java.io.IOException

/**
 * Format and comparison contract shared by local, URL and clipboard imports.
 * Keeping this independent from Android makes malformed input testable before
 * any Room mutation or UI callback is involved.
 */
object AutoTaskImport {
    const val MAX_IMPORT_BYTES = 4L * 1024L * 1024L
    const val MAX_TASKS = 256

    enum class State {
        NEW,
        UPDATE,
        EXISTING
    }

    data class Entry(
        val rule: AutoTaskRule,
        val existing: AutoTaskRule?,
        val state: State
    )

    /** Stable, configuration-only export shape; runtime logs never leave the device. */
    private data class ExportRule(
        val id: String,
        val name: String,
        val enable: Boolean,
        val cron: String?,
        val loginUrl: String?,
        val loginUi: String?,
        val loginCheckJs: String?,
        val comment: String?,
        val script: String,
        val header: String?,
        val jsLib: String?,
        val concurrentRate: String?,
        val enabledCookieJar: Boolean
    )

    fun parse(raw: String): Result<List<AutoTaskRule>> = runCatching {
        val text = raw.trim()
        require(text.isNotEmpty()) { "Auto task import is empty" }
        require(utf8ByteCount(text) <= MAX_IMPORT_BYTES) {
            "Auto task import exceeds the 4 MiB limit"
        }

        val root = try {
            JsonParser.parseString(text)
        } catch (error: Throwable) {
            throw IOException("Invalid auto task JSON", error)
        }
        val elements = extractElements(root)
        require(elements.isNotEmpty()) { "Auto task import is empty" }
        require(elements.size <= MAX_TASKS) {
            "Auto task import contains too many tasks (max $MAX_TASKS)"
        }

        val parsed = elements.mapIndexed { index, element ->
            if (!element.isJsonObject) {
                throw IOException("Auto task entry ${index + 1} must be an object")
            }
            try {
                GSON.fromJson(element, AutoTaskRule::class.java)
                    ?: throw IOException("Auto task entry ${index + 1} is empty")
            } catch (error: IOException) {
                throw error
            } catch (error: Throwable) {
                throw IOException("Invalid auto task entry ${index + 1}", error)
            }
        }

        // Reuse the legacy deterministic ID repair so an import can never
        // silently overwrite two entries that carried the same or blank ID.
        AutoTaskLegacyMigration.normalize(parsed)
            .mapIndexed { index, rule ->
                val source = elements[index].asJsonObject
                normalizeRule(index, rule, source)
            }
            .also { rules ->
                rules.forEachIndexed { index, rule ->
                    val errors = AutoTaskRuleValidator.validate(rule)
                    if (errors.isNotEmpty()) {
                        throw IOException(
                            "Invalid auto task entry ${index + 1}: " +
                                errors.joinToString { "${it.field}:${it.code}" }
                        )
                    }
                    validateTextSafety(index, rule)
                }
            }
    }

    fun compare(
        imported: List<AutoTaskRule>,
        local: List<AutoTaskRule>
    ): List<Entry> {
        val localById = local.associateBy { it.id.trim() }
        return imported.map { rule ->
            val existing = localById[rule.id.trim()]
            val state = when {
                existing == null -> State.NEW
                comparable(rule) != comparable(existing) -> State.UPDATE
                else -> State.EXISTING
            }
            Entry(rule, existing, state)
        }
    }

    fun exportJson(rules: Collection<AutoTaskRule>): String =
        GSON.toJson(rules.map { rule ->
            ExportRule(
                id = rule.id,
                name = rule.name,
                enable = rule.enable,
                cron = rule.cron,
                loginUrl = rule.loginUrl,
                loginUi = rule.loginUi,
                loginCheckJs = rule.loginCheckJs,
                comment = rule.comment,
                script = rule.normalizedScript(),
                header = rule.header,
                jsLib = rule.jsLib,
                concurrentRate = rule.concurrentRate,
                enabledCookieJar = rule.enabledCookieJar
            )
        })

    private fun extractElements(root: JsonElement): List<JsonElement> {
        return when {
            root.isJsonArray -> root.asJsonArray.toList()
            root.isJsonObject -> {
                val objectRoot = root.asJsonObject
                val envelopeKey = listOf("tasks", "rules")
                    .firstOrNull { objectRoot.has(it) }
                if (envelopeKey == null) {
                    listOf(root)
                } else {
                    val payload = objectRoot.get(envelopeKey)
                    if (payload == null || !payload.isJsonArray) {
                        throw IOException("Auto task package '$envelopeKey' must be an array")
                    }
                    payload.asJsonArray.toList()
                }
            }
            else -> throw IOException("Auto task import must be a JSON object or array")
        }
    }

    private fun normalizeRule(
        index: Int,
        rule: AutoTaskRule,
        source: JsonObject
    ): AutoTaskRule {
        return rule.copy(
            id = rule.id.trim(),
            name = rule.name.trim(),
            enable = source.get("enable")?.takeUnless { it.isJsonNull }?.asBoolean
                ?: source.get("enabled")?.takeUnless { it.isJsonNull }?.asBoolean
                ?: true,
            cron = rule.cron?.trim()?.takeIf(String::isNotEmpty) ?: AutoTaskRule.DEFAULT_CRON,
            comment = rule.comment?.trim()?.takeIf(String::isNotEmpty),
            script = rule.normalizedScript(),
            header = rule.header?.trim()?.takeIf(String::isNotEmpty),
            jsLib = rule.jsLib?.trim()?.takeIf(String::isNotEmpty),
            concurrentRate = rule.concurrentRate?.trim()?.takeIf(String::isNotEmpty),
            loginUrl = rule.loginUrl?.trim()?.takeIf(String::isNotEmpty),
            loginUi = rule.loginUi?.trim()?.takeIf(String::isNotEmpty),
            loginCheckJs = rule.loginCheckJs?.trim()?.takeIf(String::isNotEmpty),
            enabledCookieJar = source.get("enabledCookieJar")?.takeUnless { it.isJsonNull }
                ?.asBoolean ?: true,
            lastRunAt = 0L,
            lastResult = null,
            lastError = null,
            lastLog = null,
            sortOrder = index
        )
    }

    /** Runtime fields and ordering are local state, not imported configuration. */
    private fun comparable(rule: AutoTaskRule): AutoTaskRule = rule.copy(
        sortOrder = 0,
        lastRunAt = 0L,
        lastResult = null,
        lastError = null,
        lastLog = null
    )

    private fun validateTextSafety(index: Int, rule: AutoTaskRule) {
        val values = listOf(
            rule.id,
            rule.name,
            rule.cron,
            rule.comment,
            rule.script,
            rule.header,
            rule.jsLib,
            rule.concurrentRate,
            rule.loginUrl,
            rule.loginUi,
            rule.loginCheckJs
        )
        if (values.any { value -> value?.any { it == '\u0000' } == true }) {
            throw IOException("Auto task entry ${index + 1} contains an invalid NUL character")
        }
    }

    private fun utf8ByteCount(value: String): Long {
        var count = 0L
        var index = 0
        while (index < value.length) {
            val character = value[index]
            count += when {
                character.code < 0x80 -> 1
                character.code < 0x800 -> 2
                character.isHighSurrogate() &&
                    index + 1 < value.length &&
                    value[index + 1].isLowSurrogate() -> {
                    index++
                    4
                }
                else -> 3
            }
            if (count > MAX_IMPORT_BYTES) return count
            index++
        }
        return count
    }
}
