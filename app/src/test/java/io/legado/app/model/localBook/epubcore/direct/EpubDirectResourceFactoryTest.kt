package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.model.localBook.epubcore.archive.EpubArchive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class EpubDirectResourceFactoryTest {

    private val bytes = ByteArray(10) { it.toByte() }
    private val archive = MemoryArchive(mapOf("media/sample.mp4" to bytes))

    @Test
    fun `opens bounded byte range`() {
        val resource = EpubDirectResourceFactory.open(
            archive,
            "media/sample.mp4",
            "video/mp4",
            "bytes=2-5"
        )!!

        assertEquals(206, resource.statusCode)
        assertEquals("bytes 2-5/10", resource.headers["Content-Range"])
        assertEquals("4", resource.headers["Content-Length"])
        assertArrayEquals(byteArrayOf(2, 3, 4, 5), resource.stream.use { it.readBytes() })
    }

    @Test
    fun `supports suffix range`() {
        val resource = EpubDirectResourceFactory.open(
            archive,
            "media/sample.mp4",
            null,
            "bytes=-3"
        )!!

        assertEquals(206, resource.statusCode)
        assertArrayEquals(byteArrayOf(7, 8, 9), resource.stream.use { it.readBytes() })
    }

    @Test
    fun `rejects unsatisfiable range`() {
        val resource = EpubDirectResourceFactory.open(
            archive,
            "media/sample.mp4",
            null,
            "bytes=20-30"
        )!!

        assertEquals(416, resource.statusCode)
        assertEquals("bytes */10", resource.headers["Content-Range"])
        assertEquals("no-store, max-age=0", resource.headers["Cache-Control"])
        assertEquals("*", resource.headers["Access-Control-Allow-Origin"])
    }

    @Test
    fun `range unit is case insensitive and open end is supported`() {
        val resource = EpubDirectResourceFactory.open(
            archive,
            "media/sample.mp4",
            null,
            "BYTES = 6-"
        )!!

        assertEquals(206, resource.statusCode)
        assertEquals("bytes 6-9/10", resource.headers["Content-Range"])
        assertArrayEquals(byteArrayOf(6, 7, 8, 9), resource.stream.use { it.readBytes() })
    }

    @Test
    fun `multiple ranges are rejected instead of returning misleading first range`() {
        val resource = EpubDirectResourceFactory.open(
            archive,
            "media/sample.mp4",
            null,
            "bytes=0-1,4-5"
        )!!

        assertEquals(416, resource.statusCode)
        assertEquals("bytes */10", resource.headers["Content-Range"])
    }

    @Test
    fun `cached resource keeps range and cache semantics`() {
        val resource = EpubDirectResourceFactory.open(
            archive = archive,
            path = "media/sample.mp4",
            declaredMimeType = "video/mp4",
            rangeHeader = "bytes=1-2",
            cachedBytes = bytes
        )!!

        assertEquals(206, resource.statusCode)
        assertEquals("*", resource.headers["Access-Control-Allow-Origin"])
        assertEquals(
            "public, max-age=31536000, immutable",
            resource.headers["Cache-Control"]
        )
        assertArrayEquals(byteArrayOf(1, 2), resource.stream.use { it.readBytes() })
    }

    @Test
    fun `disk cached range seeks without opening the archive stream`() {
        val file = Files.createTempFile("epub-direct-range", ".bin").toFile()
        file.writeBytes(bytes)
        val archive = object : EpubArchive {
            override fun exists(path: String): Boolean = true
            override fun list(): List<String> = listOf("media/sample.mp4")
            override fun readBytes(path: String, maxBytes: Long): ByteArray = error("unused")
            override fun entrySize(path: String): Long = bytes.size.toLong()
            override fun openStream(path: String) = error("archive stream must not be opened")
            override fun close() = Unit
        }
        try {
            val resource = EpubDirectResourceFactory.open(
                archive = archive,
                path = "media/sample.mp4",
                declaredMimeType = "video/mp4",
                rangeHeader = "bytes=7-9",
                cachedFile = file
            )!!

            assertEquals(206, resource.statusCode)
            assertArrayEquals(byteArrayOf(7, 8, 9), resource.stream.use { it.readBytes() })
        } finally {
            file.delete()
        }
    }

    @Test
    fun `metadata only response does not open the archive stream`() {
        val archive = object : EpubArchive {
            override fun exists(path: String): Boolean = true
            override fun list(): List<String> = listOf("media/sample.mp4")
            override fun readBytes(path: String, maxBytes: Long): ByteArray = error("unused")
            override fun entrySize(path: String): Long = bytes.size.toLong()
            override fun openStream(path: String) = error("HEAD must not open the archive stream")
            override fun close() = Unit
        }

        val resource = EpubDirectResourceFactory.open(
            archive = archive,
            path = "media/sample.mp4",
            declaredMimeType = "video/mp4",
            rangeHeader = "bytes=2-5",
            openBody = false
        )!!

        assertEquals(206, resource.statusCode)
        assertEquals("bytes 2-5/10", resource.headers["Content-Range"])
        assertEquals(0, resource.stream.use { it.readBytes() }.size)
    }

    @Test
    fun `known EPUB extension overrides generic or incorrect manifest mime`() {
        assertEquals(
            "image/png",
            EpubDirectResourceFactory.resolvedMimeType("OPS/Images/Cover.PNG", "application/octet-stream")
        )
        assertEquals(
            "font/woff2",
            EpubDirectResourceFactory.resolvedMimeType("OPS/Fonts/Reader.WOFF2", "application/x-font-ttf")
        )
        assertEquals(
            "text/css",
            EpubDirectResourceFactory.resolvedMimeType("OPS/Styles/Book.CSS", "text/plain")
        )
    }

    @Test
    fun `unknown extension retains a useful manifest mime`() {
        assertEquals(
            "font/otf",
            EpubDirectResourceFactory.resolvedMimeType("OPS/Fonts/obfuscated.dat", "font/otf")
        )
    }

    @Test
    fun `stylesheet response lets WebView honor its own charset`() {
        val cssArchive = MemoryArchive(mapOf("styles/book.css" to "@charset \"windows-1252\";".toByteArray()))
        val resource = EpubDirectResourceFactory.open(
            archive = cssArchive,
            path = "styles/book.css",
            declaredMimeType = "text/css",
            rangeHeader = null
        )!!

        assertEquals("text/css", resource.mimeType)
        assertEquals(null, resource.encoding)
        assertEquals(null, resource.headers["X-Content-Type-Options"])
        resource.stream.close()
    }

    private class MemoryArchive(private val entries: Map<String, ByteArray>) : EpubArchive {
        override fun exists(path: String): Boolean = entries.containsKey(path)
        override fun list(): List<String> = entries.keys.toList()
        override fun readBytes(path: String, maxBytes: Long): ByteArray = entries.getValue(path)
        override fun entrySize(path: String): Long? = entries[path]?.size?.toLong()
        override fun openStream(path: String) = ByteArrayInputStream(entries.getValue(path))
        override fun close() = Unit
    }
}
