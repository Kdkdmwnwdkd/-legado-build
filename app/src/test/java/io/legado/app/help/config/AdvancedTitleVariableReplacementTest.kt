package io.legado.app.help.config

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdvancedTitleVariableReplacementTest {

    @Test
    fun replacesBothPlaceholderStylesWithoutRemovingUnknownVariables() {
        val rendered = AdvancedTitleConfig.replaceTemplateVariables(
            """{"a":"${'$'}{title}","b":"{{title}}","c":"${'$'}{unknown}"}""",
            mapOf("title" to "Chapter")
        )

        val root = JsonParser.parseString(rendered).asJsonObject
        assertEquals("Chapter", root["a"].asString)
        assertEquals("Chapter", root["b"].asString)
        assertEquals("${'$'}{unknown}", root["c"].asString)
    }

    @Test
    fun returnsOriginalStringWhenTemplateHasNoVariables() {
        val source = """{"layers":[]}"""

        val rendered = AdvancedTitleConfig.replaceTemplateVariables(
            source,
            mapOf("title" to "Chapter")
        )

        assertSame(source, rendered)
    }

    @Test
    fun detectsRenderableLottieLayers() {
        val json = """{"v":"5.7","w":1080,"h":360,"assets":[{"p":"large"}],"layers":[{}]}"""

        assertTrue(AdvancedTitleConfig.hasRenderableLayers(json))
    }

    @Test
    fun bundledAdvancedTitleHasRenderableLayers() {
        val resource = sequenceOf(
            File("src/main/res/raw/advanced_title_lottie.json"),
            File("app/src/main/res/raw/advanced_title_lottie.json")
        ).firstOrNull(File::isFile)
        checkNotNull(resource) { "Bundled advanced title resource is missing" }

        assertTrue(AdvancedTitleConfig.hasRenderableLayers(resource.readText(Charsets.UTF_8)))
    }

    @Test
    fun rejectsEmptyOrIncompleteLayerDocuments() {
        assertFalse(AdvancedTitleConfig.hasRenderableLayers("""{"layers":[]}"""))
        assertFalse(AdvancedTitleConfig.hasRenderableLayers("""{"layers":[null]}"""))
        assertFalse(AdvancedTitleConfig.hasRenderableLayers("""{"layers":[1]}"""))
        assertFalse(AdvancedTitleConfig.hasRenderableLayers("""{"layers":[{}], BROKEN"""))
        assertFalse(AdvancedTitleConfig.hasRenderableLayers("""{"layers":[{}]} trailing"""))
    }
}
