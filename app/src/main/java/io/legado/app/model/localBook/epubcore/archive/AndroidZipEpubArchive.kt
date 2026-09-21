package io.legado.app.model.localBook.epubcore.archive

import io.legado.app.utils.readBytesLimited
import me.ag2s.epublib.util.zip.AndroidZipEntry
import me.ag2s.epublib.util.zip.AndroidZipFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/**
 * EPUB archive backed by the bundled Android ZIP reader.
 *
 * Some real-world EPUBs contain the same central-directory name more than once (most often
 * the mandatory `mimetype` entry). java.util.zip.ZipFile rejects those archives, while the
 * Android reader already has deterministic last-entry-wins semantics. Keep that tolerance in
 * the shared Direct archive layer so metadata, chapter discovery, and resource loading use the
 * same behavior.
 */
class AndroidZipEpubArchive(file: File) : EpubArchive {

    private val zipFile = AndroidZipFile(file)
    private val entries: Map<String, EntryRef>
    private val caseInsensitiveEntries: Map<String, EntryRef>

    init {
        val exact = LinkedHashMap<String, EntryRef>()
        val caseInsensitive = LinkedHashMap<String, EntryRef>()
        val enumeration = zipFile.entries()
            ?: throw IOException("Unable to read EPUB central directory: ${file.name}")
        while (enumeration.hasMoreElements()) {
            val entry = enumeration.nextElement()
            if (entry.isDirectory) continue
            val normalized = EpubPath.normalize(entry.name)
            if (normalized.isBlank()) continue
            val ref = EntryRef(entry.name, entry.size)
            // Assignment intentionally makes the later central-directory entry authoritative.
            exact[normalized] = ref
            caseInsensitive.putIfAbsent(normalized.lowercase(Locale.ROOT), ref)
        }
        entries = exact
        caseInsensitiveEntries = caseInsensitive
    }

    override fun exists(path: String): Boolean = entryRef(path) != null

    override fun list(): List<String> = entries.keys.toList()

    override fun canonicalPath(path: String): String? {
        return entryRef(path)?.name?.let(EpubPath::normalize)
    }

    override fun readBytes(path: String, maxBytes: Long): ByteArray {
        val ref = entryRef(path) ?: error("EPUB entry not found: $path")
        if (ref.size > maxBytes) {
            throw IOException("EPUB entry is too large: $path (${ref.size} bytes)")
        }
        return openEntry(ref, path).use { it.readBytesLimited(maxBytes) }
    }

    override fun entrySize(path: String): Long? {
        return entryRef(path)?.size?.takeIf { it >= 0L }
    }

    override fun openStream(path: String): InputStream {
        val ref = entryRef(path) ?: error("EPUB entry not found: $path")
        return openEntry(ref, path)
    }

    private fun openEntry(ref: EntryRef, requestedPath: String): InputStream {
        val entry: AndroidZipEntry = zipFile.getEntry(ref.name)
            ?: error("EPUB entry not found: $requestedPath")
        return zipFile.getInputStream(entry)
    }

    private fun entryRef(path: String): EntryRef? {
        val normalized = EpubPath.normalize(path)
        return entries[normalized]
            ?: caseInsensitiveEntries[normalized.lowercase(Locale.ROOT)]
    }

    override fun close() {
        zipFile.close()
    }

    private data class EntryRef(
        val name: String,
        val size: Long
    )
}
