package io.legado.app.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

class OomGuardTest {

    @Test
    fun readBytesLimitedReadsWithinLimit() {
        val bytes = byteArrayOf(1, 2, 3)
        val result = ByteArrayInputStream(bytes).readBytesLimited(maxBytes = 3)

        assertArrayEquals(bytes, result)
    }

    @Test(expected = IOException::class)
    fun readBytesLimitedRejectsOverLimit() {
        ByteArrayInputStream(ByteArray(5)).readBytesLimited(maxBytes = 4)
    }

    @Test
    fun estimateBase64DataUrlBytesReturnsDecodedSize() {
        val dataUrl = "data:image/png;base64,AAAA"

        assertEquals(3L, dataUrl.estimateBase64DataUrlBytes())
    }

    @Test
    fun decodeBase64DataUrlBytesRejectsBeforeDecodeWhenEstimatedSizeExceedsLimit() {
        val dataUrl = "data:image/png;base64,AAAA"

        assertNull(dataUrl.decodeBase64DataUrlBytes(maxBytes = 2))
    }

    @Test
    fun isSameOrSubFileOfRejectsSiblingWithSamePrefix() {
        val root = Files.createTempDirectory("legado-root").toFile()
        val child = File(root, "child.txt")
        val sibling = File(root.parentFile, "${root.name}-evil/child.txt")
        try {
            child.parentFile?.mkdirs()
            child.writeText("ok")
            sibling.parentFile?.mkdirs()
            sibling.writeText("bad")

            assertTrue(root.isSameOrSubFileOf(root))
            assertTrue(child.isSameOrSubFileOf(root))
            assertFalse(sibling.isSameOrSubFileOf(root))
        } finally {
            root.deleteRecursively()
            sibling.parentFile?.deleteRecursively()
        }
    }
}
