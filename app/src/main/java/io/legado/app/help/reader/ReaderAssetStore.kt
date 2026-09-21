package io.legado.app.help.reader

import com.google.gson.Gson
import io.legado.app.help.config.AtomicTextFileStore
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

class ReaderAssetStore(val directory: File) {
    private val gson = Gson()
    private val index get() = File(directory, "library.json")
    private val atomic by lazy { AtomicTextFileStore(index) }
    private var cached: ReaderAssetLibrary? = null
    private var generation = 0L

    @Synchronized
    fun library(): ReaderAssetLibrary {
        cached?.let { return it }
        atomic.recoverInterruptedCommit()
        val result = if (index.isFile) {
            require(index.length() <= 4L * 1024 * 1024) { "素材目录过大" }
            requireNotNull(gson.fromJson(index.readText(Charsets.UTF_8), ReaderAssetLibrary::class.java)) { "素材目录为空" }
        } else ReaderAssetLibrary()
        validate(result)
        cached = result
        return result
    }

    @Synchronized
    fun revision(): String = "${index.lastModified()}:$generation"

    @Synchronized
    fun invalidate() { cached = null; generation++ }

    @Synchronized
    fun find(id: String): ReaderAsset? = library().assets.find { it.id == id }

    @Synchronized
    fun file(id: String): File? {
        if (!ReaderAssetReferences.validId(id)) return null
        val asset = find(id) ?: return null
        return File(File(directory, "files"), id).takeIf { it.isFile && it.length() == asset.size }
    }

