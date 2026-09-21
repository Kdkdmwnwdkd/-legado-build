package io.legado.app.model.localBook.epubcore.template

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.Strictness
import java.security.MessageDigest

/** A portable template. Author HTML, CSS and JavaScript are never normalized or filtered. */
data class EpubReaderTemplate(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val name: String,
    val description: String = "",
    val firstPageHtml: String,
    val otherPageHtml: String,
    val css: String = "",
    val javascript: String = ""
) {
    /** Includes every field with length framing, independent of JSON formatting and field order. */
    fun contentHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(schemaVersion.toString(), id, name, description, firstPageHtml, otherPageHtml, css, javascript)
            .forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update((bytes.size.toString() + ":").toByteArray(Charsets.UTF_8))
                digest.update(bytes)
            }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    /** Structural validation only. Browser syntax/layout errors belong to the real renderer. */
    fun validate(): List<String> = buildList {
        if (schemaVersion != SCHEMA_VERSION) add("不支持的模板版本：" + schemaVersion)
        if (id.isBlank()) add("模板 id 不能为空")
        if (name.isBlank()) add("模板名称不能为空")
        if (firstPageHtml.isBlank()) add("首页 HTML 不能为空")
        if (otherPageHtml.isBlank()) add("续页 HTML 不能为空")
    }

    fun toJson(): String = json.toJson(this)

    companion object {
        const val SCHEMA_VERSION = 1

        internal val json = GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .setStrictness(Strictness.STRICT)
            .create()

        fun fromJson(source: String): EpubReaderTemplate = fromJsonObject(parseObject(source))

        internal fun parseObject(source: String): JsonObject {
            val element = try {
                json.fromJson(source, JsonElement::class.java)
            } catch (error: Exception) {
                throw IllegalArgumentException("模板 JSON 格式错误：" + error.localizedMessage, error)
            }
            require(element != null && element.isJsonObject) { "模板 JSON 必须是一个对象" }
            return element.asJsonObject
        }

        internal fun readVersion(value: JsonObject): Int {
            val version = value.get("schemaVersion")
            require(version != null && version.isJsonPrimitive && version.asJsonPrimitive.isNumber) {
                "schemaVersion 必须是整数"
            }
            return try {
                version.asBigDecimal.intValueExact()
            } catch (error: ArithmeticException) {
                throw IllegalArgumentException("schemaVersion 必须是整数", error)
            }
        }

        internal fun fromJsonObject(value: JsonObject): EpubReaderTemplate {
            fun string(name: String, default: String? = null): String {
                val field = value.get(name)
                if (field == null && default != null) return default
                require(field != null && field.isJsonPrimitive && field.asJsonPrimitive.isString) {
                    name + " 必须是字符串"
                }
                return field.asString
            }
            return EpubReaderTemplate(
                schemaVersion = readVersion(value),
                id = string("id"),
                name = string("name"),
                description = string("description", ""),
                firstPageHtml = string("firstPageHtml"),
                otherPageHtml = string("otherPageHtml"),
                css = string("css", ""),
                javascript = string("javascript", "")
            ).also { template ->
                val errors = template.validate()
                require(errors.isEmpty()) { errors.joinToString("\n") }
            }
        }
    }
}
