package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTaskBookConfigTest {

    @Test
    fun parsesCurrentAndLegacyCronFormats() {
        assertEquals(6, AutoTaskBookConfig.parseCronHours("0 */6 * * *"))
        assertEquals(1, AutoTaskBookConfig.parseCronHours("*/30 * * * *"))
        assertEquals(2, AutoTaskBookConfig.parseCronHours("*/120 * * * *"))
        assertEquals(
            AutoTaskBookSettings.MAX_INTERVAL_HOURS,
            AutoTaskBookConfig.parseCronHours("0 */999999 * * *")
        )
    }

    @Test
    fun rejectsInvalidCronAndUsesBoundedFallback() {
        assertNull(AutoTaskBookConfig.parseCronHours(null))
        assertNull(AutoTaskBookConfig.parseCronHours(""))
        assertNull(AutoTaskBookConfig.parseCronHours("0 */0 * * *"))
        assertNull(AutoTaskBookConfig.parseCronHours("0 */6 * *"))

        val settings = AutoTaskBookConfig.fromRule(
            rule = null,
            bookUrl = "book",
            fallbackIntervalHours = 0
        )
        assertEquals(AutoTaskBookSettings.MIN_INTERVAL_HOURS, settings.intervalHours)
    }

    @Test
    fun readsNotificationAndCacheOptionsFromWrappedScript() {
        val rule = AutoTaskRule(
            id = "book-task",
            name = "Book",
            enable = false,
            cron = "0 */4 * * *",
            script = "<js>{\"actions\":[{" +
                "\"type\":\"refreshToc\",\"bookUrl\":\"book-url\"," +
                "\"notify\":{\"enable\":false},\"cache\":{\"enable\":1}}]}</js>"
        )

        val settings = AutoTaskBookConfig.fromRule(rule, "book-url")

        assertTrue(!settings.enabled)
        assertTrue(!settings.notifyEnabled)
        assertTrue(settings.cacheEnabled)
        assertEquals(4, settings.intervalHours)
    }

    @Test
    fun fallsBackWhenScriptTargetsAnotherBook() {
        val rule = AutoTaskRule(
            id = "book-task",
            name = "Book",
            cron = "not-a-cron",
            script = AutoTask.buildBookUpdateScript("other-book", notifyEnabled = false)
        )

        val settings = AutoTaskBookConfig.fromRule(
            rule,
            bookUrl = "book-url",
            fallbackIntervalHours = 7
        )

        assertTrue(settings.notifyEnabled)
        assertTrue(!settings.cacheEnabled)
        assertEquals(7, settings.intervalHours)
    }

    @Test
    fun bookTaskIdsAreStableAndScoped() {
        val first = AutoTask.bookTaskId("book-url")
        val second = AutoTask.bookTaskId("book-url")
        val other = AutoTask.bookTaskId("other-book")

        assertEquals(first, second)
        assertNotEquals(first, other)
        assertTrue(first.startsWith("book_update:"))
    }
}
