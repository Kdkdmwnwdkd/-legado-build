package io.legado.app.model.localBook.epubcore.template

import com.google.gson.Gson
import io.legado.app.help.reader.ReaderAssetLibrary
import io.legado.app.help.reader.ReaderAssetReferences
import io.legado.app.help.reader.ReaderAssetStore
import io.legado.app.help.reader.ReaderAssets
import io.legado.app.utils.compress.SafeZipExtractor
import io.legado.app.utils.compress.SafeZipLimits
import io.legado.app.utils.readBytesLimited
import splitties.init.appCtx
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** SAF callers supply streams; package parsing and library commits run on their IO dispatcher. */
object EpubReaderTemplatePackages {
    const val mimeType = "application/zip"

    fun exportTemplate(id: String, output: OutputStream) {
        val template = requireNotNull(EpubReaderTemplateStore.resolve(id)) { "模板不存在" }
        EpubReaderTemplatePackageArchive.writeTemplate(template, output, ReaderAssets.store)
    }

    fun exportBackup(output: OutputStream) {
        EpubReaderTemplatePackageArchive.writeLibrary(EpubReaderTemplateStore.exportLibrary(), output, ReaderAssets.store)
    }

    fun importPackage(input: InputStream): List<EpubReaderTemplate> {
        val library = EpubReaderTemplatePackageArchive.read(input, appCtx.cacheDir, ReaderAssets.store)
        return EpubReaderTemplateStore.importLibrary(library)
    }
}

/** Author strings remain unchanged; referenced images/fonts travel beside the JSON document. */
internal object EpubReaderTemplatePackageArchive {
    const val MAX_PACKAGE_BYTES = 64L * 1024L * 1024L
    const val MAX_MANIFEST_BYTES = 32L * 1024L * 1024L
    private const val MAX_TEMPLATES = 512
    private const val SINGLE_FILE = "readerTemplate.json"
    private const val LIBRARY_FILE = "readerTemplates.json"
    private val limits = SafeZipLimits(
        maxEntries = 260,
        maxEntryBytes = MAX_PACKAGE_BYTES,
        maxTotalBytes = MAX_PACKAGE_BYTES,
        maxCompressionRatio = 250L
    )

