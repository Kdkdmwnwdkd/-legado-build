package io.legado.app.model

import io.legado.app.utils.CronSchedule
import java.time.ZoneId

data class AutoTaskOccurrence(
    val ruleId: String,
    val atEpochMs: Long
)

/** Scheduling decisions shared by the service and the management screen. */
object AutoTaskSchedule {
    const val DEFAULT_FIRST_RUN_GRACE_MS = 5 * 60_000L

    /**
     * Returns the next occurrence after the rule's last attempted run. A
     * future lastRunAt is treated as stale clock data so a clock rollback
     * cannot postpone a task indefinitely.
     */
    fun nextFor(
        rule: AutoTaskRule,
        nowEpochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        firstRunGraceMs: Long = DEFAULT_FIRST_RUN_GRACE_MS
    ): Long? {
        val expression = rule.cron?.trim().orEmpty()
        val schedule = CronSchedule.parse(expression) ?: return null
        val base = when {
            rule.lastRunAt in 1..nowEpochMs -> rule.lastRunAt
            else -> nowEpochMs - firstRunGraceMs.coerceAtLeast(0L)
        }
        return schedule.nextTimeAfter(base, zoneId)
    }

    fun nextEnabled(
        rules: Iterable<AutoTaskRule>,
        nowEpochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        firstRunGraceMs: Long = DEFAULT_FIRST_RUN_GRACE_MS
    ): AutoTaskOccurrence? {
        return rules.asSequence()
            .filter { it.enable }
            .mapNotNull { rule ->
                nextFor(rule, nowEpochMs, zoneId, firstRunGraceMs)
                    ?.let { AutoTaskOccurrence(rule.id, it) }
            }
            .minWithOrNull(compareBy<AutoTaskOccurrence> { it.atEpochMs }.thenBy { it.ruleId })
    }
}
