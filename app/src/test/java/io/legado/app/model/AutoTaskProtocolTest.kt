package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject

class AutoTaskProtocolTest {

    @Test
    fun parsesSingleActionObject() {
        val result = AutoTaskActionParser.parse(
            "{\"type\":\"notify\",\"title\":\"hello\"}"
        )

        assertNotNull(result)
        assertEquals("notify", result!!.single()["type"])
        assertEquals("hello", result.single()["title"])
    }

    @Test
    fun convertsRhinoObjectAndArrayWithoutHtmlUnitRuntime() {
        val context = Context.enter()
        try {
            val action = NativeObject()
            action.put("type", action, "notify")
            action.put("title", action, "from-rhino")
            val root = NativeObject()
            root.put("actions", root, NativeArray(arrayOf(action)))

            val result = AutoTaskActionParser.parse(root)

            assertNotNull(result)
            assertEquals(1, result!!.size)
            assertEquals("notify", result.single()["type"])
            assertEquals("from-rhino", result.single()["title"])
        } finally {
            Context.exit()
        }
    }

    @Test
    fun rejectsMalformedOrUnsupportedActionShape() {
        assertNull(AutoTaskActionParser.parse("not-json"))
        assertNull(AutoTaskActionParser.parse("{\"message\":\"no type\"}"))
        assertTrue(AutoTaskActionParser.parse("[{\"type\":\"notify\"}]").orEmpty().isNotEmpty())
    }
}
