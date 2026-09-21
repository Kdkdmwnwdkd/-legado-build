package io.legado.app.help.config

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdvancedTitleBuiltinAssetTest {

    @Test
    fun builtinContainsChapterTextAndAnimatedDecoration() {
        val source = sequenceOf(
            File("src/main/res/raw/advanced_title_lottie.json"),
            File("app/src/main/res/raw/advanced_title_lottie.json")
        ).firstOrNull { it.isFile } ?: error("Built-in advanced title asset not found")
        val root = JsonParser.parseString(source.readText()).asJsonObject
        val layers = root["layers"].asJsonArray
        val texts = layers.map { layer ->
            layer.asJsonObject["t"].asJsonObject["d"].asJsonObject["k"].asJsonArray
                .first().asJsonObject["s"].asJsonObject["t"].asString
        }

        assertEquals(720, root["w"].asInt)
        assertEquals(210, root["h"].asInt)
        assertTrue(layers.size() >= 3)
        assertTrue("${'$'}{s1}" in texts)
        assertTrue("${'$'}{s2}" in texts)
        assertTrue(
            layers.any { layer ->
                layer.asJsonObject["ks"].asJsonObject.entrySet().any { (_, value) ->
                    value.isJsonObject && value.asJsonObject["a"]?.asInt == 1
                }
            }
        )
    }
}
