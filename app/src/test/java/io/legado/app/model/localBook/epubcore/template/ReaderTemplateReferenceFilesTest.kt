package io.legado.app.model.localBook.epubcore.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ReaderTemplateReferenceFilesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun allProfileFilesAreCommittedBeforeTheTemplateCanBeDeleted() {
        val one = File(temporaryFolder.root, "one.json").apply { writeText("old one") }
        val two = File(temporaryFolder.root, "two.json").apply { writeText("old two") }
        val result = ReaderTemplateReferenceFiles.withoutReferences(linkedMapOf(one to "new one", two to "new two")) {
            assertEquals("new one", one.readText())
            assertEquals("new two", two.readText())
            "deleted"
        }
        assertEquals("deleted", result)
    }

    @Test
    fun failingTemplateCommitRestoresOriginalProfileBytesAndAbsentFiles() {
        val one = File(temporaryFolder.root, "one.json").apply { writeText("original\r\n  ") }
        val two = File(temporaryFolder.root, "two.json")
        assertThrows(IOException::class.java) {
            ReaderTemplateReferenceFiles.withoutReferences(linkedMapOf(one to "new one", two to "new two")) {
                throw IOException("library commit failed")
            }
        }
        assertEquals("original\r\n  ", one.readText())
        assertFalse(two.exists())
    }

    @Test
    fun failingSecondProfileWriteRollsBackTheFirstAndNeverDeletesTheTemplate() {
        val one = File(temporaryFolder.root, "one.json").apply { writeText("old one") }
        val two = File(temporaryFolder.root, "two.json").apply { writeText("old two") }
        val blockedStaging = File(temporaryFolder.root, ".two.json.staging").apply { mkdir() }
        File(blockedStaging, "blocker").writeText("cannot remove this directory as a file")
        var deleted = false
        assertThrows(IOException::class.java) {
            ReaderTemplateReferenceFiles.withoutReferences(linkedMapOf(one to "new one", two to "new two")) {
                deleted = true
            }
        }
        assertFalse(deleted)
        assertEquals("old one", one.readText())
        assertEquals("old two", two.readText())
    }

    @Test
    fun noMatchingProfilesStillCommitsTheLibrary() {
        var committed = false
        ReaderTemplateReferenceFiles.withoutReferences(emptyMap()) { committed = true }
        assertTrue(committed)
    }
}
