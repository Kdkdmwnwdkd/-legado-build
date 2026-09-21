package io.legado.app.ui.association

import io.legado.app.help.http.decompressed
import io.legado.app.utils.readBytesLimited
import okhttp3.ResponseBody
import java.io.ByteArrayInputStream
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

internal const val MAX_LEGACY_IMPORT_BYTES = 4L * 1024L * 1024L

internal inline fun <T> ResponseBody.useLimitedImportStream(
    maxBytes: Long = MAX_LEGACY_IMPORT_BYTES,
    block: (InputStream) -> T
): T {
    val bytes = decompressed().byteStream().use { it.readBytesLimited(maxBytes) }
    return ByteArrayInputStream(bytes).use(block)
}

internal fun ResponseBody.readLimitedImportText(
    maxBytes: Long = MAX_LEGACY_IMPORT_BYTES
): String {
    return useLimitedImportStream(maxBytes) { input ->
        input.reader(Charsets.UTF_8).readText()
    }
}

/**
 * Reads the auto-task response when a server may return gzip bytes even after
 * the response headers have been normalized by an HTTP implementation.
 */
internal fun ResponseBody.readLimitedImportTextWithGzip(
    maxBytes: Long = MAX_LEGACY_IMPORT_BYTES
): String {
    val bytes = decompressed().byteStream().use { raw ->
        val buffered = BufferedInputStream(raw)
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        val payload = if (first == 0x1f && second == 0x8b) {
            GZIPInputStream(buffered)
        } else {
            buffered
        }
        payload.use { it.readBytesLimited(maxBytes) }
    }
    return bytes.toString(Charsets.UTF_8)
}
