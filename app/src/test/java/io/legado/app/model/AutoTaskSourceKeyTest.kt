package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoTaskSourceKeyTest {

    @Test
    fun normalizesTaskIdsAndTemporarySourceKeys() {
        assertEquals("task-1", AutoTask.normalizeSourceTaskId(" task-1 "))
        assertEquals("task-1", AutoTask.normalizeSourceTaskId("auto_task:task-1"))
        assertEquals("task-1", AutoTask.normalizeSourceTaskId("AutoTask:auto_task:task-1"))
        assertEquals("other:auto_task:task-1", AutoTask.normalizeSourceTaskId("other:auto_task:task-1"))
    }

    @Test
    fun rejectsBlankSourceKeys() {
        assertNull(AutoTask.normalizeSourceTaskId(null))
        assertNull(AutoTask.normalizeSourceTaskId("  "))
        assertNull(AutoTask.normalizeSourceTaskId("auto_task:"))
    }

    @Test
    fun keepsOriginalCandidatesForLegacyPrefixedTaskIds() {
        assertEquals(
            listOf("auto_task:auto_task:task-1", "auto_task:task-1", "task-1"),
            AutoTask.sourceTaskIdCandidates("auto_task:auto_task:task-1")
        )
        assertEquals(
            "task-1",
            AutoTask.resolveTaskId(
                sourceType = "autoTask",
                key = "auto_task:task-1"
            )
        )
    }

    @Test
    fun resolvesLegacyAndDedicatedIntentValues() {
        assertEquals(
            "task-1",
            AutoTask.resolveTaskId(
                sourceType = "auto-task",
                key = "task-1"
            )
        )
        assertEquals(
            "task-2",
            AutoTask.resolveTaskId(
                sourceType = null,
                key = null,
                taskId = " task-2 "
            )
        )
        assertEquals(
            "task-3",
            AutoTask.resolveTaskId(
                sourceType = null,
                key = "auto_task:task-3"
            )
        )
    }
}
