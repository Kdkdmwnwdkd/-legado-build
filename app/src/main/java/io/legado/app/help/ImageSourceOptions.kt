package io.legado.app.help

import com.google.gson.JsonElement
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.util.Locale

/**
 * Parses Legado's image URL option suffix without changing the actual image URL.
 *
 * The suffix is the JSON object after the first comma immediately followed by
 * an opening brace, for example: image.png,{"click":"java.toast('ok')"}.
 */
data class ParsedImageSource(
    val source: String,
    val options: Map<String, String>
) {
    fun option(name: String): String? {
        return options.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
    }

    val click: String?
        get() = option("click") ?: option("onclick")

    val style: String?
        get() = option("style")

    val width: String?
        get() = option("width")
}

object ImageSourceOptions {
    private val optionSeparator = Regex(
        "\\s*,\\s*(?=\\{|&#(?:x0*7b|0*123);)",
        RegexOption.IGNORE_CASE
    )
    private val htmlEntity = Regex("&(#x[0-9a-fA-F]+|#\\d+|quot|apos|amp|lt|gt);")

    fun parse(raw: String?): ParsedImageSource? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        optionSeparator.findAll(value).toList().asReversed().forEach { separator ->
            val optionText = decodeHtmlEntities(
                value.substring(separator.range.last + 1).trim()
            )
            val options = parseOptions(optionText)
                ?: return@forEach
            return ParsedImageSource(
                source = value.substring(0, separator.range.first).trim(),
                options = options
            )
        }
        return ParsedImageSource(value, emptyMap())
    }

    fun click(raw: String?): String? = parse(raw)?.click

    private fun parseOptions(optionText: String): Map<String, String>? {
        readOptions(optionText)?.let { return it }

        // Some paragraph-rule results are serialized once more before being
        // placed in an HTML attribute, leaving structural quotes escaped as
        // `\"`. Retry that legacy representation without changing the normal
        // JSON path above.
        if (optionText.contains("\\\"")) {
            runCatching {
                GSON.fromJson(
                    "\"$optionText\"",
                    String::class.java
                )
            }.getOrNull()
                ?.takeIf { it != optionText }
                ?.let { decoded ->
                    readOptions(decoded)?.let { return it }
                }
            val unescaped = optionText.replace("\\\"", "\"")
            readOptions(unescaped)?.let { return it }
        }
        return null
    }

    private fun readOptions(value: String): Map<String, String>? = runCatching {
        // Headers and request bodies may be JSON objects alongside a string click.
        // Retain them as JSON rather than rejecting the entire options suffix.
        GSON.fromJsonObject<Map<String, JsonElement>>(value).getOrThrow().mapValues { (_, item) ->
            when {
                item.isJsonNull -> ""
                item.isJsonPrimitive -> item.asString
                else -> item.toString()
            }
        }
    }.getOrNull()

    private fun decodeHtmlEntities(value: String): String {
        return htmlEntity.replace(value) { match ->
            when (val entity = match.groupValues[1].lowercase(Locale.ROOT)) {
                "quot" -> "\""
                "apos" -> "'"
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                else -> {
                    val codePoint = when {
                        entity.startsWith("#x") -> entity.substring(2).toIntOrNull(16)
                        entity.startsWith("#") -> entity.substring(1).toIntOrNull()
                        else -> null
                    }
                    codePoint
                        ?.takeIf { it in 0..Character.MAX_CODE_POINT }
                        ?.let { String(Character.toChars(it)) }
                        ?: match.value
                }
            }
        }
    }
}
