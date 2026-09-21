package io.legado.app.help.book

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphRuleProcessorClickIsolationTest {

    @Test
    fun removesOrdinaryClickHandlersFromParagraphRuleImages() {
        val input = """
            <p>new<img src="https://example.com/image.png,{\"click\":\"sourceClick\",\"pclick\":\"rule:1:paragraphClick\",\"width\":\"50%\"}" onclick="domClick()" data-legado-click="legacyClick"></p>
        """.trimIndent()

        val normalized = ParagraphRuleProcessor.normalizeParagraphRuleImageClicks(input)
        assertFalse(normalized.contains("\"click\""))
        assertFalse(normalized.contains("onclick="))
        assertFalse(normalized.contains("data-legado-click="))
        assertTrue(normalized.contains("pclick"))
        assertTrue(normalized.contains("rule:1:paragraphClick"))
        assertTrue(normalized.contains("width"))
    }

    @Test
    fun removesLegacySingleQuotedClickOptionAndKeepsOtherOptions() {
        val input = "<img src=\"https://example.com/image.png,{'click':'sourceClick','style':'text'}\">"

        val normalized = ParagraphRuleProcessor.normalizeParagraphRuleImageClicks(input)
        assertFalse(normalized.contains("click"))
        assertTrue(normalized.contains("style"))
        assertTrue(normalized.contains("{'style':'text'}"))
        assertTrue(normalized.contains("https://example.com/image.png"))
    }

    @Test
    fun doesNotTreatClickTextInsideTheSourcePayloadAsAnHtmlAttribute() {
        val input = "<img src=\"https://example.com/image.png,{\"click\":\"sourceClick\",\"pclick\":\"if (x) onclick=keep\"}\">"

        val normalized = ParagraphRuleProcessor.normalizeParagraphRuleImageClicks(input)

        assertFalse(normalized.contains("sourceClick"))
        assertTrue(normalized.contains("onclick=keep"))
        assertTrue(normalized.contains("pclick"))
    }

    @Test
    fun keepsHtmlEntityEncodingWhenRemovingClickOption() {
        val input = "<img src=\"https://example.com/image.png,&#123;&quot;click&quot;:&quot;sourceClick&quot;,&quot;style&quot;:&quot;text&quot;&#125;\">"

        val normalized = ParagraphRuleProcessor.normalizeParagraphRuleImageClicks(input)

        assertFalse(normalized.contains("sourceClick"))
        assertTrue(normalized.contains("&quot;style&quot;"))
        assertTrue(normalized.contains("&#123;"))
    }

    @Test
    fun handlesCaseInsensitiveImageAndClickAttributes() {
        val input = "<IMG SRC=\"https://example.com/image.png,{\"CLICK\":\"sourceClick\"}\" ONCLICK=\"domClick()\">"

        val normalized = ParagraphRuleProcessor.normalizeParagraphRuleImageClicks(input)

        assertFalse(normalized.contains("sourceClick"))
        assertFalse(normalized.contains("ONCLICK", ignoreCase = true))
    }

    @Test
    fun sourceImagesRemainUntouchedWhileProtected() {
        val original = "<img src=\"https://example.com/image.png,{\"click\":\"sourceClick\"}\">"
        val protected = SpecialContentProtector.protect(original)

        val normalized = ParagraphRuleProcessor.normalizeParagraphRuleImageClicks(protected.content)

        assertTrue(protected.restore(normalized).contains("\"click\":\"sourceClick\""))
    }
}
