package io.legado.app.model.localBook.epubcore.font

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EpubReaderFontPreparerTest {

    @Test
    fun `prepares a real file with content revision and reuses the prepared target`() {
        val root = Files.createTempDirectory("epub-reader-font-").toFile()
        try {
            val source = File(root, "reader.ttf")
            source.writeBytes(byteArrayOf(0, 1, 0, 0, 1, 2, 3, 4))
            val preparer = EpubReaderFontPreparer(File(root, "cache"))

            val prepared = preparer.prepare(source.absolutePath) ?: error("font was not prepared")
            assertEquals(
                "8f0513480785f2923c4e12c063577738866d249ef79e902db4132c0093664510",
                prepared.revision
            )
            assertEquals("font/ttf", prepared.mimeType)
            assertEquals(8L, prepared.length)
            assertTrue(File(prepared.filePath).isFile)
            assertEquals(source.readBytes().toList(), File(prepared.filePath).readBytes().toList())
            assertEquals(prepared, preparer.prepare(source.absolutePath))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rejects an unsupported font container instead of silently falling back`() {
        val root = Files.createTempDirectory("epub-reader-font-invalid-").toFile()
        try {
            val source = File(root, "reader.bin")
            source.writeBytes("not-a-font".toByteArray())
            try {
                EpubReaderFontPreparer(File(root, "cache")).prepare(source.absolutePath)
                fail("invalid font should be rejected")
            } catch (error: IllegalStateException) {
                assertTrue(error.message.orEmpty().contains("Unsupported or invalid"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a changed source gets a new content revision after invalidation`() {
        val root = Files.createTempDirectory("epub-reader-font-revision-").toFile()
        try {
            val source = File(root, "reader.otf")
            source.writeBytes("OTTOold".toByteArray())
            val preparer = EpubReaderFontPreparer(File(root, "cache"))
            val first = preparer.prepare(source.absolutePath) ?: error("font was not prepared")

            source.writeBytes("OTTOnew".toByteArray())
            preparer.invalidate()
            val second = preparer.prepare(source.absolutePath) ?: error("font was not prepared")

            assertNotEquals(first.revision, second.revision)
            assertEquals("font/otf", second.mimeType)
        } finally {
            root.deleteRecursively()
        }
    }
}
