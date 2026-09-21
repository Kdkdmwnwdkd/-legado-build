package io.legado.app.model.localBook.epubcore.archive

import io.legado.app.utils.readBytesLimited
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile

class ZipEpubArchive(file: File) : EpubArchive {

    private val zipFile = ZipFile(file)
    private val entries: Map<String, String>
    private val caseInsensitiveEntries: Map<String, String>

    init {
        val exact = LinkedHashMap<String, String>()
        val caseInsensitive = LinkedHashMap<String, String>()
        zipFile.entries().asSequence()
            .filterNot { it.isDirectory }
            .forEach { entry ->
                val normalized = EpubPath.normalize(entry.name)
                exact[normalized] = entry.name
                caseInsensitive.putIfAbsent(normalized.lowercase(Locale.ROOT), entry.name)
            }
        entries = exact
        caseInsensitiveEntries = caseInsensitive
    }

    override fun exists(path: String): Boolean = entryName(path) != null

    override fun list(): List<String> = entries.keys.toList()

    override fun canonicalPath(path: String): String? = entryName(path)?.let(EpubPath::normalize)

    override fun readBytes(path: String, maxBytes: Long): ByteArray {
        val entryName = entryName(path) ?: error("EPUB entry not found: $path")
        val entry = zipFile.getEntry(entryName) ?: error("EPUB entry not found: $path")
        if (entry.size > maxBytes) {
            throw IOException("EPUB entry is too large: $path (${entry.size} bytes)")
        }
        return zipFile.getInputStream(entry).use { it.readBytesLimited(maxBytes) }
    }

    override fun entrySize(path: String): Long? {
        val entryName = entryName(path) ?: return null
        return zipFile.getEntry(entryName)?.size?.takeIf { it >= 0L }
    }

    override fun openStream(path: String) = synchronized(zipFile) {
        val entryName = entryName(path) ?: error("EPUB entry not found: $path")
        val entry = zipFile.getEntry(entryName) ?: error("EPUB entry not found: $path")
        zipFile.getInputStream(entry)
    }

    private fun entryName(path: String): String? {
        val normalized = EpubPath.normalize(path)
        return entries[normalized]
            ?: caseInsensitiveEntries[normalized.lowercase(Locale.ROOT)]
    }

    override fun close() {
        zipFile.close()
    }
}
