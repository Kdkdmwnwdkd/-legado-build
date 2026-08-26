package io.legado.app.help

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.legado.app.utils.GSONStrict

/**
 * Strict boundary for direct-link upload rules read from external JSON.
 *
 * Gson can populate null into Kotlin non-null properties when it creates an object through
 * reflection. Parsing to a JSON tree first keeps those invalid values out of [DirectLinkUpload.Rule].
 */
object DirectLinkUploadRuleCodec {

    const val MAX_JSON_BYTES = 512 * 1024

    fun decode(json: String?): Result<DirectLinkUpload.Rule> = runCatching {
        val source = requireNotNull(json) { "Direct link upload rule JSON is missing" }
        requireJsonSize(source)
        val element = parse(source)
        if (!element.isJsonObject) {
            throw IllegalArgumentException("Direct link upload rule must be a JSON object")
        }
        element.asJsonObject.toCanonicalRule()
    }

    fun decodeList(json: String?): Result<List<DirectLinkUpload.Rule>> = runCatching {
        val source = requireNotNull(json) { "Direct link upload rule JSON is missing" }
        requireJsonSize(source)
        val element = parse(source)
        if (!element.isJsonArray) {
            throw IllegalArgumentException("Direct link upload rules must be a JSON array")
        }
        element.asJsonArray.mapIndexed { index, item ->
            if (!item.isJsonObject) {
                throw IllegalArgumentException(
                    "Direct link upload rule at index $index must be a JSON object"
                )
            }
            item.asJsonObject.toCanonicalRule()
        }
    }

    fun canonicalize(rule: DirectLinkUpload.Rule): Result<DirectLinkUpload.Rule> = runCatching {
        canonicalizeOrThrow(rule)
    }

    fun encode(rule: DirectLinkUpload.Rule): Result<String> = runCatching {
        val json = GSONStrict.toJson(canonicalizeOrThrow(rule))
        requireJsonSize(json)
        json
    }

    private fun JsonObject.toCanonicalRule(): DirectLinkUpload.Rule {
        return DirectLinkUpload.Rule(
            uploadUrl = requiredNonBlankString("uploadUrl"),
            downloadUrlRule = requiredNonBlankString("downloadUrlRule"),
            summary = optionalString("summary"),
            compress = optionalBoolean("compress"),
            expiryDate = optionalNonNegativeInt("expiryDate")
        )
    }

    private fun canonicalizeOrThrow(rule: DirectLinkUpload.Rule): DirectLinkUpload.Rule {
        // Nullable locals are intentional. A legacy Gson-created Rule can contain runtime nulls
        // despite the Kotlin property declarations, and those values must become validation errors.
        val uploadUrl: String? = rule.uploadUrl
        val downloadUrlRule: String? = rule.downloadUrlRule
        val summary: String? = rule.summary
        if (uploadUrl.isNullOrBlank()) {
            throw IllegalArgumentException("Direct link upload rule uploadUrl must not be blank")
        }
        if (downloadUrlRule.isNullOrBlank()) {
            throw IllegalArgumentException(
                "Direct link upload rule downloadUrlRule must not be blank"
            )
        }
        if (summary == null) {
            throw IllegalArgumentException("Direct link upload rule summary must not be null")
        }
        return DirectLinkUpload.Rule(
            uploadUrl = uploadUrl,
            downloadUrlRule = downloadUrlRule,
            summary = summary,
            compress = rule.compress,
            expiryDate = rule.expiryDate.also {
                require(it >= 0) {
                    "Direct link upload rule expiryDate must not be negative"
                }
            }
        )
    }

    private fun JsonObject.requiredNonBlankString(name: String): String {
        val value = strictString(name, nullIsAllowed = false)
            ?: throw IllegalArgumentException("Direct link upload rule $name is missing")
        if (value.isBlank()) {
            throw IllegalArgumentException("Direct link upload rule $name must not be blank")
        }
        return value
    }

    private fun JsonObject.optionalString(name: String): String {
        return strictString(name, nullIsAllowed = true).orEmpty()
    }

    private fun JsonObject.strictString(name: String, nullIsAllowed: Boolean): String? {
        val element = get(name)
        if (element == null || element.isJsonNull) {
            if (nullIsAllowed) return null
            throw IllegalArgumentException("Direct link upload rule $name is missing")
        }
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            throw IllegalArgumentException("Direct link upload rule $name must be a string")
        }
        return element.asString
    }

    private fun JsonObject.optionalBoolean(name: String): Boolean {
        val element = get(name)
        if (element == null || element.isJsonNull) return false
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) {
            throw IllegalArgumentException("Direct link upload rule $name must be a boolean")
        }
        return element.asBoolean
    }

    private fun JsonObject.optionalNonNegativeInt(name: String): Int {
        val element = get(name)
        if (element == null || element.isJsonNull) return 0
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            throw IllegalArgumentException("Direct link upload rule $name must be an integer")
        }
        val decimal = runCatching { element.asBigDecimal }.getOrElse {
            throw IllegalArgumentException("Direct link upload rule $name must be an integer")
        }
        if (decimal.stripTrailingZeros().scale() > 0 || decimal < java.math.BigDecimal.ZERO) {
            throw IllegalArgumentException(
                "Direct link upload rule $name must be a non-negative integer"
            )
        }
        return runCatching { decimal.intValueExact() }.getOrElse {
            throw IllegalArgumentException("Direct link upload rule $name is out of range")
        }
    }

    private fun parse(json: String): JsonElement {
        return GSONStrict.fromJson(json, JsonElement::class.java)
            ?: throw IllegalArgumentException("Direct link upload rule JSON is empty")
    }

    private fun requireJsonSize(json: String) {
        if (utf8SizeExceeds(json, MAX_JSON_BYTES)) {
            throw IllegalArgumentException(
                "Direct link upload rule JSON exceeds $MAX_JSON_BYTES UTF-8 bytes"
            )
        }
    }

    private fun utf8SizeExceeds(value: String, maxBytes: Int): Boolean {
        var byteCount = 0
        var index = 0
        while (index < value.length) {
            val char = value[index]
            val charBytes = when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                char.isHighSurrogate() &&
                    index + 1 < value.length &&
                    value[index + 1].isLowSurrogate() -> {
                    index++
                    4
                }

                else -> 3
            }
            if (byteCount > maxBytes - charBytes) return true
            byteCount += charBytes
            index++
        }
        return false
    }
}
