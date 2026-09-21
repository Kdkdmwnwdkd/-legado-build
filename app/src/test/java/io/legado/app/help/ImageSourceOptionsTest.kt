package io.legado.app.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageSourceOptionsTest {

    @Test
    fun nestedRequestOptionsDoNotHideTheClickOrDataPayloadBoundary() {
        val parsed = ImageSourceOptions.parse("data:image/png;base64,AAAA,{\"headers\":{\"Referer\":\"https://book/\"},\"click\":\"source()\",\"retry\":2}")!!
        assertEquals("data:image/png;base64,AAAA", parsed.source)
        assertEquals("source()", parsed.click)
        assertEquals("{\"Referer\":\"https://book/\"}", parsed.option("headers"))
        assertEquals("2", parsed.option("retry"))
    }

    @Test
    fun parsesClickAndPresentationOptions() {
        val parsed = ImageSourceOptions.parse(
            "https://example.com/image.png,{\"click\":\"java.toast('ok')\",\"width\":\"50%\",\"style\":\"center\"}"
        )

        assertEquals("https://example.com/image.png", parsed?.source)
        assertEquals("java.toast('ok')", parsed?.click)
        assertEquals("50%", parsed?.width)
        assertEquals("center", parsed?.style)
    }

    @Test
    fun acceptsLegacySingleQuotedAndHtmlEscapedOptions() {
        val parsed = ImageSourceOptions.parse(
            "https://example.com/image.png,&#123;&quot;OnClick&quot;:&quot;java.toast('a,b')&quot;&#125;"
        )

        assertEquals("java.toast('a,b')", parsed?.click)
        assertEquals(
            "java.toast(\"single\")",
            ImageSourceOptions.click("https://example.com/image.png,{'click':'java.toast(\"single\")'}")
        )
    }

    @Test
    fun keepsDataSvgPayloadSeparateFromOptions() {
        val parsed = ImageSourceOptions.parse(
            "data:image/svg+xml;base64,PHN2Zy8+,{\"click\":\"java.toast('svg')\"}"
        )

        assertEquals("data:image/svg+xml;base64,PHN2Zy8+", parsed?.source)
        assertEquals("java.toast('svg')", parsed?.click)
    }

    @Test
    fun acceptsOptionsEscapedForAnHtmlAttribute() {
        val parsed = ImageSourceOptions.parse(
            "https://example.com/image.png,{\\\"click\\\":\\\"java.toast('ok')\\\",\\\"width\\\":\\\"50%\\\"}"
        )

        assertEquals("java.toast('ok')", parsed?.click)
        assertEquals("50%", parsed?.width)
    }

    @Test
    fun preservesEscapedQuotesInsideAnEscapedOptionValue() {
        val parsed = ImageSourceOptions.parse(
            "https://example.com/image.png,{\\\"click\\\":\\\"java.toast(\\\\\\\"ok\\\\\\\")\\\"}"
        )

        assertEquals("java.toast(\"ok\")", parsed?.click)
    }

    @Test
    fun preservesUrlsWithoutValidOptions() {
        val raw = "https://example.com/image.png,not-json"

        assertEquals(raw, ImageSourceOptions.parse(raw)?.source)
        assertNull(ImageSourceOptions.click(raw))
    }

    @Test
    fun preservesImagePayloadAndUsesTheLastValidOptionSuffix() {
        val svg = "data:image/svg+xml,<svg data-note=',{not-options}'>&amp;</svg>"
        val raw = "$svg,{&quot;click&quot;:&quot;java.toast('ok')&quot;}"

        val parsed = ImageSourceOptions.parse(raw)

        assertEquals(svg, parsed?.source)
        assertEquals("java.toast('ok')", parsed?.click)
        assertEquals(
            "https://example.com/image.svg?label=a&amp;b",
            ImageSourceOptions.parse("https://example.com/image.svg?label=a&amp;b")?.source
        )
    }
}
