package io.legado.app.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTaskExecutionTest {

    private fun rule(id: String = "task") = AutoTaskRule(
        id = id,
        name = id,
        cron = "* * * * *",
        script = "return 1"
    )

    @Test
    fun serializesConcurrentTasks() = runBlocking {
        var active = 0
        var maxActive = 0
        val coordinator = AutoTaskExecutionCoordinator(
            scriptExecutor = AutoTaskScriptExecutor {
                active++
                maxActive = maxOf(maxActive, active)
                delay(25)
                active--
                "ok"
            },
            actionHandler = AutoTaskActionHandler { _, _, _ ->
                AutoTaskActionResult(handled = true)
            }
        )

        val first = async { coordinator.run(rule("a")) }
        val second = async { coordinator.run(rule("b")) }
        assertEquals(AutoTaskRunStatus.SUCCESS, first.await().status)
        assertEquals(AutoTaskRunStatus.SUCCESS, second.await().status)
        assertEquals(1, maxActive)
    }

    @Test
    fun distinguishesTimeoutFromParentCancellation() = runBlocking {
        val coordinator = AutoTaskExecutionCoordinator(
            scriptExecutor = AutoTaskScriptExecutor {
                delay(1_200)
                "late"
            },
            actionHandler = AutoTaskActionHandler { _, _, _ -> AutoTaskActionResult(true) }
        )

        val timedOut = coordinator.run(rule(), timeoutMs = 1_000L)
        assertEquals(AutoTaskRunStatus.TIMED_OUT, timedOut.status)

        val job = async { coordinator.run(rule("cancel"), timeoutMs = 10_000L) }
        delay(20)
        job.cancel()
        try {
            job.await()
            throw AssertionError("cancellation should propagate")
        } catch (_: CancellationException) {
            assertTrue(true)
        }
    }

    @Test
    fun rejectsInvalidRulesBeforeCallingScript() = runBlocking {
        var called = false
        val coordinator = AutoTaskExecutionCoordinator(
            scriptExecutor = AutoTaskScriptExecutor {
                called = true
                "unexpected"
            },
            actionHandler = AutoTaskActionHandler { _, _, _ -> AutoTaskActionResult(true) }
        )
        val result = coordinator.run(rule().copy(cron = "invalid"))
        assertEquals(AutoTaskRunStatus.INVALID, result.status)
        assertTrue(!called)
    }

    @Test
    fun reportsUnhandledScriptAsNoActionAndBoundsDetails() = runBlocking {
        val coordinator = AutoTaskExecutionCoordinator(
            scriptExecutor = AutoTaskScriptExecutor { "raw" },
            actionHandler = AutoTaskActionHandler { _, _, _ ->
                AutoTaskActionResult(handled = false, detail = "x".repeat(10_000))
            }
        )
        val result = withTimeout(2_000L) { coordinator.run(rule()) }
        assertEquals(AutoTaskRunStatus.NO_ACTION, result.status)
        assertEquals(4_000, result.detail?.length)
    }
}
