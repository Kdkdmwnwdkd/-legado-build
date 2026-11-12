package com.shimmer.reader.model

import com.shimmer.reader.data.entities.BookSourcePart
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CheckSourceRunTest {

    private fun part(url: String, name: String = url, type: Int = 0) =
        BookSourcePart(
            bookSourceUrl = url,
            bookSourceName = name,
            bookSourceType = type,
        )

    @Before
    fun setUp() {
        CheckSourceRunStore.reset()
    }

    @After
    fun tearDown() {
        CheckSourceRunStore.reset()
    }

    @Test
    fun beginListsEverySelectedSourceAsWaiting() {
        CheckSourceRunStore.begin(
            listOf(part("a"), part("b"), part("a"))
        )

        val run = CheckSourceRunStore.state.value
        assertEquals(CheckRunStatus.RUNNING, run.status)
        // 重复选中同一个源只留一条
        assertEquals(2, run.total)
        assertTrue(run.items.all { it.status == CheckItemStatus.WAITING })
        assertEquals(0, run.done)
        assertEquals(2, run.remaining)
    }

    @Test
    fun passedAndFailedAreCountedSeparately() {
        CheckSourceRunStore.begin(listOf(part("a"), part("b"), part("c")))
        CheckSourceRunStore.markRunning("a", "a", 0)
        CheckSourceRunStore.markPassed("a", 1200)
        CheckSourceRunStore.markRunning("b", "b", 0)
        CheckSourceRunStore.markFailed("b", "源地址不可访问", 800)

        val run = CheckSourceRunStore.state.value
        assertEquals(1, run.passed)
        assertEquals(1, run.failed)
        assertEquals(2, run.done)
        assertEquals(1, run.remaining)
        assertEquals(0.66f, run.progress, 0.01f)
        assertEquals("源地址不可访问", run.items.first { it.origin == "b" }.reason)
    }

    @Test
    fun stageOnlyMovesWhileRunning() {
        CheckSourceRunStore.begin(listOf(part("a")))

        // 还没开跑就打点，不该改动状态
        CheckSourceRunStore.markStage("a", CheckStage.SEARCH)
        assertEquals(CheckItemStatus.WAITING, CheckSourceRunStore.state.value.items[0].status)

        CheckSourceRunStore.markRunning("a", "a", 0)
        CheckSourceRunStore.markStage("a", CheckStage.CONTENT)

        val item = CheckSourceRunStore.state.value.items[0]
        assertEquals(CheckItemStatus.RUNNING, item.status)
        assertEquals(CheckStage.CONTENT, item.stage)
    }

    @Test
    fun finishKeepsPassedAndFailedButDropsRunning() {
        CheckSourceRunStore.begin(listOf(part("a"), part("b")))
        CheckSourceRunStore.markRunning("a", "a", 0)
        CheckSourceRunStore.markPassed("a", 100)
        CheckSourceRunStore.markRunning("b", "b", 0)

        CheckSourceRunStore.finish(cancelled = true, autoDisabled = 0)

        val run = CheckSourceRunStore.state.value
        assertEquals(CheckRunStatus.CANCELLED, run.status)
        assertTrue(run.settled)
        assertEquals(CheckItemStatus.PASSED, run.items.first { it.origin == "a" }.status)
        // 半路被取消的源不该永远停在「校验中」
        assertEquals(CheckItemStatus.CANCELLED, run.items.first { it.origin == "b" }.status)
        assertEquals(0, run.remaining)
    }

    @Test
    fun finishedRunRecordsAutoDisabledCount() {
        CheckSourceRunStore.begin(listOf(part("a")))
        CheckSourceRunStore.markRunning("a", "a", 0)
        CheckSourceRunStore.markFailed("a", "搜索失效", 10)

        CheckSourceRunStore.finish(cancelled = false, autoDisabled = 1)

        val run = CheckSourceRunStore.state.value
        assertEquals(CheckRunStatus.FINISHED, run.status)
        assertEquals(1, run.autoDisabled)
        assertTrue(run.endedAt >= run.startedAt)
    }

    @Test
    fun lateCallbacksAfterFinishAreIgnored() {
        CheckSourceRunStore.begin(listOf(part("a")))
        CheckSourceRunStore.markRunning("a", "a", 0)
        CheckSourceRunStore.markPassed("a", 100)
        CheckSourceRunStore.finish(cancelled = false, autoDisabled = 0)

        // 线程池里迟到的回调不应该把结果改回去
        CheckSourceRunStore.markFailed("a", "网站失效", 200)

        val run = CheckSourceRunStore.state.value
        assertEquals(CheckItemStatus.PASSED, run.items[0].status)
        assertEquals(1, run.passed)
    }

    @Test
    fun finishTwiceKeepsFirstOutcome() {
        CheckSourceRunStore.begin(listOf(part("a")))
        CheckSourceRunStore.finish(cancelled = false, autoDisabled = 0)
        CheckSourceRunStore.finish(cancelled = true, autoDisabled = 0)

        assertEquals(CheckRunStatus.FINISHED, CheckSourceRunStore.state.value.status)
    }

    @Test
    fun emptyRunHasNoProgressAndIsNotRunning() {
        val run = CheckSourceRunStore.state.value
        assertEquals(CheckRunStatus.IDLE, run.status)
        assertEquals(0f, run.progress, 0f)
        assertFalse(run.settled)
    }
}
