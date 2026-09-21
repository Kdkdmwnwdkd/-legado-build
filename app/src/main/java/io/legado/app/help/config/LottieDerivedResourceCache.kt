package io.legado.app.help.config

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.JsonParser
import io.legado.app.utils.Utf8Sha256
import io.legado.app.utils.decodeBase64DataUrlBytes
import splitties.init.appCtx
import java.io.File
import java.io.StringReader
import java.util.LinkedHashMap

internal data class PreparedLottieTemplate(
    val json: String,
    val fallbackAssets: Map<String, String> = emptyMap(),
    val derived: Boolean = false
)

internal object LottieDerivedResourceCache {

    private val compiler by lazy {
        LottieDerivedResourceCompiler(
            cacheRoot = File(appCtx.cacheDir, "lottie-derived-v${LottieDerivedResourceCompiler.CACHE_VERSION}"),
            decodeDataUrl = { it.decodeBase64DataUrlBytes() },
            validateDocument = AdvancedTitleConfig::hasRenderableLayers
        )
    }

    fun prepare(rawJson: String): PreparedLottieTemplate = compiler.prepare(rawJson)

    fun resourceFile(alias: String): File? = compiler.resourceFile(alias)

    fun isResourceAlias(value: String): Boolean {
        return value.startsWith(LottieDerivedResourceCompiler.RESOURCE_PREFIX)
    }

    fun clearMemory() = compiler.clearMemory()
}

