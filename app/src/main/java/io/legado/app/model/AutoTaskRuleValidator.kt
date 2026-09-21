package io.legado.app.model

import io.legado.app.utils.CronSchedule

/** Stable field identifiers used by both Compose and non-UI callers. */
enum class AutoTaskRuleField {
    ID,
    NAME,
    CRON,
    SCRIPT,
    CONCURRENT_RATE
}

enum class AutoTaskRuleErrorCode {
    REQUIRED,
    TOO_LONG,
    INVALID_CRON,
    INVALID_CONCURRENT_RATE
}

data class AutoTaskRuleValidationError(
    val field: AutoTaskRuleField,
    val code: AutoTaskRuleErrorCode
)

/** Pure validation rules. Messages are deliberately left to the caller/UI. */
object AutoTaskRuleValidator {
    const val MAX_ID_LENGTH = 128
    const val MAX_NAME_LENGTH = 200
    const val MAX_SCRIPT_LENGTH = 1_048_576
    const val MAX_CONCURRENT_RATE_LENGTH = 32

    fun validate(rule: AutoTaskRule): List<AutoTaskRuleValidationError> = buildList {
        val id = rule.id.trim()
        if (id.isEmpty()) add(error(AutoTaskRuleField.ID, AutoTaskRuleErrorCode.REQUIRED))
        else if (id.length > MAX_ID_LENGTH) add(error(AutoTaskRuleField.ID, AutoTaskRuleErrorCode.TOO_LONG))

        val name = rule.name.trim()
        if (name.isEmpty()) add(error(AutoTaskRuleField.NAME, AutoTaskRuleErrorCode.REQUIRED))
        else if (name.length > MAX_NAME_LENGTH) add(error(AutoTaskRuleField.NAME, AutoTaskRuleErrorCode.TOO_LONG))

        val cron = rule.cron?.trim().orEmpty()
        if (cron.isEmpty()) {
            add(error(AutoTaskRuleField.CRON, AutoTaskRuleErrorCode.REQUIRED))
        } else if (CronSchedule.parse(cron) == null) {
            add(error(AutoTaskRuleField.CRON, AutoTaskRuleErrorCode.INVALID_CRON))
        }

        val script = rule.normalizedScript()
        if (script.isEmpty()) add(error(AutoTaskRuleField.SCRIPT, AutoTaskRuleErrorCode.REQUIRED))
        else if (script.length > MAX_SCRIPT_LENGTH) add(error(AutoTaskRuleField.SCRIPT, AutoTaskRuleErrorCode.TOO_LONG))

        val rate = rule.concurrentRate?.trim().orEmpty()
        if (rate.length > MAX_CONCURRENT_RATE_LENGTH) {
            add(error(AutoTaskRuleField.CONCURRENT_RATE, AutoTaskRuleErrorCode.TOO_LONG))
        } else if (!isValidConcurrentRate(rate)) {
            add(error(AutoTaskRuleField.CONCURRENT_RATE, AutoTaskRuleErrorCode.INVALID_CONCURRENT_RATE))
        }
    }

    fun isValid(rule: AutoTaskRule): Boolean = validate(rule).isEmpty()

    /**
     * Matches the existing source limiter syntax: 0 disables the limit,
     * a positive integer is a count, and count/interval is a window.
     */
    fun isValidConcurrentRate(value: String): Boolean {
        val text = value.trim()
        if (text.isEmpty() || text == "0") return true
        val slash = text.indexOf('/')
        if (slash < 0) return text.toIntOrNull()?.let { it > 0 } == true
        if (slash == 0 || slash == text.lastIndex || text.indexOf('/', slash + 1) >= 0) return false
        val count = text.substring(0, slash).toIntOrNull()
        val interval = text.substring(slash + 1).toIntOrNull()
        return count != null && interval != null && count > 0 && interval > 0
    }

    private fun error(
        field: AutoTaskRuleField,
        code: AutoTaskRuleErrorCode
    ) = AutoTaskRuleValidationError(field, code)
}
