package io.legado.app.model.localBook.epubcore.template

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EpubTemplateBridgePolicyTest {
    private fun wire(type: String, fields: String = "", token: String = "7"): String =
        "{\"type\":\"$type\",\"token\":$token${if (fields.isEmpty()) "" else ",$fields"}}"

    private fun parse(raw: String) = EpubTemplateBridgePolicy.parse(raw, 7)

    private fun selection(): JsonObject = JsonParser.parseString(wire("selection",
        """"text":"选中的🌅正文","rects":[{"left":-2.5,"top":4,"right":60,"bottom":32}],"viewportWidth":400,"viewportHeight":700"""
    )).asJsonObject

    private fun validMessages(): Map<String, String> = linkedMapOf(
        "stable" to wire("stable"),
        "error" to wire("error", """"message":"Template failed""""),
        "metrics" to wire("metrics", """"pageCount":3,"pageIndex":2,"layoutRevision":0"""),
        "renderState" to wire("renderState", """"visualRevision":0,"layoutPending":true"""),
        "textPosition" to wire("textPosition", """"page":0,"revision":0,"offset":12"""),
        "selection" to selection().toString(),
        "sourceImage" to wire("sourceImage", """"page":0,"revision":0,"imageId":"image-12","sequence":1"""),
        "image" to wire("image", """"url":"https://text-session.epub.local/image/1""""),
        "link" to wire("link", """"url":"#chapter-title""""),
        "annotationState" to wire("annotationState", """"visible":true"""),
        "boundary" to wire("boundary", """"direction":1"""),
        "embeddedInteraction" to wire("embeddedInteraction", """"interactionId":0,"active":false""")
    )

    @Test fun `every supported event returns an intact validated flat payload`() {
        validMessages().forEach { (type, raw) ->
            val message = parse(raw)
            assertNotNull(type, message)
            assertEquals(type, message!!.type)
            assertEquals(7L, message.token)
            assertEquals(JsonParser.parseString(raw), message.payload)
        }
    }

    @Test fun `stale tokens unknown events and executable extra fields are rejected`() {
        validMessages().values.forEach { assertNull(EpubTemplateBridgePolicy.parse(it, 8)) }
        listOf("eval", "execute", "java", "onStable", "Metrics", "").forEach {
            assertNull(parse(wire(it)))
        }
        validMessages().values.forEach { raw ->
            val payload = JsonParser.parseString(raw).asJsonObject
            payload.addProperty("script", "java.openUrl('intent://arbitrary')")
            assertNull(payload.toString(), parse(payload.toString()))
        }
    }

    @Test fun `required fields must be present and null is never coerced`() {
        validMessages().values.forEach { raw ->
            val original = JsonParser.parseString(raw).asJsonObject
            original.keySet().forEach { name ->
                val missing = original.deepCopy().apply { remove(name) }
                assertNull(missing.toString(), parse(missing.toString()))
                val nullValue = original.deepCopy().apply { add(name, null) }
                assertNull(nullValue.toString(), parse(nullValue.toString()))
            }
        }
    }

    @Test fun `numeric strings fractions booleans and unsafe token integers are rejected exactly`() {
        listOf("\"7\"", "true", "null", "[]", "{}", "7.1", "7.00000000000000000000000001",
            "-1", "9007199254740992", "1e9999999999").forEach { value ->
            assertNull(value, parse(wire("stable", token = value)))
        }
        assertNotNull(parse(wire("stable", token = "7.000")))
        assertNotNull(parse(wire("stable", token = "7e0")))
        val maximum = EpubTemplateBridgePolicy.MAX_SAFE_INTEGER
        assertNotNull(EpubTemplateBridgePolicy.parse(wire("stable", token = "$maximum"), maximum))
        assertNull(EpubTemplateBridgePolicy.parse(wire("stable", token = "${maximum + 1}"), maximum + 1))
        assertNotNull(EpubTemplateBridgePolicy.parse(wire("stable", token = "-1"), -1))
        assertNotNull(EpubTemplateBridgePolicy.parse(wire("stable", token = "-${maximum}"), -maximum))
        assertNull(EpubTemplateBridgePolicy.parse(wire("stable", token = "-${maximum + 1}"), -maximum - 1))
        assertNull(EpubTemplateBridgePolicy.parse(wire("stable", token = "-1.00000000000000000001"), -1))
        assertNotNull(EpubTemplateBridgePolicy.parse(wire("stable", token = "-1e0"), -1))
    }

    @Test fun `negative preload events validate without accepting stale or promoted owners`() {
        for (preloadToken in listOf(-1L, -17L, -EpubTemplateBridgePolicy.MAX_SAFE_INTEGER)) {
            validMessages().forEach { (type, raw) ->
                val payload = JsonParser.parseString(raw).asJsonObject.apply {
                    addProperty("token", preloadToken)
                }
                val wire = payload.toString()
                val accepted = EpubTemplateBridgePolicy.parse(wire, preloadToken)
                assertNotNull("$type with preload token $preloadToken", accepted)
                assertEquals(preloadToken, accepted!!.token)
                assertEquals(payload, accepted.payload)
                assertNull("$type must not enter another preload", EpubTemplateBridgePolicy.parse(wire, preloadToken + 1))
                assertNull("$type must not survive promotion", EpubTemplateBridgePolicy.parse(wire, 7))
                assertNull("$type foreground event must not enter preload", EpubTemplateBridgePolicy.parse(raw, preloadToken))
            }
        }
    }

    @Test fun `native integer fields are bounded before getAsInt can truncate or overflow`() {
        listOf("-1", "1.5", "1.00000000000000000001", "2147483648", "1e1000", "\"1\"", "true").forEach { value ->
            assertNull(value, parse(wire("textPosition", "\"page\":$value,\"revision\":0,\"offset\":0")))
            assertNull(value, parse(wire("textPosition", "\"page\":0,\"revision\":0,\"offset\":$value")))
        }
        assertNotNull(parse(wire("textPosition", """"page":2147483647,"revision":9007199254740991,"offset":2147483647""")))
        assertNull(parse(wire("textPosition", """"page":0,"revision":9007199254740992,"offset":0""")))
        assertNull(parse(wire("metrics", """"pageCount":0,"pageIndex":0,"layoutRevision":0""")))
        assertNull(parse(wire("metrics", """"pageCount":2,"pageIndex":2,"layoutRevision":0""")))
        assertNull(parse(wire("metrics", """"pageCount":2,"pageIndex":-1,"layoutRevision":0""")))
        assertNotNull(parse(wire("metrics", """"pageCount":2147483647,"pageIndex":2147483646,"layoutRevision":0""")))
    }

    @Test fun `boolean fields reject string and number coercions`() {
        listOf("\"true\"", "\"false\"", "1", "0", "null", "[]", "{}").forEach { value ->
            assertNull(parse(wire("renderState", "\"visualRevision\":0,\"layoutPending\":$value")))
            assertNull(parse(wire("embeddedInteraction", "\"interactionId\":0,\"active\":$value")))
            assertNull(parse(wire("annotationState", "\"visible\":$value")))
        }
        assertNull(parse(wire("embeddedInteraction", """"interactionId":-1,"active":true""")))
        assertNotNull(parse(wire("annotationState", """"visible":false""")))
    }

    @Test fun `scroll boundary accepts only signed unit directions`() {
        assertNotNull(parse(wire("boundary", """"direction":-1""")))
        assertNotNull(parse(wire("boundary", """"direction":1""")))
        listOf("0", "2", "-2", "-1.5", "\"1\"", "true", "null").forEach { value ->
            assertNull(value, parse(wire("boundary", "\"direction\":$value")))
        }
    }

    @Test fun `strict JSON rejects duplicate decoded keys comments trailing documents and excessive depth`() {
        listOf(
            """{"type":"stable","token":7,"token":7}""",
            """{"type":"stable","token":7,"\u0074oken":7}""",
            """{"type":"stable","type":"stable","token":7}""",
            """{'type':'stable','token':7}""",
            """{type:"stable",token:7}""",
            """{"type":"stable","token":7,}""",
            """{"type":"stable","token":7}// ignored""",
            wire("stable") + wire("stable"),
            "[]", "null", "false", "",
            wire("stable", "\"extra\":" + "[".repeat(1000) + "0" + "]".repeat(1000))
        ).forEach { assertNull(it.take(120), parse(it)) }
        val duplicateRect = selection().toString().replace("\"left\":-2.5", "\"left\":-2.5,\"left\":0")
        assertNull(parse(duplicateRect))
    }

    @Test fun `selection geometry rejects nonnumbers nonfinite floats invalid dimensions and reversed rectangles`() {
        listOf("\"1\"", "true", "null", "1e309", "1e100").forEach { value ->
            val raw = selection().toString().replace("\"left\":-2.5", "\"left\":$value")
            assertNull(value, parse(raw))
        }
        listOf("0", "-1", "1e-1000", "1e-100", "1e100", "\"400\"").forEach { value ->
            val raw = selection().toString().replace("\"viewportWidth\":400", "\"viewportWidth\":$value")
            assertNull(value, parse(raw))
        }
        listOf("NaN", "Infinity", "-Infinity").forEach { value ->
            assertNull(parse(selection().toString().replace("\"left\":-2.5", "\"left\":$value")))
        }
        val backward = selection().apply { getAsJsonArray("rects")[0].asJsonObject.addProperty("right", -3) }
        assertNull(parse(backward.toString()))
        val overflowWidth = selection().apply {
            getAsJsonArray("rects")[0].asJsonObject.apply {
                addProperty("left", -3e38); addProperty("right", 3e38)
            }
        }
        assertNull(parse(overflowWidth.toString()))
    }

    @Test fun `selection rectangle shape and count are checked before native iteration`() {
        val original = selection()
        listOf("[]", "null", "1", "\"rect\"", "{\"left\":0}").forEach { rectangle ->
            val payload = original.deepCopy()
            payload.add("rects", JsonParser.parseString("[$rectangle]"))
            assertNull(payload.toString(), parse(payload.toString()))
        }
        val extra = original.deepCopy().apply { getAsJsonArray("rects")[0].asJsonObject.addProperty("execute", "anything") }
        assertNull(parse(extra.toString()))
        val atLimit = original.deepCopy().apply {
            val rectangle = getAsJsonArray("rects")[0]
            add("rects", JsonArray().apply { repeat(EpubTemplateBridgePolicy.MAX_SELECTION_RECTS) { add(rectangle.deepCopy()) } })
        }
        assertNotNull(parse(atLimit.toString()))
        atLimit.getAsJsonArray("rects").add(atLimit.getAsJsonArray("rects")[0].deepCopy())
        assertNull(parse(atLimit.toString()))
        val clear = original.deepCopy().apply { addProperty("text", ""); add("rects", JsonArray()) }
        assertNotNull(parse(clear.toString()))
    }

    @Test fun `message and selection text budgets use UTF16 lengths without silently truncating`() {
        val limit = EpubTemplateBridgePolicy.MAX_RAW_CHARS
        val stable = wire("stable")
        assertNotNull(parse(" ".repeat(limit - stable.length) + stable))
        assertNull(parse(" ".repeat(limit - stable.length + 1) + stable))
        val payload = selection().apply { addProperty("text", "🌅".repeat(EpubTemplateBridgePolicy.MAX_SELECTION_CHARS / 2)) }
        assertNotNull(parse(payload.toString()))
        payload.addProperty("text", payload.get("text").asString + "x")
        assertNull(parse(payload.toString()))
        assertNotNull(parse(wire("error", "\"message\":\"${"x".repeat(2000)}\"")))
        assertNull(parse(wire("error", "\"message\":\"${"x".repeat(2001)}\"")))
    }

    @Test fun `source image events carry only an existing-format id and positive replay sequence`() {
        listOf("", "other-1", "image--1", "javascript:alert(1)", "image-" + "1".repeat(59)).forEach { id ->
            assertNull(parse(wire("sourceImage", "\"page\":0,\"revision\":0,\"imageId\":\"$id\",\"sequence\":1")))
        }
        assertNotNull(parse(wire("sourceImage", "\"page\":0,\"revision\":0,\"imageId\":\"image-${"1".repeat(58)}\",\"sequence\":1")))
        assertNull(parse(wire("sourceImage", """"page":0,"revision":0,"imageId":"image-0","sequence":0""")))
        assertNull(parse(wire("sourceImage", """"page":0,"revision":0,"imageId":"image-0","sequence":1,"click":"java.eval('x')""")))
    }

    @Test fun `image and link URLs admit only their intended schemes and bounded strings`() {
        fun url(type: String, value: String) = JsonObject().apply {
            addProperty("type", type); addProperty("token", 7); addProperty("url", value)
        }.toString()
        listOf("https://example.org/read#page", "HTTP://EXAMPLE.ORG/", "https://[::1]:443/image.png").forEach { value ->
            assertNotNull(value, parse(url("image", value)))
            assertNotNull(value, parse(url("link", value)))
        }
        assertNotNull(parse(url("link", "#正文")))
        assertNull(parse(url("image", "#正文")))
        listOf("data:image/png;base64,AA==", "data:image/svg+xml,%3Csvg%2F%3E", "blob:https://example.org/asset-id", "blob:null/asset-id").forEach { value ->
            assertNotNull(value, parse(url("image", value)))
            assertNull(value, parse(url("link", value)))
        }
        listOf("javascript:alert(1)", "file:///private/book", "content://private/book", "intent://open#Intent;end",
            "data:text/html,%3Cscript%3E", "data:image/png,", "blob:javascript:alert(1)", "blob:file:///private/image", "blob:null/",
            "https:///missing-host", "https://user@", "https://example.org:99999/image", "/relative", "//example.org", " https://example.org",
            "https://example.org/\nscript", "https://example.org/\u2028script", "https://example.org/" + "x".repeat(8192)).forEach { value ->
            assertNull(value.take(120), parse(url("image", value)))
            assertNull(value.take(120), parse(url("link", value)))
        }
    }
}
