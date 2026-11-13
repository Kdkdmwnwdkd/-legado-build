package com.shimmer.reader.help.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupSummaryTest {

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun count(bytes: ByteArray, size: Long = bytes.size.toLong()): BackupSummary? {
        return BackupSummaryReader.readFrom(ByteArrayInputStream(bytes), size)
    }

    @Test
    fun countsKnownArrays() {
        val zip = zipOf(
            "bookshelf.json" to """[{"name":"a"},{"name":"b"},{"name":"c"}]""",
            "bookmark.json" to """[{"bookName":"a"},{"bookName":"b"}]""",
            "bookSource.json" to """[{},{},{},{},{}]""",
            "bookGroup.json" to """[{"groupName":"默认"}]""",
            "readRecord.json" to """[{},{},{},{}]""",
            "config.xml" to "<map />"
        )
        val summary = count(zip)
        assertNotNull(summary)
        val parsed = requireNotNull(summary)
        assertEquals(3, parsed.books)
        assertEquals(2, parsed.bookmarks)
        assertEquals(5, parsed.sources)
        assertEquals(1, parsed.groups)
        assertEquals(4, parsed.readRecords)
        assertTrue(parsed.sizeBytes > 0)
        assertTrue(!parsed.isEmpty)
    }

    @Test
    fun missingFilesCountAsZero() {
        val summary = count(zipOf("bookshelf.json" to """[{"name":"a"}]"""))
        assertNotNull(summary)
        assertEquals(1, summary!!.books)
        assertEquals(0, summary.bookmarks)
        assertEquals(0, summary.sources)
        assertEquals(0, summary.groups)
        assertEquals(0, summary.readRecords)
    }

    @Test
    fun nonArrayContentCountsAsZero() {
        val summary = count(
            zipOf(
                "bookshelf.json" to """{"books":[1,2,3]}""",
                "bookSource.json" to """[{"a":1}]"""
            )
        )
        assertNotNull(summary)
        assertEquals(0, summary!!.books)
        assertEquals(1, summary.sources)
    }

    @Test
    fun brokenJsonKeepsReadingOtherEntries() {
        val summary = count(
            zipOf(
                "bookshelf.json" to """[{"name": 未闭合""",
                "bookmark.json" to """[{},{}]"""
            )
        )
        assertNotNull(summary)
        assertEquals(0, summary!!.books)
        assertEquals(2, summary.bookmarks)
    }

    @Test
    fun notAZipReturnsNull() {
        val bytes = "这不是备份文件".toByteArray(Charsets.UTF_8)
        assertNull(count(bytes))
    }

    @Test
    fun emptyZipReturnsNull() {
        assertNull(count(zipOf()))
    }

    @Test
    fun emptyBackupIsStillReadable() {
        val summary = count(zipOf("bookshelf.json" to "[]", "bookmark.json" to "[]"))
        assertNotNull(summary)
        assertTrue(summary!!.isEmpty)
    }
}
