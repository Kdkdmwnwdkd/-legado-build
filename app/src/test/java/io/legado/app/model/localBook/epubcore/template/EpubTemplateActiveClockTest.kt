package io.legado.app.model.localBook.epubcore.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubTemplateActiveClockTest {
    @Test
    fun `a minute in the background does not exhaust an eight second command`() {
        var uptime = 100L
        val clock = EpubTemplateActiveClock { uptime }
        val deadline = clock.now() + 8_000L
        uptime += 2_000L
        clock.pause()
        uptime += 60_000L
        assertEquals(6_000L, deadline - clock.now())
        clock.resume()
        assertEquals(6_000L, deadline - clock.now())
        uptime += 5_999L
        assertFalse(clock.now() >= deadline)
        uptime++
        assertTrue(clock.now() >= deadline)
    }

    @Test
    fun `multiple background trips preserve the remaining layout budget`() {
        var uptime = 0L
        val clock = EpubTemplateActiveClock { uptime }
        val deadline = clock.now() + 45_000L
        repeat(3) {
            uptime += 10_000L
            clock.pause()
            uptime += 120_000L
            clock.resume()
        }
        assertEquals(15_000L, deadline - clock.now())
        uptime += 15_000L
        assertEquals(deadline, clock.now())
    }

    @Test
    fun `repeated lifecycle callbacks do not restart a pause or double subtract it`() {
        var uptime = 20L
        val clock = EpubTemplateActiveClock { uptime }
        clock.resume()
        clock.pause()
        uptime += 100L
        clock.pause()
        uptime += 100L
        clock.resume()
        clock.resume()
        assertEquals(20L, clock.now())
        uptime += 30L
        assertEquals(50L, clock.now())
    }

    @Test
    fun `preparation started while paused receives its whole foreground budget`() {
        var uptime = 100L
        val clock = EpubTemplateActiveClock { uptime }
        clock.pause()
        uptime += 30_000L
        val deadline = clock.now() + 60_000L
        uptime += 90_000L
        clock.resume()
        assertEquals(60_000L, deadline - clock.now())
    }

    @Test
    fun `preview and reader clocks do not suspend one another`() {
        var uptime = 0L
        val reader = EpubTemplateActiveClock { uptime }
        val preview = EpubTemplateActiveClock { uptime }
        reader.pause()
        uptime = 1_500L
        assertEquals(0L, reader.now())
        assertEquals(1_500L, preview.now())
        reader.resume()
        uptime += 500L
        assertEquals(500L, reader.now())
        assertEquals(2_000L, preview.now())
    }
}
