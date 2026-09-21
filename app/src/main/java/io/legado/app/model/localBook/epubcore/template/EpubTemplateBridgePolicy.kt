package io.legado.app.model.localBook.epubcore.template

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import java.net.URI
import java.util.Locale

/**
 * Validates the template renderer's data-only bridge messages.
 * The Android bridge authenticates its channel before calling this policy; source
 * image IDs still require the existing current-session/image-action gate afterward.
 */
object EpubTemplateBridgePolicy {
    const val MAX_RAW_CHARS = 256 * 1024
    const val MAX_SELECTION_CHARS = 64 * 1024
    const val MAX_SELECTION_RECTS = 512
    const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    private const val MAX_URL_CHARS = 8192
    private const val MAX_ERROR_CHARS = 2000
    private const val MAX_JSON_DEPTH = 4
    private const val MAX_JSON_VALUES = 4096
    private val baseFields = setOf("type", "token")
    private val eventFields = mapOf(
        "stable" to emptySet(),
        "error" to setOf("message"),
        "metrics" to setOf("pageCount", "pageIndex", "layoutRevision"),
        "renderState" to setOf("visualRevision", "layoutPending"),
        "textPosition" to setOf("page", "revision", "offset"),
        "selection" to setOf("text", "rects", "viewportWidth", "viewportHeight"),
        "sourceImage" to setOf("page", "revision", "imageId", "sequence"),
        "image" to setOf("url"),
        "link" to setOf("url"),
        "annotationState" to setOf("visible"),
        "boundary" to setOf("direction"),
        "embeddedInteraction" to setOf("interactionId", "active")
    )
    private val rectFields = setOf("left", "top", "right", "bottom")
    private val imageId = Regex("image-[0-9]+")
    private val imageMime = Regex("image/[a-z0-9][a-z0-9.+-]*")

    /** payload is the entire validated flat wire object, including type and token. */
    data class Message(val type: String, val token: Long, val payload: JsonObject)

    fun parse(raw: String, expectedToken: Long): Message? {
        if (raw.length > MAX_RAW_CHARS || raw.isBlank() ||
            expectedToken !in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) return null
        return try {
            val element = JsonReader(StringReader(raw)).use { reader ->
                reader.strictness = Strictness.STRICT
                val value = readValue(reader, 0, ParseBudget())
                require(reader.peek() == JsonToken.END_DOCUMENT)
                value
            }
            if (!element.isJsonObject) return null
            val payload = element.asJsonObject
            val type = payload.string("type", 32) ?: return null
            val fields = eventFields[type] ?: return null
            if (!payload.exactFields(baseFields + fields)) return null
            // Foreground generations are positive; native warm/preload surfaces
            // use decreasing negative tokens until they are promoted.
            val token = payload.integer("token", min = -MAX_SAFE_INTEGER) ?: return null
            if (token != expectedToken) return null
            val valid = when (type) {
                "stable" -> true
                "error" -> payload.string("message", MAX_ERROR_CHARS) != null
                "metrics" -> {
                    val count = payload.integer("pageCount", min = 1, max = Int.MAX_VALUE.toLong())
                    val index = payload.integer("pageIndex", max = Int.MAX_VALUE.toLong())
                    count != null && index != null && index < count && payload.integer("layoutRevision") != null
                }
                "renderState" -> payload.integer("visualRevision") != null && payload.boolean("layoutPending") != null
                "textPosition" -> payload.pageAndRevision() && payload.integer("offset", max = Int.MAX_VALUE.toLong()) != null
                "selection" -> validSelection(payload)
                "sourceImage" -> payload.pageAndRevision() && payload.integer("sequence", min = 1) != null &&
                    payload.string("imageId", 64)?.matches(imageId) == true
                "image", "link" -> payload.string("url", MAX_URL_CHARS)?.let { validUrl(it, type == "image") } == true
                "annotationState" -> payload.boolean("visible") != null
                "boundary" -> {
                    val direction = payload.integer("direction", min = -1, max = 1)
                    direction == -1L || direction == 1L
                }
                "embeddedInteraction" -> payload.integer("interactionId") != null && payload.boolean("active") != null
                else -> false
            }
            if (valid) Message(type, token, payload) else null
        } catch (_: Exception) {
            // Malformed, oversized, deeply nested or numerically invalid input is
            // a rejected message, never a renderer/native lifecycle exception.
            null
        }
    }

