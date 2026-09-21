package io.legado.app.model.localBook.epubcore.font

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

data class EpubPreparedReaderFont(
    val source: String,
    val filePath: String,
    val revision: String,
    val mimeType: String,
    val length: Long
)

class EpubReaderFontPreparer private constructor(
    private val cacheDirectory: File,
    private val contentResolver: ContentResolver?
) {

    constructor(context: Context) : this(
        cacheDirectory = File(context.applicationContext.cacheDir, CACHE_DIRECTORY),
        contentResolver = context.applicationContext.contentResolver
    )

    internal constructor(cacheDirectory: File) : this(
        cacheDirectory = cacheDirectory,
        contentResolver = null
    )

    @Volatile
    private var cachedFont: EpubPreparedReaderFont? = null

    fun cached(source: String?): EpubPreparedReaderFont? {
        val normalized = source?.takeIf { it.isNotBlank() } ?: return null
        return cachedFont?.takeIf {
            it.source == normalized && File(it.filePath).let { file ->
                file.isFile && file.length() == it.length
            }
        }
    }

    @Synchronized
    fun prepare(source: String?): EpubPreparedReaderFont? {
        val normalized = source?.takeIf { it.isNotBlank() } ?: run {
            cachedFont = null
            return null
        }
        cached(normalized)?.let { return it }

        val directory = cacheDirectory.apply {
            check(isDirectory || mkdirs()) { "Unable to create EPUB reader font cache" }
        }
        val temp = File.createTempFile("reader-font-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val header = ByteArray(FONT_HEADER_BYTES)
            var headerLength = 0
            var total = 0L
            openSource(normalized).use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        check(total <= MAX_FONT_BYTES) { "Custom reader font is too large" }
                        if (headerLength < header.size) {
                            val copied = minOf(read, header.size - headerLength)
                            buffer.copyInto(header, headerLength, 0, copied)
                            headerLength += copied
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            check(total > 0L) { "Custom reader font is empty" }
            val format = detectFormat(header, headerLength)
                ?: error("Unsupported or invalid custom reader font")
            val revision = digest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
            val target = File(directory, "$revision.${format.extension}")
            if (!target.isFile || target.length() != total) {
                if (target.exists() && !target.delete()) {
                    error("Unable to replace cached custom reader font")
                }
                if (!temp.renameTo(target)) {
                    FileInputStream(temp).use { input ->
                        FileOutputStream(target).use { output ->
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }
                }
            }
            check(target.isFile && target.length() == total) {
                "Custom reader font cache verification failed"
            }
            runCatching { target.setLastModified(System.currentTimeMillis()) }
            return EpubPreparedReaderFont(
                source = normalized,
                filePath = target.absolutePath,
                revision = revision,
                mimeType = format.mimeType,
                length = total
            ).also { cachedFont = it }
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Custom EPUB reader font could not be prepared: " +
                    (throwable.message ?: throwable.javaClass.simpleName),
                throwable
            )
        } finally {
            runCatching { temp.delete() }
        }
    }

    @Synchronized
    fun invalidate() {
        cachedFont = null
    }

    private fun openSource(source: String): InputStream {
        return if (source.startsWith("content://", ignoreCase = true)) {
            contentResolver?.openInputStream(Uri.parse(source))
                ?: error("Custom reader font provider returned no data")
        } else {
            val file = if (source.startsWith("file://", ignoreCase = true)) {
                File(Uri.parse(source).path ?: error("Invalid custom reader font path"))
            } else {
                File(source)
            }
            check(file.isFile) { "Custom reader font file does not exist" }
            FileInputStream(file)
        }
    }

    private fun detectFormat(header: ByteArray, length: Int): FontFormat? {
        if (length < 4) return null
        val signature = header.copyOf(4).toString(Charsets.ISO_8859_1)
        return when {
            header[0] == 0.toByte() && header[1] == 1.toByte() &&
                header[2] == 0.toByte() && header[3] == 0.toByte() -> FontFormat.Ttf
            signature == "true" || signature == "typ1" -> FontFormat.Ttf
            signature == "OTTO" -> FontFormat.Otf
            signature == "ttcf" -> FontFormat.Collection
            signature == "wOFF" -> FontFormat.Woff
            signature == "wOF2" -> FontFormat.Woff2
            else -> null
        }
    }

    private enum class FontFormat(val extension: String, val mimeType: String) {
        Ttf("ttf", "font/ttf"),
        Otf("otf", "font/otf"),
        Collection("ttc", "font/collection"),
        Woff("woff", "font/woff"),
        Woff2("woff2", "font/woff2")
    }

    private companion object {
        const val CACHE_DIRECTORY = "epub-reader-fonts"
        const val FONT_HEADER_BYTES = 4
        const val MAX_FONT_BYTES = 64L * 1024L * 1024L
    }
}
