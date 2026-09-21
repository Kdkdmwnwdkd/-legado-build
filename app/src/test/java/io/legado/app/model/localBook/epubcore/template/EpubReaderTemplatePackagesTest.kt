package io.legado.app.model.localBook.epubcore.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubReaderTemplatePackagesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun singlePackageRoundTripPreservesComplexAuthorCode() {
        val template = exampleTemplate().copy(
            firstPageHtml = "<article>\r\n<!-- 原样保留 -->\n<section data-reader-flow=\"body\"></section></article>  ",
            css = "@supports(display:grid){ .页 :is(p, h1){ --note:'猫咪\uD83D\uDC31'; } }\r\n  ",
            javascript = "const a = `模板 ${'$'}{1 + 1}`;\r\nwindow.custom = () => '</script>';  "
        )
        val output = ByteArrayOutputStream()
        EpubReaderTemplatePackageArchive.writeTemplate(template, output)
        assertEquals(EpubReaderTemplateLibrary(listOf(template)), read(output.toByteArray()))
    }

    @Test
    fun independentBackupRoundTripKeepsAllEntriesAndHiddenState() {
        val library = EpubReaderTemplateLibrary(
            listOf(exampleTemplate("user.one"), exampleTemplate("builtin.hidden")), setOf("builtin.hidden")
        )
        val output = ByteArrayOutputStream()
        EpubReaderTemplatePackageArchive.writeLibrary(library, output)
        assertEquals(library, read(output.toByteArray()))
    }

    @Test
    fun legacyRawSingleAndLibraryDocumentsRemainReadable() {
        val template = exampleTemplate()
        val library = EpubReaderTemplateLibrary(listOf(template))
        assertEquals(library, read(template.toJson().toByteArray(Charsets.UTF_8)))
        assertEquals(library, read(library.toJson().toByteArray(Charsets.UTF_8)))
        assertEquals(library, read(("\uFEFF" + template.toJson()).toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun supportedManifestInsideSingleFolderCanBeImported() {
        val template = exampleTemplate()
        assertEquals(listOf(template), read(zip("theme/readerTemplate.json" to template.toJson())).templates)
    }

    @Test
    fun traversalAbsoluteAndBackslashPathsAreRejectedAndTemporaryFilesRemoved() {
        listOf("../readerTemplate.json", "/readerTemplate.json", "C:/readerTemplate.json", "..\\readerTemplate.json")
            .forEach { name ->
                val root = temporaryFolder.newFolder()
                assertThrows(IOException::class.java) {
                    EpubReaderTemplatePackageArchive.read(ByteArrayInputStream(zip(name to exampleTemplate().toJson())), root)
                }
                assertTrue(root.listFiles().orEmpty().isEmpty())
            }
        assertFalse(File(temporaryFolder.root, "readerTemplate.json").exists())
    }

    @Test
    fun ambiguousOrDuplicatePathsCannotChooseAnArbitraryManifest() {
        val raw = exampleTemplate().toJson()
        assertThrows(IOException::class.java) {
            read(zip("readerTemplate.json" to raw, "READERTEMPLATE.json" to raw))
        }
        assertThrows(IllegalArgumentException::class.java) {
            read(zip("first/readerTemplate.json" to raw, "second/readerTemplate.json" to raw))
        }
        assertThrows(IllegalArgumentException::class.java) { read(zip("unrelated.json" to raw)) }
    }

    @Test
    fun corruptStoredZipFailsItsChecksumEvenWhenJsonStillParses() {
        val template = exampleTemplate().copy(css = "corruption-marker-A")
        val output = ByteArrayOutputStream()
        EpubReaderTemplatePackageArchive.writeTemplate(template, output)
        val bytes = output.toByteArray()
        val marker = "corruption-marker-A".toByteArray(Charsets.UTF_8)
        val index = bytes.indices.first { start ->
            start + marker.size <= bytes.size && marker.indices.all { offset -> bytes[start + offset] == marker[offset] }
        }
        bytes[index + marker.lastIndex] = 'B'.code.toByte()
        assertThrows(IOException::class.java) { read(bytes) }
    }

    @Test
    fun malformedUtf8AndTruncatedArchivesAreNotImportedWithReplacementCharacters() {
        assertThrows(IOException::class.java) { read(byteArrayOf(0xc3.toByte(), 0x28)) }
        val raw = zip("readerTemplate.json" to exampleTemplate().toJson())
        assertThrows(IOException::class.java) { read(raw.copyOf(raw.size / 2)) }
    }

    @Test
    fun libraryWithDuplicateIdsIsRejectedBeforeAnyImport() {
        val template = exampleTemplate().toJson()
        val json = "{\"schemaVersion\":1,\"templates\":[" + template + "," + template + "]}"
        assertThrows(IllegalArgumentException::class.java) { read(zip("readerTemplates.json" to json)) }
    }

    @Test
    fun exportedRepetitiveCodeStillPassesTheImportCompressionGuard() {
        val template = exampleTemplate().copy(css = "/*" + "same-author-text ".repeat(80_000) + "*/")
        val output = ByteArrayOutputStream()
        EpubReaderTemplatePackageArchive.writeTemplate(template, output)
        assertEquals(template, read(output.toByteArray()).templates.single())
    }

    @Test
    fun oversizedStreamsStopAndRemoveTheirTemporaryDirectory() {
        val root = temporaryFolder.newFolder()
        val stream = object : InputStream() {
            var delivered = 0L
            override fun read(): Int { delivered++; return 'x'.code }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                bytes.fill('x'.code.toByte(), offset, offset + length)
                delivered += length
                return length
            }
        }
        assertThrows(IllegalArgumentException::class.java) { EpubReaderTemplatePackageArchive.read(stream, root) }
        assertTrue(stream.delivered <= EpubReaderTemplatePackageArchive.MAX_PACKAGE_BYTES + DEFAULT_BUFFER_SIZE)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun oversizedManifestAndTooManyTemplatesAreRejectedBeforeExportWrites() {
        val output = ByteArrayOutputStream()
        val many = EpubReaderTemplateLibrary((0..512).map { exampleTemplate("user.$it") })
        assertThrows(IllegalArgumentException::class.java) { EpubReaderTemplatePackageArchive.writeLibrary(many, output) }
        assertEquals(0, output.size())
        val tooLarge = exampleTemplate().copy(css = "x".repeat(EpubReaderTemplatePackageArchive.MAX_MANIFEST_BYTES.toInt()))
        assertThrows(IllegalArgumentException::class.java) { EpubReaderTemplatePackageArchive.writeTemplate(tooLarge, output) }
        assertEquals(0, output.size())
    }

    private fun read(bytes: ByteArray): EpubReaderTemplateLibrary {
        val root = temporaryFolder.newFolder()
        return EpubReaderTemplatePackageArchive.read(ByteArrayInputStream(bytes), root).also {
            assertTrue(root.listFiles().orEmpty().isEmpty())
        }
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, raw) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(raw.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
