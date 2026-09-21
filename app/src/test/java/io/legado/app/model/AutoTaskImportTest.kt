package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTaskImportTest {


    @Test
    fun parsesObjectArrayAndEnvelope() {
        val objectRule = AutoTaskImport.parse(
            """{"id":"one","name":" One ","script":"@js: 1"}"""
        ).getOrThrow().single()
        assertEquals("One", objectRule.name)
        assertEquals("1", objectRule.script)
        assertEquals(AutoTaskRule.DEFAULT_CRON, objectRule.cron)

        val array = AutoTaskImport.parse(
            """[{"id":"a","name":"A","script":"1"},{"id":"b","name":"B","script":"2"}]"""
        ).getOrThrow()
        assertEquals(2, array.size)

        val envelope = AutoTaskImport.parse(
            """{"tasks":[{"id":"e","name":"E","script":"3"}]}"""
        ).getOrThrow()
        assertEquals("e", envelope.single().id)
    }

    @Test
    fun repairsBlankAndDuplicateIdsDeterministically() {
        val raw = """[
            {"id":"same","name":"A","script":"1"},
            {"id":"same","name":"B","script":"2"},
            {"id":"","name":"C","script":"3"}
        ]"""
        val first = AutoTaskImport.parse(raw).getOrThrow()
        val second = AutoTaskImport.parse(raw).getOrThrow()
        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(3, first.map { it.id }.toSet().size)
        assertNotEquals(first[0].id, first[1].id)
    }

    @Test
    fun comparisonIgnoresLocalRuntimeAndOrdering() {
        val imported = AutoTaskRule(
            id = "task",
            name = "Task",
            cron = "*/5 * * * *",
            script = "1",
            sortOrder = 4
        )
        val local = imported.copy(
            sortOrder = 0,
            lastRunAt = 99L,
            lastResult = "old",
            lastError = "old error",
            lastLog = "old log"
        )
        val entry = AutoTaskImport.compare(listOf(imported), listOf(local)).single()
        assertEquals(AutoTaskImport.State.EXISTING, entry.state)
    }

    @Test
    fun invalidTaskAndUnsupportedRootFail() {
        assertTrue(
            AutoTaskImport.parse("{\"id\":\"x\",\"name\":\"x\",\"script\":\"\"}").isFailure
        )
        assertTrue(AutoTaskImport.parse("true").isFailure)
        assertTrue(AutoTaskImport.parse("{\"tasks\":{}}").isFailure)
    }
}
