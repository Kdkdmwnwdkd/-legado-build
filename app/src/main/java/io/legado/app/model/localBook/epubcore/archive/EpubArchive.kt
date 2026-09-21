package io.legado.app.model.localBook.epubcore.archive

import java.io.Closeable
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale

interface EpubArchive : Closeable {
    fun exists(path: String): Boolean
    fun list(): List<String>
    fun canonicalPath(path: String): String? {
        val normalized = EpubPath.normalize(path)
        var caseInsensitiveMatch: String? = null
        list().forEach { entry ->
            val candidate = EpubPath.normalize(entry)
            if (candidate == normalized) return candidate
            if (caseInsensitiveMatch == null &&
                candidate.lowercase(Locale.ROOT) == normalized.lowercase(Locale.ROOT)
            ) {
                caseInsensitiveMatch = candidate
            }
        }
        return caseInsensitiveMatch
    }
    fun readBytes(path: String, maxBytes: Long = DEFAULT_MAX_ENTRY_BYTES): ByteArray
    fun entrySize(path: String): Long? = null
    fun openStream(path: String): InputStream = ByteArrayInputStream(readBytes(path))

    fun readText(path: String): String = readBytes(path).toString(Charsets.UTF_8)

    companion object {
        const val DEFAULT_MAX_ENTRY_BYTES = 32L * 1024L * 1024L
    }
}
