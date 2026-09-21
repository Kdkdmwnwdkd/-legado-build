package io.legado.app.constant

import org.junit.Assert.assertEquals
import org.junit.Test

class PageAnimationSpeedTest {

    @Test
    fun `speed presets keep the standard duration aligned with the text reader`() {
        assertEquals(180, PageAnimationSpeed.EXTREME.durationMillis)
        assertEquals(300, PageAnimationSpeed.STANDARD.durationMillis)
        assertEquals(420, PageAnimationSpeed.RELAXED.durationMillis)
        assertEquals(560, PageAnimationSpeed.ELEGANT.durationMillis)
    }

    @Test
    fun `unknown preference values normalize to standard`() {
        assertEquals(PageAnimationSpeed.STANDARD, PageAnimationSpeed.fromPreference(-1))
        assertEquals(PageAnimationSpeed.STANDARD, PageAnimationSpeed.fromPreference(99))
        assertEquals(PageAnimationSpeed.RELAXED, PageAnimationSpeed.fromPreference(2))
    }
}