internal class LottieDerivedResourceCompiler(
    private val cacheRoot: File,
    private val decodeDataUrl: (String) -> ByteArray?,
    private val validateDocument: (String) -> Boolean,
    private val writeResource: (File, ByteArray) -> Unit = { file, bytes ->
        AtomicByteFileStore(file).writeVerified(bytes) { staged ->
            isUsableResourceFile(staged, file.extension)
        }
    },
    private val writeDocument: (File, String, (String) -> Boolean) -> Unit = { file, text, verify ->
        AtomicTextFileStore(file).writeVerified(text, verify)
    }
) {

    private data class MemoryEntry(val template: PreparedLottieTemplate, val weight: Int)

    private val lock = Any()
    private val memory = LinkedHashMap<String, MemoryEntry>(4, 0.75f, true)
    private var memoryWeight = 0

    fun prepare(rawJson: String): PreparedLottieTemplate {
        if (rawJson.isBlank() || rawJson.length > MAX_DERIVABLE_JSON_CHARS) {
            return PreparedLottieTemplate(rawJson)
        }
        val sourceHash = Utf8Sha256.digestHex(rawJson)
        synchronized(lock) {
            memory[sourceHash]?.let { return it.template }
            val prepared = runCatching { prepareLocked(rawJson, sourceHash) }
                .getOrElse { PreparedLottieTemplate(rawJson) }
            cacheMemory(sourceHash, prepared)
            return prepared
        }
    }

    fun resourceFile(alias: String): File? {
        val name = alias.removePrefix(RESOURCE_PREFIX)
        if (name == alias || !RESOURCE_NAME.matches(name)) return null
        return runCatching {
            val root = File(cacheRoot, RESOURCE_DIRECTORY).canonicalFile
            val candidate = File(root, name).canonicalFile
            candidate.takeIf { it.parentFile == root }
        }.getOrNull()
    }

    fun clearMemory() = synchronized(lock) {
        memory.clear()
        memoryWeight = 0
    }

    private fun prepareLocked(rawJson: String, sourceHash: String): PreparedLottieTemplate {
        val sources = extractEmbeddedSources(rawJson)
        if (sources.isEmpty() || sources.size > MAX_ASSETS_PER_DOCUMENT) {
            return PreparedLottieTemplate(rawJson)
        }
        val fallbackAssets = LinkedHashMap<String, String>(sources.size)
        var estimatedBytes = 0L
        sources.forEach { source ->
            val alias = aliasFor(source)
            val previous = fallbackAssets.put(alias, source)
            if (previous != null && previous != source) return PreparedLottieTemplate(rawJson)
            estimatedBytes += estimateDecodedBytes(source)
            if (estimatedBytes > MAX_DERIVED_RESOURCE_BYTES) return PreparedLottieTemplate(rawJson)
        }

        val expectedAliases = sources.map(::aliasFor)
        val cached = readCachedDocument(sourceHash, expectedAliases)
        val derivedJson = cached ?: deriveDocument(rawJson, fallbackAssets)
            ?: return PreparedLottieTemplate(rawJson)
        if (cached == null && !validateCachedDocument(derivedJson, expectedAliases)) {
            return PreparedLottieTemplate(rawJson)
        }
        fallbackAssets.forEach { (alias, source) -> ensureResource(alias, source) }
        if (cached == null) storeDocument(sourceHash, derivedJson, expectedAliases)
        trimCacheBestEffort()
        return PreparedLottieTemplate(
            json = derivedJson,
            fallbackAssets = fallbackAssets,
            derived = true
        )
    }

    private fun extractEmbeddedSources(rawJson: String): List<String> {
        val result = arrayListOf<String>()
        JsonReader(StringReader(rawJson)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != "assets") {
                    reader.skipValue()
                    continue
                }
                if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                    reader.skipValue()
                    continue
                }
                reader.beginArray()
                while (reader.hasNext()) {
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                        reader.skipValue()
                        continue
                    }
                    var path: String? = null
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() == "p" && reader.peek() == JsonToken.STRING) {
                            path = reader.nextString()
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endObject()
                    path?.takeIf(::isEmbeddedImage)?.let(result::add)
                    if (result.size > MAX_ASSETS_PER_DOCUMENT) return result
                }
                reader.endArray()
            }
            reader.endObject()
        }
        return result
    }

    private fun deriveDocument(
        rawJson: String,
        fallbackAssets: Map<String, String>
    ): String? {
        val root = JsonParser.parseString(rawJson).takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val assets = root.getAsJsonArray("assets") ?: return null
        var replaced = 0
        for (element in assets) {
            val asset = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val source = asset.get("p")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?.takeIf(::isEmbeddedImage)
                ?: continue
            val alias = aliasFor(source)
            if (fallbackAssets[alias] != source) return null
            asset.addProperty("p", alias)
            asset.addProperty("u", "")
            replaced++
        }
        if (replaced == 0) return null
        return root.toString().takeIf(validateDocument)
    }

    private fun readCachedDocument(sourceHash: String, aliases: List<String>): String? {
        val file = documentFile(sourceHash) ?: return null
        return runCatching {
            AtomicTextFileStore(file).recoverInterruptedCommit()
            if (!file.isFile || file.length() !in 1..MAX_DERIVABLE_JSON_CHARS.toLong()) return null
            file.readText(Charsets.UTF_8).takeIf { validateCachedDocument(it, aliases) }
        }.getOrNull()
    }

    private fun storeDocument(sourceHash: String, json: String, aliases: List<String>) {
        val file = documentFile(sourceHash) ?: return
        runCatching {
            writeDocument(file, json) { validateCachedDocument(it, aliases) }
        }
    }

    private fun validateCachedDocument(json: String, aliases: List<String>): Boolean {
        if (!validateDocument(json)) return false
        return runCatching { extractDerivedAliases(json) == aliases }.getOrDefault(false)
    }

    private fun ensureResource(alias: String, source: String) {
        val file = resourceFile(alias) ?: return
        runCatching { AtomicByteFileStore(file).recoverInterruptedCommit() }
        if (file.length() <= MAX_SINGLE_RESOURCE_BYTES && isUsableResourceFile(file, file.extension)) {
            file.setLastModified(System.currentTimeMillis())
            return
        }
        val bytes = decodeDataUrl(source)
            ?.takeIf { it.isNotEmpty() && it.size.toLong() <= MAX_SINGLE_RESOURCE_BYTES }
            ?: return
        runCatching { writeResource(file, bytes) }
    }

    private fun extractDerivedAliases(json: String): List<String> {
        val result = arrayListOf<String>()
        JsonReader(StringReader(json)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != "assets") {
                    reader.skipValue()
                    continue
                }
                if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                    reader.skipValue()
                    continue
                }
                reader.beginArray()
                while (reader.hasNext()) {
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                        reader.skipValue()
                        continue
                    }
                    var path: String? = null
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() == "p" && reader.peek() == JsonToken.STRING) {
                            path = reader.nextString()
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endObject()
                    path?.let { candidate ->
                        if (candidate.startsWith(RESOURCE_PREFIX)) result.add(candidate)
                    }
                }
                reader.endArray()
            }
            reader.endObject()
            check(reader.peek() == JsonToken.END_DOCUMENT)
        }
        return result
    }

    private fun documentFile(sourceHash: String): File? {
        if (!HASH.matches(sourceHash)) return null
        return runCatching {
            val root = File(cacheRoot, DOCUMENT_DIRECTORY).canonicalFile
            val candidate = File(root, "$sourceHash.json").canonicalFile
            candidate.takeIf { it.parentFile == root }
        }.getOrNull()
    }

    private fun aliasFor(source: String): String {
        return RESOURCE_PREFIX + Utf8Sha256.digestHex(source) + "." + extensionFor(source)
    }

    private fun extensionFor(source: String): String {
        val metadata = source.substringBefore(',').lowercase()
        return when {
            "image/svg+xml" in metadata -> "svg"
            "image/png" in metadata -> "png"
            "image/jpeg" in metadata || "image/jpg" in metadata -> "jpg"
            "image/webp" in metadata -> "webp"
            "image/gif" in metadata -> "gif"
            "image/bmp" in metadata -> "bmp"
            else -> "img"
        }
    }

    private fun isEmbeddedImage(value: String): Boolean {
        if (!value.startsWith("data:image", ignoreCase = true)) return false
        val comma = value.indexOf(',')
        return comma > 0 && value.substring(0, comma).contains(";base64", ignoreCase = true)
    }

    private fun estimateDecodedBytes(source: String): Long {
        val payloadLength = source.length - source.indexOf(',') - 1
        return (payloadLength.coerceAtLeast(0).toLong() * 3L / 4L).coerceAtLeast(0L)
    }

    private fun cacheMemory(sourceHash: String, template: PreparedLottieTemplate) {
        val weight = template.json.length + template.fallbackAssets.values.sumOf { it.length }
        memory.remove(sourceHash)?.let { memoryWeight -= it.weight }
        if (weight > MAX_MEMORY_CHARS) return
        while (memory.isNotEmpty() && memoryWeight + weight > MAX_MEMORY_CHARS) {
            val eldest = memory.entries.iterator().next()
            memory.remove(eldest.key)
            memoryWeight -= eldest.value.weight
        }
        memory[sourceHash] = MemoryEntry(template, weight)
        memoryWeight += weight
    }

    private fun trimCacheBestEffort() = runCatching {
        trimDirectory(File(cacheRoot, RESOURCE_DIRECTORY), MAX_RESOURCE_CACHE_BYTES, MAX_CACHE_FILES)
        trimDirectory(File(cacheRoot, DOCUMENT_DIRECTORY), MAX_DOCUMENT_CACHE_BYTES, MAX_CACHE_FILES)
    }

    private fun trimDirectory(directory: File, maxBytes: Long, maxFiles: Int) {
        val files = directory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && !it.name.startsWith('.') }
            ?.take(MAX_CACHE_SCAN_FILES)
            ?.sortedBy { it.lastModified() }
            ?.toMutableList()
            ?: return
        var total = files.sumOf { it.length().coerceAtLeast(0L) }
        while (files.isNotEmpty() && (files.size > maxFiles || total > maxBytes)) {
            val file = files.removeAt(0)
            val length = file.length().coerceAtLeast(0L)
            if (file.delete()) total = (total - length).coerceAtLeast(0L)
        }
    }

    companion object {
        const val CACHE_VERSION = 1
        const val RESOURCE_PREFIX = "legado-cache://v$CACHE_VERSION/"
        private const val RESOURCE_DIRECTORY = "resources"
        private const val DOCUMENT_DIRECTORY = "documents"
        private const val MAX_DERIVABLE_JSON_CHARS = 2 * 1024 * 1024
        private const val MAX_ASSETS_PER_DOCUMENT = 128
        private const val MAX_MEMORY_CHARS = 4 * 1024 * 1024
        private const val MAX_SINGLE_RESOURCE_BYTES = 8L * 1024L * 1024L
        private const val MAX_DERIVED_RESOURCE_BYTES = 16L * 1024L * 1024L
        private const val MAX_RESOURCE_CACHE_BYTES = 64L * 1024L * 1024L
        private const val MAX_DOCUMENT_CACHE_BYTES = 8L * 1024L * 1024L
        private const val MAX_CACHE_FILES = 256
        private const val MAX_CACHE_SCAN_FILES = 1024
        private val HASH = Regex("^[0-9a-f]{64}$")
        private val RESOURCE_NAME = Regex("^[0-9a-f]{64}\\.(png|jpg|webp|gif|bmp|svg|img)$")

        private fun isUsableResourceFile(file: File, extension: String): Boolean {
            if (!file.isFile || file.length() <= 0L) return false
            val prefix = runCatching {
                file.inputStream().buffered().use { input ->
                    val bytes = ByteArray(512)
                    val read = input.read(bytes)
                    if (read <= 0) ByteArray(0) else bytes.copyOf(read)
                }
            }.getOrNull() ?: return false
            return when (extension.lowercase()) {
                "png" -> prefix.startsWithBytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
                "jpg" -> prefix.startsWithBytes(0xFF, 0xD8, 0xFF)
                "webp" -> prefix.startsWithAscii("RIFF") && prefix.hasAsciiAt(8, "WEBP")
                "gif" -> prefix.startsWithAscii("GIF87a") || prefix.startsWithAscii("GIF89a")
                "bmp" -> prefix.startsWithAscii("BM")
                "svg" -> prefix.toString(Charsets.UTF_8).contains("<svg", ignoreCase = true)
                "img" -> true
                else -> false
            }
        }

        private fun ByteArray.startsWithBytes(vararg expected: Int): Boolean {
            return size >= expected.size && expected.indices.all { index ->
                this[index].toInt() and 0xFF == expected[index]
            }
        }

        private fun ByteArray.startsWithAscii(expected: String): Boolean = hasAsciiAt(0, expected)

        private fun ByteArray.hasAsciiAt(offset: Int, expected: String): Boolean {
            if (offset < 0 || size - offset < expected.length) return false
            return expected.indices.all { index -> this[offset + index].toInt() == expected[index].code }
        }
    }
}
