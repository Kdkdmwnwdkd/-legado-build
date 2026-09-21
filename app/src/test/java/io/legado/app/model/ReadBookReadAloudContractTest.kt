package io.legado.app.model

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadBookReadAloudContractTest {

    @Test
    fun `direct read aloud chapter turns have one visual handoff callback`() {
        val source = readBookSource()
        listOf("moveToNextChapter", "moveToNextChapterAwait", "moveToPrevChapter").forEach { name ->
            val body = functionBody(source, name)
            assertTrue("$name must identify Direct read aloud mode", body.contains("suppressDirectReadAloudContent"))
            assertTrue("$name must emit the dedicated chapter callback", body.contains("onReadAloudChapterChanged"))
            assertTrue(
                "$name must emit exactly one Direct visual handoff",
                body.countOccurrences("onReadAloudChapterChanged") == 1
            )
            assertTrue(
                "$name must load speech text without a visible refresh",
                body.contains("upContent = upContent && !suppressVisualContent")
            )
            assertTrue("$name must keep hidden chapter loading non-visual", body.contains("upContent = false"))
        }
    }

    @Test
    fun `direct read aloud openChapter keeps trailing callback compatibility`() {
        val source = readBookSource()
        val signature = source.substringAfter("fun openChapter(").substringBefore(") {")
        assertTrue(signature.indexOf("fromReadAloud") < signature.indexOf("success"))
        val body = functionBody(source, "openChapter")
        assertTrue(body.contains("upContent = upContent && !suppressVisualContent"))
        assertTrue(body.contains("onReadAloudChapterChanged"))
    }

    @Test
    fun `direct read aloud page changes do not call ordinary content refresh`() {
        val body = functionBody(readBookSource(), "curPageChanged")
        assertTrue(body.contains("if (!suppressDirectReadAloudContent(fromReadAloud))"))
    }

    private fun readBookSource(): String {
        val relativePath = "io/legado/app/model/ReadBook.kt"
        return sequenceOf(
            File("src/main/java/$relativePath"),
            File("app/src/main/java/$relativePath")
        ).firstOrNull(File::isFile)?.readText() ?: error("ReadBook source not found")
    }

    private fun functionBody(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        check(start >= 0) { "ReadBook function not found: $name" }
        val bodyStart = source.indexOf('{', start)
        check(bodyStart >= 0) { "ReadBook function body not found: $name" }
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(bodyStart + 1, index)
            }
        }
        error("Unterminated ReadBook function: $name")
    }

    private fun String.countOccurrences(value: String): Int {
        var count = 0
        var offset = 0
        while (true) {
            val match = indexOf(value, offset)
            if (match < 0) return count
            count++
            offset = match + value.length
        }
    }
}
