package io.legado.app.help.reader

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

internal object ReaderAssetFixtures {
    // A real 2 × 3 JPEG; Android's compile classpath has no java.desktop module.
    fun jpeg(): ByteArray = Base64.getDecoder().decode(
        "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/" +
            "2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/" +
            "wAARCAADAAIDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/" +
            "8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD5/ooooA//2Q=="
    )
    fun png(red: Int = 255, width: Int = 1, height: Int = 1): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        fun chunk(tag: String, data: ByteArray) {
            val body = tag.toByteArray(Charsets.US_ASCII) + data
            output.write(ByteBuffer.allocate(4).putInt(data.size).array())
            output.write(body)
            output.write(ByteBuffer.allocate(4).putInt(CRC32().apply { update(body) }.value.toInt()).array())
        }
        chunk("IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(6).put(0).put(0).put(0).array())
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { it.write(byteArrayOf(0, red.toByte(), 0, 0, 255.toByte())) }
        chunk("IDAT", compressed.toByteArray()); chunk("IEND", byteArrayOf())
        return output.toByteArray()
    }

    // Minimal table-directory fixture for bounded format inspection, not a renderable font.
    fun sfnt(): ByteArray = ByteBuffer.allocate(32).putInt(65536).putShort(1).putShort(16).putShort(0).putShort(0)
        .put("name".toByteArray()).putInt(0).putInt(28).putInt(4).putInt(0).array()
}