    fun read(input: InputStream, temporaryRoot: File, assets: ReaderAssetStore? = null): EpubReaderTemplateLibrary {
        val directory = File(temporaryRoot, "reader-template-import-" + UUID.randomUUID())
        check(directory.mkdirs()) { "无法创建导入目录" }
        try {
            val incoming = File(directory, "incoming")
            input.use { source ->
                incoming.outputStream().use { target ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var size = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        size += count
                        require(size <= MAX_PACKAGE_BYTES) { "模板文件超过 64 MiB" }
                        target.write(buffer, 0, count)
                    }
                }
            }
            val zip = incoming.inputStream().use { it.read() == 0x50 && it.read() == 0x4b }
            val content = if (zip) readArchive(incoming, File(directory, "content")) else
                ArchiveContent(incoming.inputStream().use { it.readBytesLimited(MAX_MANIFEST_BYTES) }, null)
            val bytes = content.bytes
            val raw = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            val root = EpubReaderTemplate.parseObject(raw)
            val library = if (root.has("templates")) EpubReaderTemplateLibrary.fromJsonObject(root) else {
                EpubReaderTemplateLibrary(listOf(EpubReaderTemplate.fromJsonObject(root)))
            }
            validateSize(library)
            val referenced = referencedAssets(library)
            val incomingAssets = content.assets?.let(::ReaderAssetStore)
            val attached = incomingAssets?.library()?.assets.orEmpty()
            require(attached.size <= 128 && incomingAssets?.library()?.folders.orEmpty().isEmpty()) { "模板素材列表无效" }
            attached.forEach { incomingAssets!!.verifiedBytes(it.id) }
            if (referenced.isNotEmpty()) {
                val destination = requireNotNull(assets) { "此模板包含阅读素材，请从页面管理导入" }
                require(referenced.all { id -> attached.any { it.id == id } || destination.file(id) != null }) {
                    "模板引用的素材未附带且本地不存在，请重新导出完整模板包"
                }
            }
            if (attached.isNotEmpty()) requireNotNull(assets) { "无法保存模板素材" }.restoreFrom(requireNotNull(content.assets))
            return library
        } finally {
            // This directory is freshly generated here and never derived from a ZIP entry.
            directory.deleteRecursively()
        }
    }

    fun writeTemplate(template: EpubReaderTemplate, output: OutputStream, assets: ReaderAssetStore? = null) {
        val library = EpubReaderTemplateLibrary(listOf(template))
        library.validate()
        write(SINGLE_FILE, template.toJson(), library, output, assets)
    }

    fun writeLibrary(library: EpubReaderTemplateLibrary, output: OutputStream, assets: ReaderAssetStore? = null) {
        validateSize(library)
        write(LIBRARY_FILE, library.toJson(), library, output, assets)
    }

    private data class ArchiveContent(val bytes: ByteArray, val assets: File?)

    private fun readArchive(file: File, destination: File): ArchiveContent {
        val files = SafeZipExtractor.extract(file, destination, limits)
        val manifests = files.filter { it.name in setOf(SINGLE_FILE, LIBRARY_FILE) }
        require(manifests.size == 1) {
            "请选择模板文件或模板备份"
        }
        val manifest = manifests.single()
        val bytes = manifest.inputStream().use { it.readBytesLimited(MAX_MANIFEST_BYTES) }
        val assetDirectory = File(manifest.parentFile, ReaderAssets.BACKUP_DIR)
        val assetIndex = File(assetDirectory, "library.json")
        val incoming = assetIndex.takeIf { it.isFile }?.let { ReaderAssetStore(assetDirectory).library() }
        val allowed = setOf(manifest, assetIndex) + incoming?.assets.orEmpty().map { File(assetDirectory, "files/${it.id}") }
        require(files.all { it in allowed } && (incoming != null || files.size == 1)) { "模板包包含未知文件" }
        ZipFile(file).use { zip ->
            files.forEach { extracted ->
                val entry = requireNotNull(zip.getEntry(extracted.relativeTo(destination).invariantSeparatorsPath)) { "模板文件不完整" }
                val crc = CRC32()
                extracted.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) { val count = input.read(buffer); if (count < 0) break; crc.update(buffer, 0, count) }
                }
                if (entry.size != extracted.length() || entry.crc != crc.value) throw IOException("模板文件已损坏")
            }
        }
        return ArchiveContent(bytes, assetDirectory.takeIf { incoming != null })
    }

    private fun write(name: String, raw: String, library: EpubReaderTemplateLibrary, output: OutputStream, assets: ReaderAssetStore?) {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() <= MAX_MANIFEST_BYTES) { "模板内容超过 32 MiB" }
        val referenced = referencedAssets(library)
        require(referenced.size <= 128) { "模板包最多引用 128 个素材，请分批导出" }
        val selected = referenced.map { id -> requireNotNull(assets?.find(id)) { "模板引用的素材已丢失，请重新导入：$id" } }
        val assetIndex = Gson().toJson(ReaderAssetLibrary(assets = selected)).toByteArray(Charsets.UTF_8)
        require(bytes.size + assetIndex.size + selected.sumOf { it.size } + (selected.size + 2) * 400L <= MAX_PACKAGE_BYTES) {
            "模板和素材总量超过 64 MiB，请分批导出"
        }
        selected.forEach { assets!!.verifiedBytes(it.id) }
        ZipOutputStream(output).use { zip ->
            // Stored entries remain importable even for highly repetitive author code;
            // the importer's decompression-ratio guard can stay enabled for outside ZIPs.
            writeEntry(zip, name, bytes)
            if (selected.isNotEmpty()) {
                writeEntry(zip, "${ReaderAssets.BACKUP_DIR}/library.json", assetIndex)
                selected.forEach { writeEntry(zip, "${ReaderAssets.BACKUP_DIR}/files/${it.id}", assets!!.verifiedBytes(it.id)) }
            }
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name).apply {
            method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
            crc = CRC32().apply { update(bytes) }.value
        })
        zip.write(bytes); zip.closeEntry()
    }

    private fun referencedAssets(library: EpubReaderTemplateLibrary): Set<String> = library.templates.flatMap {
        ReaderAssetReferences.ids(it.firstPageHtml + "\n" + it.otherPageHtml + "\n" + it.css + "\n" + it.javascript)
    }.toSet()

    private fun validateSize(library: EpubReaderTemplateLibrary) {
        library.validate()
        require(library.templates.size <= MAX_TEMPLATES && library.hiddenBuiltInIds.size <= MAX_TEMPLATES) {
            "模板备份最多包含 512 项"
        }
    }
}
