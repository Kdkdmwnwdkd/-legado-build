package io.legado.app.help.storage

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class RestorePackageDirectoryTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun directory(name: String, value: String): File = temporary.newFolder(name).also {
        File(it, "resource.txt").writeText(value)
    }

    @Test fun failedCommitRestoresOldFilesAndReachesTheOuterJournal() {
        val source = directory("source", "new")
        val target = directory("target", "old")
        val failure = IOException("injected restore failure")
        val thrown = assertThrows(IOException::class.java) {
            Restore.restorePackageDirectory(source, target) { throw failure }
        }
        assertSame(failure, thrown)
        assertEquals("old", File(target, "resource.txt").readText())
        assertEquals("new", File(source, "resource.txt").readText())
    }

    @Test fun invalidSourceCannotRemoveAnExistingLibrary() {
        val source = temporary.newFile("source-file")
        val target = directory("target", "old")
        assertThrows(IllegalArgumentException::class.java) {
            Restore.restorePackageDirectory(source, target)
        }
        assertEquals("old", File(target, "resource.txt").readText())
    }

    @Test fun successPublishesCompleteNestedFilesBeforeInvalidatingCache() {
        val source = directory("source", "new")
        val target = directory("target", "old")
        File(source, "nested").mkdir()
        File(source, "nested/image.bin").writeBytes(byteArrayOf(1, 2, 3))
        var invalidated = false
        Restore.restorePackageDirectory(source, target) {
            assertEquals("new", File(target, "resource.txt").readText())
            assertArrayEquals(byteArrayOf(1, 2, 3), File(target, "nested/image.bin").readBytes())
            invalidated = true
        }
        assertTrue(invalidated)
        assertEquals(setOf("source", "target"), temporary.root.list()!!.toSet())
    }

    @Test fun omittedPackageDoesNotChangeCurrentFilesOrCache() {
        val target = directory("target", "old")
        Restore.restorePackageDirectory(File(temporary.root, "absent"), target) {
            fail("A backup without this package must not change its cache")
        }
        assertEquals("old", File(target, "resource.txt").readText())
    }

    @Test fun failedConfigurationReloadRestoresOriginalBytesAndReportsFailure() {
        val source = temporary.newFile("source.json").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val oldBytes = byteArrayOf(0xff.toByte(), 0, 4)
        val target = temporary.newFile("target.json").apply { writeBytes(oldBytes) }
        val failure = IOException("injected configuration reload failure")
        val thrown = assertThrows(IOException::class.java) {
            Restore.restoreFile(source, target) { throw failure }
        }
        assertSame(failure, thrown)
        assertArrayEquals(oldBytes, target.readBytes())
    }

    @Test fun invalidConfigurationSourceDoesNotDeleteAnExistingFile() {
        val source = temporary.newFolder("source-directory")
        val target = temporary.newFile("target.json").apply { writeText("saved") }
        assertThrows(IllegalArgumentException::class.java) { Restore.restoreFile(source, target) }
        assertEquals("saved", target.readText())
    }

    @Test fun configurationRestorePublishesExactBytesBeforeReloading() {
        val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte(), 0, 1, 2)
        val source = temporary.newFile("source.json").apply { writeBytes(bytes) }
        val target = temporary.newFile("target.json").apply { writeText("old") }
        var reloaded = false
        Restore.restoreFile(source, target) {
            assertArrayEquals(bytes, target.readBytes())
            reloaded = true
        }
        assertTrue(reloaded)
        assertEquals(setOf("source.json", "target.json"), temporary.root.list()!!.toSet())
    }
}
