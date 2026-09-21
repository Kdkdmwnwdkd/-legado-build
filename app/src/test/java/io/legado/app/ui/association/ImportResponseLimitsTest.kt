package io.legado.app.ui.association

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

class ImportResponseLimitsTest {

    @Test
    fun readsResponseAtConfiguredLimit() {
        val text = "a".repeat(32)

        assertEquals(text, text.toResponseBody().readLimitedImportText(32))
    }

    @Test(expected = IOException::class)
    fun rejectsResponseBeyondConfiguredLimit() {
        "a".repeat(33).toResponseBody().readLimitedImportText(32)
    }

    @Test
    fun readsGzipResponseBodyWithinLimit() {
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write("{\"tasks\":[]}".toByteArray()) }
        }.toByteArray()

        assertEquals(
            "{\"tasks\":[]}",
            compressed.toResponseBody().readLimitedImportTextWithGzip(64)
        )
    }
}
