package io.legado.app.model.localBook.epubcore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubRegexTest {

    @Test
    fun `preserves valid pattern behavior`() {
        val regex = EpubRegex.compile("epub-[0-9]+", RegexOption.IGNORE_CASE)

        assertTrue(regex.matches("EPUB-42"))
    }

    @Test
    fun `invalid optional pattern cannot poison its owner`() {
        val regex = EpubRegex.compile("[")

        assertFalse(regex.containsMatchIn("any EPUB document"))
    }
}
