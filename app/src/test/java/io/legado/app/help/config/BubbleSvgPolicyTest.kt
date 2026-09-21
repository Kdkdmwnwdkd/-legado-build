package io.legado.app.help.config

import org.junit.Assert.assertEquals
import org.junit.Test
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

class BubbleSvgPolicyTest {

    @Test
    fun allowsLocalFragmentReferences() {
        BubbleSvgPolicy.validate(
            """<svg><defs><linearGradient id="g"/></defs><path fill="url(#g)"/><use href="#g"/></svg>"""
        )
    }

    @Test
    fun allowsPackageAliasesAndRelativeAssets() {
        val svg = """<svg><image href="asset://background"/><image href="assets/icon.webp"/></svg>"""

        BubbleSvgPolicy.validate(svg)

        assertEquals(
            setOf("asset://background", "assets/icon.webp"),
            BubbleSvgPolicy.packageReferences(svg)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNetworkReferences() {
        BubbleSvgPolicy.validate("""<svg><image href="https://example.com/a.png"/></svg>""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsScriptsAndEventHandlers() {
        BubbleSvgPolicy.validate("""<svg onload="run()"><script>run()</script></svg>""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsXmlEntities() {
        BubbleSvgPolicy.validate("""<!DOCTYPE svg [<!ENTITY x SYSTEM "file:///etc/passwd">]><svg>&x;</svg>""")
    }

    @Test
    fun escapedLabelsRoundTripAsTextAndAttributesWithXmlMetacharacters() {
        val labels = listOf(
            """5 < 6 & 7 > 2 "引号" '单引号'""",
            """</text><image href="asset://other"/><text>12""",
            "9+🌅"
        )
        for (label in labels) {
            val document = svgWithLabel(label)
            val text = document.getElementsByTagName("text").item(0)
            assertEquals(label, text.textContent)
            assertEquals(label, text.attributes.getNamedItem("aria-label").nodeValue)
            assertEquals(1, document.getElementsByTagName("text").length)
            assertEquals(0, document.getElementsByTagName("image").length)
        }
    }

    @Test
    fun entityLookingLabelsRemainLiteralAfterOneXmlParse() {
        for (label in listOf("&lt;3 &amp;", "&#65; &#x1F305; &unknown;", "&amp;lt;12", "")) {
            val text = svgWithLabel(label).getElementsByTagName("text").item(0)
            assertEquals(label, text.textContent)
            assertEquals(label, text.attributes.getNamedItem("aria-label").nodeValue)
        }
    }

    private fun svgWithLabel(label: String): org.w3c.dom.Document {
        val escaped = BubbleSvgPolicy.escapeText(label)
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"><text aria-label="$escaped">$escaped</text></svg>"""
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(svg)))
    }
}
