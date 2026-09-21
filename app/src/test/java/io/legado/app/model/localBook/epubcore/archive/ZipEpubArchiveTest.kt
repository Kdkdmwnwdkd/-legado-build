package io.legado.app.model.localBook.epubcore.archive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipEpubArchiveTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `resource lookup falls back to archive path case insensitively`() {
        val entries = linkedMapOf(
            "OEBPS/Text/Chapter.XHTML" to "chapter".toByteArray(),
            "OEBPS/Styles/Book.CSS" to "css".toByteArray(),
            "OEBPS/Images/Cover.PNG" to byteArrayOf(1, 2, 3),
            "OEBPS/Fonts/Reader.WOFF2" to byteArrayOf(4, 5, 6, 7)
        )
        val archive = ZipEpubArchive(createArchive(entries))

        archive.use {
            assertTrue(it.exists("oebps/text/chapter.xhtml"))
            assertEquals("chapter", it.readText("oebps/text/chapter.xhtml"))
            assertEquals("css", it.readText("OEBPS/styles/book.css"))
            assertArrayEquals(byteArrayOf(1, 2, 3), it.readBytes("oebps/images/cover.png"))
            assertEquals(4L, it.entrySize("oebps/fonts/reader.woff2"))
            assertArrayEquals(
                byteArrayOf(4, 5, 6, 7),
                it.openStream("oebps/fonts/reader.woff2").use { stream -> stream.readBytes() }
            )
            assertEquals("OEBPS/Images/Cover.PNG", it.canonicalPath("oebps/images/cover.png"))
            assertFalse(it.exists("OEBPS/Images/missing.png"))
        }
    }

    @Test
    fun `exact path wins when archive contains case variants`() {
        val archive = ZipEpubArchive(
            createArchive(
                linkedMapOf(
                    "OEBPS/Images/Cover.png" to "upper".toByteArray(),
                    "OEBPS/images/cover.png" to "lower".toByteArray()
                )
            )
        )

        archive.use {
            assertEquals("upper", it.readText("OEBPS/Images/Cover.png"))
            assertEquals("lower", it.readText("OEBPS/images/cover.png"))
            assertEquals("OEBPS/Images/Cover.png", it.canonicalPath("OEBPS/Images/Cover.png"))
            assertEquals("OEBPS/images/cover.png", it.canonicalPath("OEBPS/images/cover.png"))
        }
    }

    private fun createArchive(entries: Map<String, ByteArray>): File {
        return temporaryFolder.newFile("fixture-${System.nanoTime()}.epub").also { file ->
            ZipOutputStream(file.outputStream()).use { output ->
                entries.forEach { (name, bytes) ->
                    output.putNextEntry(ZipEntry(name))
                    output.write(bytes)
                    output.closeEntry()
                }
            }
        }
    }
}
