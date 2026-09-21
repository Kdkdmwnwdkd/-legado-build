package io.legado.app.model

import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Parses the pre-Room CacheManager representation without mutating storage. */
object AutoTaskLegacyMigration {
    const val CACHE_KEY = "autoTaskRules"

    fun parse(json: String): Result<List<AutoTaskRule>> = runCatching {
        val rules = GSON.fromJsonArray<AutoTaskRule>(json).getOrThrow()
        normalize(rules)
    }

    internal fun normalize(rules: List<AutoTaskRule>): List<AutoTaskRule> {
        val usedIds = HashSet<String>(rules.size)
        return rules.mapIndexed { index, rule ->
            val rawId: String? = rule.id
            val rawName: String? = rule.name
            val rawScript: String? = rule.script
            val preferredId = rawId?.trim().orEmpty()
            val id = uniqueId(preferredId, index, usedIds)
            rule.copy(
                id = id,
                name = rawName.orEmpty(),
                cron = rule.cron?.trim()?.takeIf(String::isNotEmpty)
                    ?: AutoTaskRule.DEFAULT_CRON,
                script = rawScript.orEmpty(),
                sortOrder = index
            )
        }
    }

    private fun uniqueId(preferredId: String, index: Int, usedIds: MutableSet<String>): String {
        if (preferredId.isNotEmpty() && usedIds.add(preferredId)) return preferredId
        var salt = 0
        while (true) {
            val seed = "legacy-auto-task|$index|$salt|$preferredId"
            val generated = UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString()
            if (usedIds.add(generated)) return generated
            salt++
        }
    }
}
