package io.legado.app.utils.compress

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

class SafeZipExtractorTest {

    @Test
    fun extractsPackageWithinLimits() {
        withZip(mapOf("theme.json" to byteArrayOf(1, 2, 3))) { zip, root ->
            val output = File(root, "out")
            SafeZipExtractor.extract(zip, output, limits())

            assertArrayEquals(byteArrayOf(1, 2, 3), File(output, "theme.json").readBytes())
        }
    }

    @Test(expected = IOException::class)
    fun rejectsTraversalEntry() {
        withZip(mapOf("../outside" to byteArrayOf(1))) { zip, root ->
            SafeZipExtractor.extract(zip, File(root, "out"), limits())
        }
    }

    @Test(expected = IOException::class)
    fun rejectsExpandedTotalBeyondLimit() {
        withZip(mapOf("large.bin" to ByteArray(17))) { zip, root ->
            SafeZipExtractor.extract(
                zip,
                File(root, "out"),
                limits().copy(maxEntryBytes = 16, maxTotalBytes = 16)
            )
        }
    }

    private fun limits() = SafeZipLimits(
        maxEntries = 8,
        maxEntryBytes = 1024,
        maxTotalBytes = 2048,
        maxCompressionRatio = 1000
    )

    private fun withZip(entries: Map<String, ByteArray>, block: (File, File) -> Unit) {
        val root = createTempDirectory("safe_zip_").toFile()
        val zip = File(root, "package.zip")
        try {
            ZipOutputStream(FileOutputStream(zip)).use { output ->
                entries.forEach { (name, bytes) ->
                    output.putNextEntry(ZipEntry(name))
                    output.write(bytes)
                    output.closeEntry()
                }
            }
            block(zip, root)
        } finally {
            root.deleteRecursively()
        }
    }
}