    private class ParseBudget(var values: Int = 0)

    private fun readValue(reader: JsonReader, depth: Int, budget: ParseBudget): JsonElement {
        require(depth <= MAX_JSON_DEPTH && ++budget.values <= MAX_JSON_VALUES)
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(name.length <= 64 && !has(name))
                    add(name, readValue(reader, depth + 1, budget))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                reader.beginArray()
                while (reader.hasNext()) {
                    require(size() < MAX_SELECTION_RECTS)
                    add(readValue(reader, depth + 1, budget))
                }
                reader.endArray()
            }
            JsonToken.STRING -> {
                val value = reader.nextString()
                require(value.length <= MAX_SELECTION_CHARS)
                JsonPrimitive(value)
            }
            JsonToken.NUMBER -> {
                val value = reader.nextString()
                require(value.length <= 64)
                JsonPrimitive(BigDecimal(value))
            }
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
            else -> throw IllegalArgumentException("Invalid template bridge JSON")
        }
    }

    private fun JsonObject.exactFields(fields: Set<String>): Boolean =
        size() == fields.size && fields.all(::has)

    private fun JsonObject.string(name: String, max: Int): String? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return element.asString.takeIf { it.length <= max }
    }

    private fun JsonObject.boolean(name: String): Boolean? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) return null
        return element.asBoolean
    }

    private fun JsonObject.integer(name: String, min: Long = 0, max: Long = MAX_SAFE_INTEGER): Long? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
        val value = element.asBigDecimal
        if (value < BigDecimal.valueOf(min) || value > BigDecimal.valueOf(max) || value.stripTrailingZeros().scale() > 0) return null
        return value.longValueExact()
    }

    private fun JsonObject.coordinate(name: String): Double? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
        val value = element.asDouble
        return value.takeIf { it.isFinite() && it.toFloat().isFinite() }
    }

    private fun JsonObject.pageAndRevision(): Boolean =
        integer("page", max = Int.MAX_VALUE.toLong()) != null && integer("revision") != null

    private fun validSelection(payload: JsonObject): Boolean {
        if (payload.string("text", MAX_SELECTION_CHARS) == null) return false
        val width = payload.coordinate("viewportWidth") ?: return false
        val height = payload.coordinate("viewportHeight") ?: return false
        if (width <= 0 || height <= 0 || width.toFloat() <= 0 || height.toFloat() <= 0) return false
        val rects = payload.get("rects")?.takeIf { it.isJsonArray }?.asJsonArray ?: return false
        if (rects.size() > MAX_SELECTION_RECTS) return false
        return rects.all { element ->
            if (!element.isJsonObject) return@all false
            val rect = element.asJsonObject
            if (!rect.exactFields(rectFields)) return@all false
            val left = rect.coordinate("left") ?: return@all false
            val top = rect.coordinate("top") ?: return@all false
            val right = rect.coordinate("right") ?: return@all false
            val bottom = rect.coordinate("bottom") ?: return@all false
            right >= left && bottom >= top && (right - left).toFloat().isFinite() && (bottom - top).toFloat().isFinite()
        }
    }

    private fun validUrl(value: String, image: Boolean): Boolean {
        if (value.isEmpty() || value.any { it <= ' ' || it.isISOControl() || it == '\u2028' || it == '\u2029' }) return false
        if (!image && value.startsWith('#')) return true
        if (isHttpUrl(value)) return true
        if (!image) return false
        if (value.startsWith("data:", ignoreCase = true)) {
            val comma = value.indexOf(',')
            if (comma < 0 || comma == value.lastIndex) return false
            val mime = value.substring(5, comma).substringBefore(';').lowercase(Locale.ROOT)
            return imageMime.matches(mime)
        }
        if (value.startsWith("blob:", ignoreCase = true)) {
            val source = value.substring(5)
            return if (source.startsWith("null/")) source.length > 5 && URI(value).isAbsolute else isHttpUrl(source)
        }
        return false
    }

    private fun isHttpUrl(value: String): Boolean {
        val uri = URI(value)
        return uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.port in -1..65535
    }
}
