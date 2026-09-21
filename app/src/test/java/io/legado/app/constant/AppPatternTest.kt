package io.legado.app.constant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPatternTest {

    @Test
    fun virtualImageSrcMatchesParagraphBubblePlaceholders() {
        assertTrue(AppPattern.isVirtualImageSrc("dp:5,{}"))
        assertTrue(AppPattern.isVirtualImageSrc("  DP:12,{\"status\":\"normal\"}  "))
        assertTrue(AppPattern.isVirtualImageSrc("bubble://paragraph?num=5&status=normal"))
    }

    @Test
    fun virtualImageSrcDoesNotMatchRealImages() {
        assertFalse(AppPattern.isVirtualImageSrc(null))
        assertFalse(AppPattern.isVirtualImageSrc(""))
        assertFalse(AppPattern.isVirtualImageSrc("https://example.com/image.png"))
        assertFalse(AppPattern.isVirtualImageSrc("data:image/png;base64,AAAA"))
        assertFalse(AppPattern.isVirtualImageSrc("ai-image://abc"))
    }
}