    @Synchronized
    fun import(input: InputStream, name: String, expectedId: String? = null): ReaderAsset {
        val files = File(directory, "files").apply { check(isDirectory || mkdirs()) { "无法创建素材文件夹" } }
        val temp = File.createTempFile("asset-", ".tmp", files)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            input.use { source -> FileOutputStream(temp).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    size += count
                    require(size <= ReaderAssetFormat.MAX_FILE_BYTES) { "单个素材不能超过 64 MiB" }
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
                output.fd.sync()
            } }
            val id = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            require(expectedId == null || expectedId == id) { "素材校验失败" }
            val format = ReaderAssetFormat.inspect(temp.readBytes())
            val original = library()
            val existing = original.assets.find { it.id == id }
            val asset = existing ?: ReaderAsset(id, cleanName(name), format.kind, format.mime, format.extension,
                size, format.width, format.height)
            if (existing == null) {
                require(original.assets.size < 2048) { "素材数量达到上限，请先整理素材" }
                require(original.assets.sumOf { it.size } + size <= 512L * 1024 * 1024) { "素材库超过 512 MiB，请先整理素材" }
            }
            val target = File(files, id)
            if (!target.isFile || target.length() != size || sha256(target.readBytes()) != id) {
                check(!target.exists() || target.delete()) { "无法修复素材文件" }
                check(temp.renameTo(target)) { "无法保存素材文件" }
                generation++
            }
            if (existing == null) save(original.copy(assets = original.assets + asset))
            return asset
        } finally { temp.delete() }
    }

    /** Validate the whole batch and its capacity before importing any member. */
    @Synchronized
    fun importAll(payloads: List<ReaderAssetPayload>): List<ReaderAsset> {
        val unique = payloads.distinctBy { it.id }
        unique.forEach { it.validate() }
        val original = library()
        val added = unique.filter { incoming -> original.assets.none { it.id == incoming.id } }
        require(original.assets.size + added.size <= 2048 &&
            original.assets.sumOf { it.size } + added.sumOf { it.bytes.size.toLong() } <= 512L * 1024 * 1024) {
            "素材库空间不足，请先整理素材"
        }
        return unique.map { import(it.bytes.inputStream(), it.name, it.id) }
    }

    @Synchronized
    fun payload(id: String): ReaderAssetPayload {
        val asset = find(id) ?: error("引用的素材已丢失：$id")
        return ReaderAssetPayload(id, asset.name, verifiedBytes(id))
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val original = library()
        require(original.assets.any { it.id == id }) { "素材已被删除" }
        save(original.copy(assets = original.assets.map { if (it.id == id) it.copy(name = cleanName(name)) else it }))
    }

    @Synchronized
    fun delete(id: String) {
        val original = library()
        if (original.assets.none { it.id == id }) return
        val stored = File(File(directory, "files"), id)
        save(original.copy(assets = original.assets.filterNot { it.id == id }))
        stored.delete()
    }

    @Synchronized
    fun addFolder(name: String, uri: String): ReaderAssetFolder {
        require(uri.startsWith("content://") || uri.startsWith("file://")) { "仅支持本地素材文件夹" }
        val original = library()
        original.folders.find { it.uri == uri }?.let { return it }
        require(original.folders.size < 32) { "最多保存 32 个素材来源文件夹" }
        val folder = ReaderAssetFolder(UUID.randomUUID().toString(), cleanName(name), uri)
        save(original.copy(folders = original.folders + folder))
        return folder
    }

    @Synchronized
    fun removeFolder(id: String) { save(library().let { it.copy(folders = it.folders.filterNot { folder -> folder.id == id }) }) }

    @Synchronized
    fun backupTo(target: File) {
        val source = library()
        target.mkdirs()
        // Device-specific SAF grants are not portable. Imported files are.
        source.assets.forEach { asset ->
            val bytes = verifiedBytes(asset.id)
            File(target, "files/${asset.id}").apply { parentFile?.mkdirs(); writeBytes(bytes) }
        }
        File(target, "library.json").writeText(gson.toJson(source.copy(folders = emptyList())), Charsets.UTF_8)
    }

    @Synchronized
    fun restoreFrom(source: File) {
        if (!File(source, "library.json").isFile) return
        val incoming = ReaderAssetStore(source)
        val assets = incoming.library().assets
        val original = library()
        val added = assets.filter { asset -> original.assets.none { it.id == asset.id } }
        require(original.assets.size + added.size <= 2048 &&
            original.assets.sumOf { it.size } + added.sumOf { it.size } <= 512L * 1024 * 1024) { "素材库空间不足" }
        assets.forEach { incoming.verifiedBytes(it.id) }
        assets.forEach { asset -> import(incoming.file(asset.id)!!.inputStream(), asset.name, asset.id) }
    }

    @Synchronized
    fun verifiedBytes(id: String): ByteArray {
        val file = file(id) ?: error("素材已丢失，请重新导入：" + (find(id)?.name ?: id))
        val bytes = file.readBytes()
        require(sha256(bytes) == id) { "素材已损坏，请重新导入：" + find(id)?.name }
        val format = ReaderAssetFormat.inspect(bytes)
        val asset = requireNotNull(find(id))
        require(format.kind == asset.kind && format.mime == asset.mimeType && format.extension == asset.extension &&
            format.width == asset.width && format.height == asset.height) { "素材信息与文件不一致：${asset.name}" }
        return bytes
    }

    private fun save(value: ReaderAssetLibrary) {
        validate(value)
        atomic.writeVerified(gson.toJson(value)) { gson.fromJson(it, ReaderAssetLibrary::class.java) == value }
        cached = value; generation++
    }

    private fun validate(value: ReaderAssetLibrary) {
        require(value.version == 1 && value.assets.size <= 2048 && value.folders.size <= 32) { "不支持的素材目录" }
        require(value.assets.map { it.id }.distinct().size == value.assets.size && value.assets.all {
            ReaderAssetReferences.validId(it.id) && it.kind in setOf("font", "image") && it.size in 1..ReaderAssetFormat.MAX_FILE_BYTES &&
                it.name.isNotBlank() && it.name.length <= 256 && supportedTypes[it.extension] == it.mimeType &&
                (it.kind == "image") == it.mimeType.startsWith("image/")
        }) { "素材目录内容无效" }
        require(value.assets.sumOf { it.size } <= 512L * 1024 * 1024) { "素材库超过大小上限" }
        require(value.folders.map { it.id }.distinct().size == value.folders.size && value.folders.all {
            it.id.length <= 64 && it.name.length <= 256 && it.uri.length <= 8192 &&
                (it.uri.startsWith("content://") || it.uri.startsWith("file://"))
        }) { "素材来源文件夹无效" }
    }

    companion object {
        val supportedTypes = mapOf("png" to "image/png", "jpg" to "image/jpeg", "gif" to "image/gif",
            "webp" to "image/webp", "ttf" to "font/ttf", "otf" to "font/otf", "ttc" to "font/collection",
            "woff" to "font/woff", "woff2" to "font/woff2")
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun cleanName(name: String) = name.trim().replace(Regex("[\\p{Cntrl}/\\\\]"), "_").take(256).ifBlank { "未命名素材" }
    }
}
