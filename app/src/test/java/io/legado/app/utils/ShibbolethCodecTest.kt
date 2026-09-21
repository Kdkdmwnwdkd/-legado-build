package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShibbolethCodecTest {

    @Test
    fun encodeAndDecodeRemainCompatibleWithUpstreamEnvelope() {
        val time = 1_784_181_294_937L
        val code = ShibbolethCodec.encode(
            url = "https://example.com/rules/bookSource.json",
            type = ShibbolethCodec.BOOK_SOURCE,
            timeMillis = time,
            expiryDays = 30
        ).getOrThrow()

        assertTrue(code.startsWith("复制口令到阅读导入#L:"))
        assertTrue(code.endsWith("¥Sigma^"))

        val decoded = ShibbolethCodec.decode(code).getOrThrow()
        assertEquals("https://example.com/rules/bookSource.json", decoded.url)
        assertEquals(ShibbolethCodec.BOOK_SOURCE, decoded.type)
        assertEquals("Sigma", decoded.customWord)
        assertFalse(decoded.isExpired(time))
    }

    @Test
    fun permanentCodeHasNoExpiry() {
        val code = ShibbolethCodec.encode(
            url = "https://example.org/a.zip",
            type = ShibbolethCodec.TTS_RULE,
            timeMillis = 1_784_181_294_937L,
            expiryDays = 0
        ).getOrThrow()

        assertEquals(null, ShibbolethCodec.decode(code).getOrThrow().expiresAtMillis)
    }

    @Test
    fun malformedMetadataReturnsFailure() {
        val malformed = "复制口令到阅读导入#L:example电com杠a串！sy©oops¥Sigma^"

        assertTrue(ShibbolethCodec.decode(malformed).isFailure)
    }

    @Test
    fun nonHttpsUrlIsRejectedBecauseUpstreamMarkerWouldBeMissing() {
        assertTrue(
            ShibbolethCodec.encode(
                url = "http://example.com/a.json",
                type = ShibbolethCodec.BOOK_SOURCE
            ).isFailure
        )
    }

    @Test
    fun urlCredentialsAreRejected() {
        assertFalse(ShibbolethCodec.canEncodeUrl("https://user:secret@example.com/a.json"))
    }

    @Test
    fun urlWhitespaceIsRejected() {
        assertFalse(ShibbolethCodec.canEncodeUrl("https://example.com/a json"))
    }
}
