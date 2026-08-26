package io.legado.app.service.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RelayContentSanitizerTest {
    @Test
    fun removesAllImageTagsFromRelayContent() {
        val content = "正文<img src='dp:18,{&quot;click&quot;:&quot;showCmt(1,2,3,4)&quot;}'>" +
            "中间<IMG class=\"cover\" src=\"https://example.com/a.png\">结尾"

        val sanitized = RelayContentSanitizer.removeImages(content)

        assertEquals("正文中间结尾", sanitized)
        assertFalse(sanitized.contains("<img", ignoreCase = true))
    }

    @Test
    fun keepsOrdinaryTextAndFormatting() {
        val content = "正文<br><b>重点</b>"

        assertEquals(content, RelayContentSanitizer.removeImages(content))
    }
}
