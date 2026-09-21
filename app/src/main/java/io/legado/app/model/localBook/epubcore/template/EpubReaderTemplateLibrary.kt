package io.legado.app.model.localBook.epubcore.template

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** The existing library document also carries which bundled templates the user has removed. */
internal data class EpubReaderTemplateLibrary(
    val templates: List<EpubReaderTemplate> = emptyList(),
    val hiddenBuiltInIds: Set<String> = emptySet()
) {
    fun validate() {
        val ids = hashSetOf<String>()
        templates.forEach { template ->
            require(template.validate().isEmpty()) { template.validate().joinToString("\n") }
            require(ids.add(template.id)) { "模板重复：" + template.id }
        }
        require(hiddenBuiltInIds.all { it.startsWith("builtin.") && it.length > "builtin.".length }) {
            "内置模板记录无效"
        }
    }

    fun toJson(): String {
        validate()
        return EpubReaderTemplate.json.toJson(JsonObject().apply {
            addProperty("schemaVersion", EpubReaderTemplate.SCHEMA_VERSION)
            add("templates", JsonArray().apply {
                templates.forEach { add(EpubReaderTemplate.json.toJsonTree(it)) }
            })
            if (hiddenBuiltInIds.isNotEmpty()) {
                add("hiddenBuiltInIds", JsonArray().apply { hiddenBuiltInIds.sorted().forEach { add(it) } })
            }
        })
    }

    companion object {
        fun fromJson(raw: String): EpubReaderTemplateLibrary {
            return fromJsonObject(EpubReaderTemplate.parseObject(raw))
        }

        fun fromJsonObject(root: JsonObject): EpubReaderTemplateLibrary {
            require(EpubReaderTemplate.readVersion(root) == EpubReaderTemplate.SCHEMA_VERSION) {
                "不支持的模板库版本"
            }
            val templates = root.get("templates")
            require(templates != null && templates.isJsonArray) { "模板库内容无效" }
            val hidden = root.get("hiddenBuiltInIds")
            require(hidden == null || hidden.isJsonArray) { "内置模板记录无效" }
            val hiddenIds = hidden?.asJsonArray?.map { value ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "内置模板记录无效" }
                value.asString
            }.orEmpty()
            require(hiddenIds.distinct().size == hiddenIds.size) { "内置模板记录重复" }
            return EpubReaderTemplateLibrary(
                templates.asJsonArray.map { value ->
                    require(value.isJsonObject) { "模板库条目无效" }
                    EpubReaderTemplate.fromJsonObject(value.asJsonObject)
                },
                hiddenIds.toSet()
            ).also { it.validate() }
        }
    }
}
