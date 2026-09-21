package io.legado.app.help.reader

import android.graphics.Typeface
import android.net.Uri
import android.util.LruCache
import io.legado.app.help.book.highlight.HighlightRules
import io.legado.app.model.localBook.epubcore.direct.EpubDirectResource
import io.legado.app.model.localBook.epubcore.template.EpubReaderTemplateStore
import io.legado.app.utils.FileDoc
import io.legado.app.utils.list
import io.legado.app.utils.openInputStream
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.File

/** Images and fonts shared by highlights and page templates, always served locally. */
object ReaderAssets {
    const val BACKUP_DIR = "readerAssets"
    val store by lazy { ReaderAssetStore(File(appCtx.filesDir, BACKUP_DIR)) }
    val extensions = (ReaderAssetStore.supportedTypes.keys + "jpeg").toTypedArray()
    private val typefaces = LruCache<String, Typeface>(8)

    fun importUri(uri: Uri): ReaderAsset {
        requireLocal(uri)
        val file = FileDoc.fromUri(uri, false)
        require(!file.isDir && file.size <= ReaderAssetFormat.MAX_FILE_BYTES) { "请选择不超过 64 MiB 的图片或字体" }
        return store.import(file.openInputStream().getOrThrow(), file.name)
    }

    fun addFolder(uri: Uri): ReaderAssetFolder {
        requireLocal(uri)
        val folder = FileDoc.fromUri(uri, true)
        requireNotNull(folder.list()) { "无法读取文件夹，请重新选择并授予读取权限" }
        return store.addFolder(folder.name.ifBlank { "素材文件夹" }, folder.uri.toString())
    }

    fun browse(folder: FileDoc): List<FileDoc> {
        requireLocal(folder.uri)
        val entries = requireNotNull(folder.list { file ->
            file.isDir || file.name.substringAfterLast('.', "").lowercase() in extensions
        }) { "无法读取文件夹，请重新选择并授予读取权限" }
        require(entries.size <= 5000) { "此文件夹包含超过 5000 个素材，请选择较小的子文件夹" }
        return entries.sortedWith(compareBy<FileDoc> { !it.isDir }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun references(id: String): List<String> = ReaderAssetUsage.references(id, HighlightRules.store.all(),
        EpubReaderTemplateStore.exportLibrary().templates)

    fun deleteUnused(id: String) {
        val used = references(id)
        require(used.isEmpty()) { "此素材仍被引用，请先在对应规则或页面中更换：\n" + used.joinToString("\n") }
        store.delete(id)
        synchronized(typefaces) { typefaces.evictAll() }
    }

    fun typeface(id: String): Typeface? {
        val asset = store.find(id)?.takeIf { it.kind == "font" } ?: return null
        if (asset.extension in setOf("woff", "woff2")) return null
        val file = store.file(id) ?: return null
        val key = id + ":" + store.revision()
        synchronized(typefaces) { typefaces.get(key)?.let { return it } }
        val face = runCatching { Typeface.createFromFile(file) }.getOrNull() ?: return null
        synchronized(typefaces) { typefaces.put(key, face) }
        return face
    }

    fun resource(url: String, headOnly: Boolean = false): EpubDirectResource? {
        val id = ReaderAssetReferences.idFromUrl(url) ?: return null
        val asset = store.find(id) ?: return null
        val file = store.file(id) ?: return null
        return EpubDirectResource(
            mimeType = asset.mimeType, encoding = null, statusCode = 200, reasonPhrase = "OK",
            headers = mapOf("Content-Length" to asset.size.toString(), "Cache-Control" to "private, max-age=31536000",
                "Access-Control-Allow-Origin" to "*", "X-Content-Type-Options" to "nosniff"),
            stream = if (headOnly) ByteArrayInputStream(byteArrayOf()) else file.inputStream()
        )
    }

    private fun requireLocal(uri: Uri) {
        require(uri.scheme in setOf("content", "file")) { "请选择本地图片、字体或素材文件夹" }
    }
}
