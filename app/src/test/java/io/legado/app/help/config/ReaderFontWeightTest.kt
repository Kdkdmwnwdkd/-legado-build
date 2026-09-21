package io.legado.app.help.config

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderFontWeightTest {

    @Test
    fun normalizeMapsLegacyValues() {
        assertEquals(400, ReaderFontWeight.normalize(0))
        assertEquals(700, ReaderFontWeight.normalize(1))
        assertEquals(300, ReaderFontWeight.normalize(2))
    }

    @Test
    fun normalizeClampsContinuousValues() {
        assertEquals(100, ReaderFontWeight.normalize(50))
        assertEquals(450, ReaderFontWeight.normalize(450))
        assertEquals(900, ReaderFontWeight.normalize(950))
    }

    @Test
    fun titleWeightStaysHeavierWithinSupportedRange() {
        assertEquals(400, ReaderFontWeight.titleWeight(100))
        assertEquals(700, ReaderFontWeight.titleWeight(400))
        assertEquals(900, ReaderFontWeight.titleWeight(700))
    }
}
