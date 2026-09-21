package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTaskRuleValidatorTest {

    private fun validRule() = AutoTaskRule(
        id = "task-1",
        name = "Refresh",
        cron = "*/15 * * * *",
        script = "return { actions: [] }"
    )

    @Test
    fun acceptsLegacyScriptWrappersAndRateForms() {
        val rule = validRule().copy(script = "@js: return 1", concurrentRate = "2/1000")
        assertTrue(AutoTaskRuleValidator.isValid(rule))
        assertEquals("return 1", rule.normalizedScript())
        assertTrue(AutoTaskRuleValidator.isValidConcurrentRate("0"))
        assertTrue(AutoTaskRuleValidator.isValidConcurrentRate("8"))
        assertFalse(AutoTaskRuleValidator.isValidConcurrentRate("2/0"))
    }

    @Test
    fun reportsEachInvalidFieldWithoutThrowing() {
        val errors = AutoTaskRuleValidator.validate(
            AutoTaskRule(id = "", name = " ", cron = "61 * * * *", script = "")
        )
        assertEquals(
            setOf(
                AutoTaskRuleField.ID,
                AutoTaskRuleField.NAME,
                AutoTaskRuleField.CRON,
                AutoTaskRuleField.SCRIPT
            ),
            errors.map { it.field }.toSet()
        )
    }
}
