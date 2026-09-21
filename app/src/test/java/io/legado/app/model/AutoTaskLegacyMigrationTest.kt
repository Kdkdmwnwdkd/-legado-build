package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTaskLegacyMigrationTest {

    @Test
    fun preservesOrderAndRepairsMissingOrDuplicateIds() {
        val json = """
            [
              {"id":"same","name":"One","cron":"0 * * * *","script":"1"},
              {"id":"same","name":"Two","cron":"5 * * * *","script":"2"},
              {"id":"","name":"Three","script":"3"}
            ]
        """.trimIndent()

        val rules = AutoTaskLegacyMigration.parse(json).getOrThrow()

        assertEquals(listOf(0, 1, 2), rules.map { it.sortOrder })
        assertEquals("same", rules[0].id)
        assertNotEquals(rules[0].id, rules[1].id)
        assertNotEquals(rules[1].id, rules[2].id)
        assertEquals(AutoTaskRule.DEFAULT_CRON, rules[2].cron)
    }

    @Test
    fun malformedLegacyDataFailsWithoutProducingPartialRules() {
        assertTrue(AutoTaskLegacyMigration.parse("not-json").isFailure)
        assertTrue(AutoTaskLegacyMigration.parse("{\"id\":\"one\"}").isFailure)
        assertTrue(AutoTaskLegacyMigration.parse("[null]").isFailure)
    }

    @Test
    fun generatedIdsAreDeterministic() {
        val json = "[{\"id\":\"\",\"name\":\"One\",\"script\":\"1\"}]"
        val first = AutoTaskLegacyMigration.parse(json).getOrThrow().single().id
        val second = AutoTaskLegacyMigration.parse(json).getOrThrow().single().id
        assertEquals(first, second)
    }
}
