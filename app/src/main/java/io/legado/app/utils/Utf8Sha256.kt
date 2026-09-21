package io.legado.app.utils

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal object Utf8Sha256 {

    fun digestHex(source: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val chars = CharBuffer.wrap(source)
        val bytes = ByteBuffer.allocate(BUFFER_BYTES)
        while (true) {
            val result = encoder.encode(chars, bytes, true)
            bytes.flip()
            digest.update(bytes)
            bytes.clear()
            when {
                result.isOverflow -> continue
                result.isUnderflow -> break
                else -> result.throwException()
            }
        }
        while (true) {
            val result = encoder.flush(bytes)
            bytes.flip()
            digest.update(bytes)
            bytes.clear()
            when {
                result.isOverflow -> continue
                result.isUnderflow -> break
                else -> result.throwException()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val BUFFER_BYTES = 8 * 1024
}
