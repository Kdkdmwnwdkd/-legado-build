package io.legado.app.help.book.highlight

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonWriter
import io.legado.app.help.reader.ReaderAssetJsonCodec
import io.legado.app.help.reader.ReaderAssetPayload
import io.legado.app.help.reader.ReaderAssetReferences
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** RED01 contains gzip JSON; RED10 describes its payload encoding in a JSON header. */
object HighlightPackageParser {
    const val MAX_PACKAGE_BYTES = 24 * 1024 * 1024
    const val MAX_JSON_BYTES = 48 * 1024 * 1024
    const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    const val MAX_RULES = 2048

    class EncryptedPackageException : IllegalArgumentException(
        "此 .red 文件标明使用 Reeden 的 AES-256-GCM 加密封装，当前尚不支持读取。" +
            "这不代表你或规则作者设置了密码。"
    )

    fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= limit) { "高亮规则文件过大" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun isRed(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 82.toByte() && bytes[1] == 69.toByte() && bytes[2] == 68.toByte()

    private fun containerHeader(bytes: ByteArray): Pair<JsonObject, Int> {
        require(bytes.size in 8..MAX_PACKAGE_BYTES) { "RED 文件头不完整或文件过大" }
        val size = ByteBuffer.wrap(bytes, 4, 4).int
        require(size in 1..65536 && size <= bytes.size - 8) { "RED 文件头不完整或长度无效" }
        val metadata = JsonParser.parseString(String(bytes, 8, size, Charsets.UTF_8))
        require(metadata.isJsonObject) { "RED 文件头格式错误" }
        return metadata.asJsonObject to (8 + size)
    }

    private fun json(bytes: ByteArray): JsonElement {
        require(bytes.size <= MAX_PACKAGE_BYTES) { "高亮规则文件过大" }
        val decoded = if (isRed(bytes)) {
            when (bytes[3].toInt() and 255) {
                1 -> GZIPInputStream(ByteArrayInputStream(bytes, 4, bytes.size - 4)).use {
                    readLimited(it, MAX_JSON_BYTES)
                }
                16 -> {
                    val (metadata, payloadOffset) = containerHeader(bytes)
                    require(metadata.string("resourceType") == "highlightRule") {
                        "不是高亮规则文件（替换规则、主题和书籍请从对应入口导入）"
                    }
                    if (metadata.string("algorithm").equals("aes-256-gcm-manifest", true)) {
                        require(metadata.integer("manifestLength", -1) in 16..(bytes.size - payloadOffset)) {
                            "RED 文件内容不完整或长度无效"
                        }
                        val nonce = runCatching { Base64.getDecoder().decode(metadata.string("manifestNonce")) }.getOrNull()
                        require(nonce?.size == 12) { "RED 文件的加密参数不完整或无效" }
                        throw EncryptedPackageException()
                    }
                    error("当前尚不支持此 .red 文件的封装方式")
                }
                else -> error("不支持的 RED 文件版本")
            }
        } else bytes
        return JsonParser.parseString(decoded.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
    }

    /** Used before dispatching a .red to the existing theme importer. */
    fun resourceType(bytes: ByteArray): String? = runCatching {
        if (isRed(bytes) && bytes[3] == 16.toByte()) {
            containerHeader(bytes).first.string("resourceType")
        } else {
            val root = json(bytes)
            when {
                root.isJsonObject -> root.asJsonObject.string("type").ifBlank {
                    if (isRule(root)) "highlightRule" else ""
                }
                root.isJsonArray && root.asJsonArray.size() > 0 &&
                    root.asJsonArray.all(::isRule) -> "highlightRule"
                else -> null
            }
        }
    }.getOrNull()

    fun parse(bytes: ByteArray, bookUrl: String? = null): HighlightPackage {
        val root = json(bytes)
        val resources = ReaderAssetJsonCodec.read(if (root.isJsonObject) root.asJsonObject.get("readerAssets") else null)
        val entries = when {
            root.isJsonArray -> root.asJsonArray.toList()
            root.isJsonObject && root.asJsonObject.string("type") == "highlightRule" -> {
                val data = root.asJsonObject.get("data")
                require(data != null && data.isJsonArray) { "高亮规则缺少 data 列表" }
                data.asJsonArray.toList()
            }
            isRule(root) -> listOf(root)
            else -> error("不是高亮规则包（替换规则、主题和书籍请从对应入口导入）")
        }
        require(entries.size in 1..MAX_RULES) { "高亮规则为空或数量超过上限" }
        val assets = linkedMapOf<String, ByteArray>()
        val warnings = arrayListOf<String>()
        val rules = entries.mapIndexedNotNull { index, element ->
            runCatching {
                require(isRule(element)) { "缺少 keyword 或 styleType" }
                val obj = element.asJsonObject
                val keyword = obj.string("keyword")
                val css = obj.string("styleCssText")
                require(keyword.length <= 16384 && css.length <= 32768) { "表达式或样式过长" }
                var warning = ""
                val encodedImage = obj.string("backgroundImageData")
                val image = if (encodedImage.isNotBlank()) runCatching {
                    require(encodedImage.length <= MAX_IMAGE_BYTES * 2) { "背景图片过大" }
                    val compressed = Base64.getMimeDecoder().decode(encodedImage)
                    val decoded = if (compressed.size > 2 && compressed[0] == 31.toByte() &&
                        compressed[1] == 139.toByte()) {
                        GZIPInputStream(ByteArrayInputStream(compressed)).use { readLimited(it, MAX_IMAGE_BYTES) }
                    } else compressed
                    require(decoded.size <= MAX_IMAGE_BYTES && imageMime(decoded) != null) { "不支持的背景图片" }
                    decoded
                }.getOrElse {
                    warning = "背景图片未导入：" + (it.message ?: "格式错误")
                    null
                } else null
                val asset = image?.let(::sha256)
                if (image != null && asset != null && !assets.containsKey(asset)) {
                    require(assets.values.sumOf { it.size.toLong() } + image.size <= 32L * 1024 * 1024) {
                        "背景图片总量过大"
                    }
                    assets[asset] = image
                }
                val dimensions = image?.let(::imageDimensions) ?: (0 to 0)
                val rule = HighlightRule(
                    name = obj.string("name").take(512),
                    keyword = keyword,
                    isRegex = obj.bool("isRegex"),
                    isMultiline = obj.bool("isMultiline"),
                    enabled = obj.bool("enabled", true),
                    sortOrder = obj.integer("sortOrder", index),
                    global = obj.bool("global", true),
                    bookUrl = if (obj.bool("global", true)) null else bookUrl,
                    sourceBookId = obj.string("bookId").ifBlank { null },
                    titleOnly = obj.bool("titleOnly") || obj.string("target") == "title",
                    applyToStyledBooks = obj.bool("applyToStyledBooks", true),
                    groupName = obj.string("groupName").take(512),
                    styleType = obj.string("styleType", "textColor"),
                    styleColorType = obj.string("styleColorType", "accent"),
                    styleMode = obj.string("styleMode"),
                    styleCssText = css,
                    asset = asset,
                    imageWidth = dimensions.first,
                    imageHeight = dimensions.second
                )
                val issues = listOfNotNull(
                    warning.takeIf { it.isNotBlank() },
                    HighlightMatcher.validationError(rule)?.let { "表达式不可用：" + it },
                    if (css.contains("reeden-font:", true)) "未附带 Reeden 字体，使用当前阅读字体" else null,
                    if (!rule.global && bookUrl == null) "仅限单书，需在管理页绑定书籍" else null
                )
                rule.copy(importWarning = issues.joinToString("；")).also {
                    if (issues.isNotEmpty()) warnings.add(it.displayName() + "：" + it.importWarning)
                }
            }.getOrElse {
                warnings.add("第 " + (index + 1) + " 条未导入：" + (it.message ?: "格式错误"))
                null
            }
        }.sortedBy { it.sortOrder }
        require(rules.isNotEmpty()) { warnings.joinToString("\n").ifBlank { "没有可导入的高亮规则" } }
        return HighlightPackage(rules, assets, warnings, resources)
    }

    fun export(rules: List<HighlightRule>, resources: (String) -> ReaderAssetPayload? = { null },
               asset: (String) -> ByteArray?): ByteArray {
        require(rules.isNotEmpty()) { "没有可导出的高亮规则" }
        require(rules.size <= MAX_RULES) { "一次最多导出 $MAX_RULES 条高亮规则，请搜索后分批导出" }
        val gson = Gson()
        val output = ByteArrayOutputStream()
        val packageOutput = ExportLimitOutputStream(output, MAX_PACKAGE_BYTES)
        packageOutput.write(byteArrayOf(82, 69, 68, 1))
        val jsonOutput = ExportLimitOutputStream(GZIPOutputStream(packageOutput), MAX_JSON_BYTES)
        val seenAssets = hashSetOf<String>()
        var assetBytes = 0L
        // Emit one rule at a time. A shared image is repeated by the RED format,
        // so even a small compressed package can exceed the import JSON budget.
        JsonWriter(OutputStreamWriter(jsonOutput, Charsets.UTF_8)).use { writer ->
            writer.beginObject().name("version").value(1).name("type").value("highlightRule")
                .name("data").beginArray()
            rules.forEach { rule ->
                require(rule.keyword.length <= 16384 && rule.styleCssText.length <= 32768) {
                    "${rule.displayName()}：表达式或样式过长，请修改后再导出"
                }
                val obj = gson.toJsonTree(rule).asJsonObject
                listOf("id", "bookUrl", "sourceBookId", "asset", "imageWidth", "imageHeight", "importWarning")
                    .forEach(obj::remove)
                rule.sourceBookId?.let { obj.addProperty("bookId", it) }
                rule.asset?.let { id ->
                    val bytes = requireNotNull(asset(id)) {
                        "${rule.displayName()}：背景图片缺失，请重新导入原规则后再导出"
                    }
                    require(bytes.size <= MAX_IMAGE_BYTES && imageMime(bytes) != null && sha256(bytes) == id) {
                        "${rule.displayName()}：背景图片损坏或过大，请重新导入原规则后再导出"
                    }
                    imageDimensions(bytes)
                    if (seenAssets.add(id)) assetBytes += bytes.size
                    require(assetBytes <= 32L * 1024 * 1024) { "背景图片总量过大，请搜索后分批导出" }
                    val compressed = ByteArrayOutputStream()
                    GZIPOutputStream(compressed).use { it.write(bytes) }
                    obj.addProperty("backgroundImageData", Base64.getEncoder().encodeToString(compressed.toByteArray()))
                }
                gson.toJson(obj, writer)
            }
            writer.endArray()
            val referenced = rules.flatMap { ReaderAssetReferences.ids(it.styleCssText) }.toSet()
            if (referenced.isNotEmpty()) {
                writer.name("readerAssets")
                ReaderAssetJsonCodec.write(writer, referenced, resources)
            }
            writer.endObject()
        }
        return output.toByteArray()
    }

    private class ExportLimitOutputStream(private val output: OutputStream, private val limit: Int) : OutputStream() {
        private var count = 0L
        private fun reserve(size: Int) {
            require(count + size <= limit) { "导出文件内容过大，请搜索后分批导出" }
            count += size
        }
        override fun write(value: Int) { reserve(1); output.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            reserve(length)
            output.write(bytes, offset, length)
        }
        override fun flush() = output.flush()
        override fun close() = output.close()
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun imageMime(bytes: ByteArray): String? = when {
        bytes.size >= 24 && bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte) -> "image/png"
        bytes.size >= 3 && bytes[0] == 255.toByte() && bytes[1] == 216.toByte() -> "image/jpeg"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
        else -> null
    }

    private fun imageDimensions(bytes: ByteArray): Pair<Int, Int> {
        if (imageMime(bytes) != "image/png") return runCatching {
            io.legado.app.help.reader.ReaderAssetFormat.inspect(bytes).let { it.width to it.height }
        }.getOrDefault(0 to 0)
        val width = ByteBuffer.wrap(bytes, 16, 4).int
        val height = ByteBuffer.wrap(bytes, 20, 4).int
        require(width in 1..16384 && height in 1..16384 && width.toLong() * height <= 32_000_000) {
            "背景图片尺寸过大"
        }
        return width to height
    }

    private fun isRule(value: JsonElement): Boolean =
        value.isJsonObject && value.asJsonObject.has("keyword") &&
            (value.asJsonObject.has("styleType") || value.asJsonObject.has("styleCssText"))

    private fun JsonObject.string(key: String, fallback: String = ""): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: fallback
    private fun JsonObject.bool(key: String, fallback: Boolean = false): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: fallback
    private fun JsonObject.integer(key: String, fallback: Int): Int =
        get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: fallback
}
