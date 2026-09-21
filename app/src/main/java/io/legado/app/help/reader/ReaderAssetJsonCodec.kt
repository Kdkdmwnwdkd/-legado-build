package io.legado.app.help.reader

import com.google.gson.JsonElement
import com.google.gson.stream.JsonWriter
import java.util.Base64

/** Optional resources in RED01; old Reeden fields and the gzip JSON container stay intact. */
object ReaderAssetJsonCodec {
    const val MAX_BYTES = 24 * 1024 * 1024
    const val MAX_COUNT = 128

    fun read(value: JsonElement?): List<ReaderAssetPayload> {
        if (value == null || value.isJsonNull) return emptyList()
        require(value.isJsonArray && value.asJsonArray.size() <= MAX_COUNT) { "素材列表无效或数量过多" }
        val ids = hashSetOf<String>()
        var total = 0L
        return value.asJsonArray.map { element ->
            require(element.isJsonObject) { "素材条目无效" }
            val obj = element.asJsonObject
            val id = obj.get("id")?.asString.orEmpty()
            val name = obj.get("name")?.asString.orEmpty()
            val encoded = obj.get("data")?.asString.orEmpty()
            require(ReaderAssetReferences.validId(id) && ids.add(id)) { "素材标识无效或重复" }
            require(encoded.length <= ((MAX_BYTES - total) / 3 + 1) * 4) { "规则包中的素材总量超过 24 MiB" }
            val bytes = Base64.getDecoder().decode(encoded)
            total += bytes.size
            require(total <= MAX_BYTES) { "规则包中的素材总量超过 24 MiB" }
            ReaderAssetPayload(id, name, bytes).also { it.validate() }
        }
    }

    fun write(writer: JsonWriter, ids: Set<String>, resource: (String) -> ReaderAssetPayload?) {
        require(ids.size <= MAX_COUNT) { "引用的素材过多，请分批导出" }
        var total = 0L
        writer.beginArray()
        ids.forEach { id ->
            val payload = requireNotNull(resource(id)) { "引用的素材已丢失，请重新导入后再导出：$id" }
            require(payload.id == id) { "素材标识不一致" }
            payload.validate()
            total += payload.bytes.size
            require(total <= MAX_BYTES) { "规则包中的素材超过 24 MiB，请分批导出" }
            writer.beginObject().name("id").value(id).name("name").value(payload.name)
                .name("data").value(Base64.getEncoder().encodeToString(payload.bytes)).endObject()
        }
        writer.endArray()
    }
}
