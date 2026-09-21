package io.legado.app.service.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import okio.ByteString.Companion.encodeUtf8

class RelaySecurityBoundaryTest {
    @Test
    fun onlyExactReadRoutesAreAllowed() {
        assertNull(validate("GET", "/getBookshelf?group=1"))
        assertNull(validate("GET", "/getBookContent?url=https%3A%2F%2Fexample.com"))
        assertEquals("method_not_allowed", validate("POST", "/getBookshelf"))
        assertEquals("path_not_allowed", validate("GET", "/getBookSources"))
        assertEquals("path_not_allowed", validate("GET", "/saveBook"))
        assertEquals("path_not_allowed", validate("GET", "/cover?path=file%3A%2F%2F%2Fdata%2Fsecret"))
        assertEquals("path_not_allowed", validate("GET", "/image?path=content%3A%2F%2Fprivate"))
    }

    @Test
    fun progressWriteRequiresExactJsonBody() {
        val body = "{\"name\":\"book\",\"author\":\"author\"}".encodeUtf8()
        assertNull(
            RelayReadAllowlist.validate(
                RelayControlMessage(
                    type = "http_request",
                    requestId = 1,
                    method = "POST",
                    path = "/saveBookProgress",
                    contentLength = body.size.toLong(),
                    bodyBase64 = body.base64Url(),
                    headers = mapOf("content-type" to "application/json")
                )
            )
        )
        assertEquals("invalid_body", validate("POST", "/saveBookProgress"))
    }

    @Test
    fun searchAndParagraphActionsAreNotExposed() {
        assertEquals("method_not_allowed", validateJsonPost("/searchBook", "{\"key\":\"test\"}"))
        assertEquals("method_not_allowed", validateJsonPost("/paragraph/action", "{\"actionId\":\"id\"}"))
    }

    @Test
    fun traversalAndHopByHopHeadersAreRejected() {
        assertEquals("invalid_path", validate("GET", "/getBookshelf/%2e%2e/saveBook"))
        assertEquals("invalid_path", validate("GET", "/getBookshelf//saveBook"))
        assertEquals(
            "forbidden_header",
            RelayReadAllowlist.validate(
                RelayControlMessage(
                    type = "http_request",
                    requestId = 1,
                    method = "GET",
                    path = "/getBookshelf",
                    contentLength = 0,
                    headers = mapOf("Connection" to "keep-alive")
                )
            )
        )
    }

    @Test
    fun requestBudgetIsBoundedAndReleasesSlots() {
        val budget = RelayRequestBudget()
        repeat(RelayProtocol.MAX_CONCURRENT_REQUESTS) { index ->
            assertNull(budget.begin((index + 1).toLong(), 0))
        }
        assertEquals("too_many_requests", budget.begin(99, 0))
        assertTrue(budget.finish(1))
        assertNull(budget.begin(99, 0))
        assertEquals(
            "chunk_too_large",
            budget.acceptChunk(99, RelayProtocol.MAX_CHUNK_BYTES + 1)
        )
    }

    @Test
    fun backoffStaysWithinExponentialJitterBounds() {
        val backoff = RelayBackoff(Random(1234), initialMillis = 1_000, maximumMillis = 60_000)
        val ceilings = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L)
        ceilings.forEach { ceiling ->
            val value = backoff.nextDelayMillis()
            assertTrue(value in (ceiling / 2)..ceiling)
        }
        backoff.reset()
        assertTrue(backoff.nextDelayMillis() in 500L..1_000L)
    }

    @Test
    fun authenticationVectorMatchesWorkerContract() {
        val secret = ByteArray(32) { it.toByte() }
        val identity = RelayIdentity("ABEiM0RVZneImaq7zN3u_w", secret)
        assertEquals("Yw3NKWbEM2aRElRIu7JbT_QSpJxzLbLIq8G4WBvXEN0", RelayAuthenticator.deviceVerifier(identity))
        val proof = RelayAuthenticator.createProof(
            identity = identity,
            nonce = "q83vEjRWeJCrze8SNFZ4kKvN7wEjRWeJCrze8SNFZ4",
            expiresAt = 1_784_174_430_000L,
            epoch = 20_015_998_343_868L,
            now = 1_784_174_400_000L
        )
        assertEquals("90OxZvsmTpOMUtqJWbgtewpwdtOmzgIQ1ipRiHnGvCA", proof)
        val control = RelayAuthenticator.createControlProof(
            identity = identity,
            method = "GET",
            path = "/v1/device/connect",
            body = ByteArray(0),
            timestampSeconds = 1_784_174_400L,
            nonceBytes = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x90.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte(), 0x12, 0x34, 0x56, 0x78, 0x90.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte())
        )
        assertEquals("EjRWeJCrze8SNFZ4kKvN7w", control.nonce)
        assertEquals("r0RmK6Pu5KhhQgey5wEavfDdz_uwBM2M3dmO3jTrBak", control.signature)
    }

    private fun validate(method: String, path: String): String? {
        return RelayReadAllowlist.validate(
            RelayControlMessage(
                type = "http_request",
                requestId = 1,
                method = method,
                path = path,
                contentLength = 0
            )
        )
    }

    private fun validateJsonPost(path: String, json: String): String? {
        val body = json.encodeUtf8()
        return RelayReadAllowlist.validate(
            RelayControlMessage(
                type = "http_request",
                requestId = 1,
                method = "POST",
                path = path,
                contentLength = body.size.toLong(),
                bodyBase64 = body.base64Url(),
                headers = mapOf("content-type" to "application/json")
            )
        )
    }
}
