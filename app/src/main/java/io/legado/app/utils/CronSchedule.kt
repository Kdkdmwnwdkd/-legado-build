package io.legado.app.utils

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Small, deterministic five-field cron implementation used by AutoTask.
 *
 * Supported syntax is intentionally limited to the syntax accepted by the
 * old task editor: '*', '?', lists, inclusive ranges and positive steps.
 * Fields are minute, hour, day-of-month, month and day-of-week. When both
 * day fields are restricted they use the traditional cron OR rule.
 */
class CronSchedule private constructor(
    private val minutes: BooleanArray,
    private val hours: BooleanArray,
    private val daysOfMonth: BooleanArray,
    private val months: BooleanArray,
    private val daysOfWeek: BooleanArray,
    private val domAny: Boolean,
    private val dowAny: Boolean
) {

    /** Returns the first matching minute strictly after [fromEpochMs]. */
    fun nextTimeAfter(fromEpochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long? {
        var time = Instant.ofEpochMilli(fromEpochMs)
            .atZone(zoneId)
            .withSecond(0)
            .withNano(0)
            .plusMinutes(1)
        repeat(MAX_SEARCH_MINUTES) {
            if (matches(time)) return time.toInstant().toEpochMilli()
            time = time.plusMinutes(1)
        }
        return null
    }

    private fun matches(time: ZonedDateTime): Boolean {
        if (!months[time.monthValue] || !hours[time.hour] || !minutes[time.minute]) return false
        val domMatch = daysOfMonth[time.dayOfMonth]
        val dowMatch = daysOfWeek[time.dayOfWeek.value % 7]
        val dayMatch = when {
            domAny && dowAny -> true
            domAny -> dowMatch
            dowAny -> domMatch
            else -> domMatch || dowMatch
        }
        return dayMatch
    }

    companion object {
        private const val MAX_SEARCH_MINUTES = 366 * 24 * 60

        fun parse(expression: String): CronSchedule? {
            val parts = expression.trim().split(Regex("\\s+")).filter(String::isNotBlank)
            if (parts.size != 5) return null

            val minute = parseField(parts[0], 0, 59) ?: return null
            val hour = parseField(parts[1], 0, 23) ?: return null
            val dom = parseField(parts[2], 1, 31) ?: return null
            val month = parseField(parts[3], 1, 12) ?: return null
            val dow = parseField(parts[4], 0, 7, mapSundayToZero = true) ?: return null
            return CronSchedule(
                minutes = minute.allowed,
                hours = hour.allowed,
                daysOfMonth = dom.allowed,
                months = month.allowed,
                daysOfWeek = dow.allowed,
                domAny = dom.any,
                dowAny = dow.any
            )
        }

        private data class ParsedField(val allowed: BooleanArray, val any: Boolean)

        private fun parseField(
            field: String,
            min: Int,
            max: Int,
            mapSundayToZero: Boolean = false
        ): ParsedField? {
            val text = field.trim()
            if (text.isEmpty()) return null
            val allowed = BooleanArray(max + 1)
            var containsWildcard = false

            for (part in text.split(',')) {
                if (part.isBlank()) return null
                val slash = part.indexOf('/')
                if (slash >= 0 && part.indexOf('/', slash + 1) >= 0) return null
                val base = if (slash >= 0) part.substring(0, slash) else part
                val stepText = if (slash >= 0) part.substring(slash + 1) else null
                val step = stepText?.toIntOrNull() ?: 1
                if (step <= 0 || (slash >= 0 && stepText.isNullOrBlank())) return null

                val range: IntRange = when {
                    base == "*" || base == "?" -> {
                        containsWildcard = true
                        min..max
                    }
                    base.contains('-') -> {
                        val bounds = base.split('-', limit = 2)
                        if (bounds.size != 2) return null
                        val start = bounds[0].toIntOrNull() ?: return null
                        val end = bounds[1].toIntOrNull() ?: return null
                        if (start !in min..max || end !in min..max || start > end) return null
                        start..end
                    }
                    else -> {
                        val value = base.toIntOrNull() ?: return null
                        if (value !in min..max) return null
                        if (slash >= 0) value..max else value..value
                    }
                }

                for (value in range step step) {
                    val normalized = if (mapSundayToZero && value == 7) 0 else value
                    if (normalized !in min..max) return null
                    allowed[normalized] = true
                }
            }
            if (allowed.none { it }) return null
            return ParsedField(allowed, containsWildcard)
        }
    }
}
