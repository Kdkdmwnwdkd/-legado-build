package io.legado.app.model.localBook.epubcore.archive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AndroidZipEpubArchiveContractTest {

    @Test
    fun `direct facade keeps fast archive and only tolerates explicit duplicate failures`() {
        val source = source("facade/EpubCoreFacade.kt")

        assertTrue("import io.legado.app.model.localBook.epubcore.archive.AndroidZipEpubArchive" in source)
        assertTrue("val rawArchive = openArchive(file)" in source)
        assertTrue("ZipEpubArchive(file)" in source)
        assertTrue("catch (error: ZipException)" in source)
        assertTrue("isDuplicateEntryFailure(error)" in source)
        assertTrue("contains(\"duplicate\", ignoreCase = true)" in source)
        assertTrue("AndroidZipEpubArchive(file)" in source)
        assertFalse("catch (error: Throwable)" in source.substringAfter("private fun openArchive"))
    }

    @Test
    fun `archive keeps one deterministic entry for duplicate central directory names`() {
        val source = source("archive/AndroidZipEpubArchive.kt")

        assertTrue("private val zipFile = AndroidZipFile(file)" in source)
        assertTrue("exact[normalized] = ref" in source)
        assertTrue("caseInsensitive.putIfAbsent" in source)
    }

    private fun source(relativePath: String): String {
        val path = "io/legado/app/model/localBook/epubcore/$relativePath"
        val file = sequenceOf(
            File("src/main/java/$path"),
            File("app/src/main/java/$path")
        ).firstOrNull(File::isFile) ?: error("EPUB source not found: $relativePath")
        return file.readText()
    }
}
