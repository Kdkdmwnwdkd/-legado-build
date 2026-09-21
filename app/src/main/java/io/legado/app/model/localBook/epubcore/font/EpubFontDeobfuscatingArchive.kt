package io.legado.app.model.localBook.epubcore.font

import io.legado.app.constant.AppLog
import io.legado.app.model.localBook.epubcore.EpubRegex
import io.legado.app.model.localBook.epubcore.archive.EpubArchive
import io.legado.app.model.localBook.epubcore.archive.EpubPath
import io.legado.app.model.localBook.epubcore.pkg.XmlTools
import io.legado.app.model.localBook.epubcore.pkg.attr
import io.legado.app.model.localBook.epubcore.pkg.elements
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest

internal class EpubFontDeobfuscatingArchive private constructor(
    private val delegate: EpubArchive,
    private val transformations: Map<String, Transformation>
) : EpubArchive {

    override fun exists(path: String): Boolean = delegate.exists(path)

    override fun list(): List<String> = delegate.list()

    override fun canonicalPath(path: String): String? = delegate.canonicalPath(path)

    override fun readBytes(path: String, maxBytes: Long): ByteArray {
        val bytes = delegate.readBytes(path, maxBytes)
        transformation(path)?.apply(bytes)
        return bytes
    }

    override fun entrySize(path: String): Long? = delegate.entrySize(path)

    override fun openStream(path: String): InputStream {
        val source = delegate.openStream(path)
        val transformation = transformation(path) ?: return source
        return XorPrefixInputStream(source, transformation.key, transformation.prefixLength)
    }

    override fun close() = delegate.close()

    private fun transformation(path: String): Transformation? {
        val canonical = delegate.canonicalPath(EpubPath.stripFragment(path)) ?: return null
        return transformations[canonical]
    }

    private data class Transformation(
        val key: ByteArray,
        val prefixLength: Int
    ) {
        fun apply(bytes: ByteArray) {
            repeat(minOf(prefixLength, bytes.size)) { index ->
                bytes[index] = (bytes[index].toInt() xor key[index % key.size].toInt()).toByte()
            }
        }
    }

    private class XorPrefixInputStream(
        source: InputStream,
        private val key: ByteArray,
        private val prefixLength: Int
    ) : FilterInputStream(source) {

        private var position = 0L
        private var markedPosition = -1L

        override fun read(): Int {
            val value = super.read()
            if (value < 0) return value
            val decoded = if (position < prefixLength) {
                value xor (key[(position % key.size).toInt()].toInt() and 0xff)
            } else {
                value
            }
            position++
            return decoded
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count <= 0) return count
            val transformed = minOf(count.toLong(), (prefixLength - position).coerceAtLeast(0L)).toInt()
            repeat(transformed) { relativeIndex ->
                val absolutePosition = position + relativeIndex
                val bufferIndex = offset + relativeIndex
                buffer[bufferIndex] = (
                    buffer[bufferIndex].toInt() xor
                        key[(absolutePosition % key.size).toInt()].toInt()
                    ).toByte()
            }
            position += count
            return count
        }

        override fun skip(byteCount: Long): Long {
            return super.skip(byteCount).also { position += it }
        }

        @Synchronized
        override fun mark(readLimit: Int) {
            super.mark(readLimit)
            markedPosition = position
        }

        @Synchronized
        override fun reset() {
            super.reset()
            if (markedPosition >= 0L) position = markedPosition
        }
    }

    companion object {
        private const val ENCRYPTION_PATH = "META-INF/encryption.xml"
        private const val MAX_ENCRYPTION_BYTES = 1L * 1024L * 1024L
        private const val IDPF_ALGORITHM = "http://www.idpf.org/2008/embedding"
        private const val ADOBE_ALGORITHM = "http://ns.adobe.com/pdf/enc#RC"
        private const val IDPF_PREFIX_LENGTH = 1040
        private const val ADOBE_PREFIX_LENGTH = 1024
        private val SCHEME = EpubRegex.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:")
        private val UUID_PREFIX = EpubRegex.compile("^urn:uuid:", RegexOption.IGNORE_CASE)
        private val XML_WHITESPACE = setOf(' ', '\t', '\r', '\n')

        fun wrap(delegate: EpubArchive, publicationIdentifier: String?): EpubArchive {
            val encryptionPath = delegate.canonicalPath(ENCRYPTION_PATH) ?: return delegate
            val document = runCatching {
                XmlTools.parse(delegate.readBytes(encryptionPath, MAX_ENCRYPTION_BYTES))
            }.getOrElse { error ->
                AppLog.putDebug("EPUB font encryption metadata could not be parsed", error)
                return delegate
            }
            val normalizedIdentifier = publicationIdentifier
                ?.filterNot(XML_WHITESPACE::contains)
                ?.takeIf { it.isNotEmpty() }
            val idpfKey by lazy {
                normalizedIdentifier?.let { identifier ->
                    MessageDigest.getInstance("SHA-1").digest(identifier.toByteArray(Charsets.UTF_8))
                }
            }
            val adobeKey by lazy { normalizedIdentifier?.let(::adobeKey) }
            val transformations = LinkedHashMap<String, Transformation>()
            document.elements("EncryptedData").forEach { encryptedData ->
                val algorithm = encryptedData.elements("EncryptionMethod")
                    .firstOrNull()
                    ?.let { it.attr("Algorithm") ?: it.attr("algorithm") }
                    ?.trim()
                    ?: return@forEach
                val transformation = when (algorithm) {
                    IDPF_ALGORITHM -> idpfKey?.let { Transformation(it, IDPF_PREFIX_LENGTH) }
                    ADOBE_ALGORITHM -> adobeKey?.let { Transformation(it, ADOBE_PREFIX_LENGTH) }
                    else -> null
                } ?: return@forEach
                val uri = encryptedData.elements("CipherReference")
                    .firstOrNull()
                    ?.let { it.attr("URI") ?: it.attr("uri") }
                    ?.trim()
                    ?: return@forEach
                val path = archivePath(delegate, uri) ?: return@forEach
                transformations.putIfAbsent(path, transformation)
            }
            return if (transformations.isEmpty()) {
                delegate
            } else {
                EpubFontDeobfuscatingArchive(delegate, transformations)
            }
        }

        private fun archivePath(archive: EpubArchive, uri: String): String? {
            if (uri.isBlank() || uri.startsWith("//") || SCHEME.containsMatchIn(uri)) return null
            val normalized = EpubPath.stripFragment(EpubPath.resolve("", uri))
                .takeIf { it.isNotBlank() } ?: return null
            return archive.canonicalPath(normalized)
        }

        private fun adobeKey(identifier: String): ByteArray? {
            val hex = identifier
                .replace(UUID_PREFIX, "")
                .replace("-", "")
            if (hex.length != 32 || hex.any { it.digitToIntOrNull(16) == null }) return null
            return ByteArray(16) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }
    }
}
