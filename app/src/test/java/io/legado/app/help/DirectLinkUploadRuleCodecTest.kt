package io.legado.app.help

import com.google.gson.JsonObject
import io.legado.app.utils.GSON
import io.legado.app.utils.GSONStrict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectLinkUploadRuleCodecTest {

    @Test
    fun roundTripsCanonicalRuleAndPreservesRuleText() {
        val uploadUrl = "https://example.com/upload, {\"method\":\"POST\",\"body\":\" a b \"}"
        val downloadUrlRule = "@js:\n  const url = result.data.url;\n  return url;"
        val source = JsonObject().apply {
            addProperty("uploadUrl", uploadUrl)
            addProperty("downloadUrlRule", downloadUrlRule)
            addProperty("summary", " note ")
            addProperty("compress", true)
            add("ignored", JsonObject().apply { addProperty("value", 1) })
        }

        val decoded = DirectLinkUploadRuleCodec.decode(GSONStrict.toJson(source)).getOrThrow()

        assertEquals(uploadUrl, decoded.uploadUrl)
        assertEquals(downloadUrlRule, decoded.downloadUrlRule)
        assertEquals(" note ", decoded.summary)
        assertTrue(decoded.compress)

        val encoded = DirectLinkUploadRuleCodec.encode(decoded).getOrThrow()
        val encodedObject = GSONStrict.fromJson(encoded, JsonObject::class.java)
        assertEquals(
            setOf("uploadUrl", "downloadUrlRule", "summary", "compress", "expiryDate"),
            encodedObject.keySet()
        )
        assertEquals(decoded, DirectLinkUploadRuleCodec.decode(encoded).getOrThrow())
    }

    @Test
    fun defaultsNullableOptionalFields() {
        val missing = DirectLinkUploadRuleCodec.decode(
            """{"uploadUrl":"u","downloadUrlRule":"d"}"""
        ).getOrThrow()
        val explicitNull = DirectLinkUploadRuleCodec.decode(
            """{"uploadUrl":"u","downloadUrlRule":"d","summary":null,"compress":null}"""
        ).getOrThrow()

        assertEquals("", missing.summary)
        assertFalse(missing.compress)
        assertEquals("", explicitNull.summary)
        assertFalse(explicitNull.compress)
    }

    @Test
    fun expiryDateDefaultsToPermanentAndRoundTrips() {
        val legacy = DirectLinkUploadRuleCodec.decode(
            """{"uploadUrl":"https://example.com","downloadUrlRule":"$.url","summary":"x"}"""
        ).getOrThrow()
        assertEquals(0, legacy.expiryDate)

        val expiring = DirectLinkUpload.Rule(
            uploadUrl = "https://example.com",
            downloadUrlRule = "$.url",
            summary = "x",
            expiryDate = 30
        )
        val encoded = DirectLinkUploadRuleCodec.encode(expiring).getOrThrow()
        assertEquals(30, DirectLinkUploadRuleCodec.decode(encoded).getOrThrow().expiryDate)
    }

    @Test
    fun negativeOrFractionalExpiryDateIsRejected() {
        assertTrue(
            DirectLinkUploadRuleCodec.decode(
                """{"uploadUrl":"https://example.com","downloadUrlRule":"$.url","summary":"x","expiryDate":-1}"""
            ).isFailure
        )
        assertTrue(
            DirectLinkUploadRuleCodec.decode(
                """{"uploadUrl":"https://example.com","downloadUrlRule":"$.url","summary":"x","expiryDate":1.5}"""
            ).isFailure
        )
    }

    @Test
    fun decodesStrictRuleLists() {
        val rules = DirectLinkUploadRuleCodec.decodeList(
            """[
                {"uploadUrl":"u1","downloadUrlRule":"d1","summary":null},
                {"uploadUrl":"u2","downloadUrlRule":"d2","compress":true}
            ]""".trimIndent()
        ).getOrThrow()

        assertEquals(2, rules.size)
        assertEquals("", rules[0].summary)
        assertFalse(rules[0].compress)
        assertEquals("", rules[1].summary)
        assertTrue(rules[1].compress)
    }

    @Test
    fun rejectsMalformedRuleListsAndInvalidMembers() {
        listOf(
            "{}",
            "null",
            "[null]",
            "[1]",
            """[{"uploadUrl":"u","downloadUrlRule":null}]""",
            """[{"uploadUrl":"u","downloadUrlRule":"d","compress":"false"}]"""
        ).forEach { json ->
            assertFailureWithoutNullPointer(DirectLinkUploadRuleCodec.decodeList(json))
        }
    }

    @Test
    fun rejectsMissingNullOrBlankRequiredFields() {
        listOf(
            """{"downloadUrlRule":"d"}""",
            """{"uploadUrl":null,"downloadUrlRule":"d"}""",
            """{"uploadUrl":"","downloadUrlRule":"d"}""",
            """{"uploadUrl":" \t\n","downloadUrlRule":"d"}""",
            """{"uploadUrl":"u"}""",
            """{"uploadUrl":"u","downloadUrlRule":null}""",
            """{"uploadUrl":"u","downloadUrlRule":""}""",
            """{"uploadUrl":"u","downloadUrlRule":" \r\n"}"""
        ).forEach(::assertRejected)
    }

    @Test
    fun rejectsFieldsWithWrongJsonTypes() {
        listOf(
            """{"uploadUrl":1,"downloadUrlRule":"d"}""",
            """{"uploadUrl":true,"downloadUrlRule":"d"}""",
            """{"uploadUrl":"u","downloadUrlRule":1}""",
            """{"uploadUrl":"u","downloadUrlRule":{}}""",
            """{"uploadUrl":"u","downloadUrlRule":"d","summary":1}""",
            """{"uploadUrl":"u","downloadUrlRule":"d","summary":{}}""",
            """{"uploadUrl":"u","downloadUrlRule":"d","compress":"false"}""",
            """{"uploadUrl":"u","downloadUrlRule":"d","compress":0}"""
        ).forEach(::assertRejected)
    }

    @Test
    fun rejectsNonObjectAndMalformedDocuments() {
        listOf(
            "",
            "null",
            "[]",
            "\"rule\"",
            "true",
            "1",
            "{",
            """{"uploadUrl":"u","downloadUrlRule":"d"} trailing"""
        ).forEach(::assertRejected)
    }

    @Test
    fun enforcesUtf8JsonSizeAtTheBoundary() {
        val exact = jsonWithTotalBytes(DirectLinkUploadRuleCodec.MAX_JSON_BYTES)
        val oversized = jsonWithTotalBytes(DirectLinkUploadRuleCodec.MAX_JSON_BYTES + 1)

        assertTrue(DirectLinkUploadRuleCodec.decode(exact).isSuccess)
        assertRejected(oversized)

        val multibyteOversized = JsonObject().apply {
            addProperty("uploadUrl", "u")
            addProperty("downloadUrlRule", "d")
            addProperty(
                "summary",
                "\u4E66".repeat(DirectLinkUploadRuleCodec.MAX_JSON_BYTES / 3)
            )
        }
        assertRejected(GSONStrict.toJson(multibyteOversized))
    }

    @Test
    fun rejectsOversizedCanonicalEncoding() {
        val oversized = DirectLinkUpload.Rule(
            uploadUrl = "u",
            downloadUrlRule = "d",
            summary = "x".repeat(DirectLinkUploadRuleCodec.MAX_JSON_BYTES),
            compress = false
        )

        assertFailureWithoutNullPointer(DirectLinkUploadRuleCodec.encode(oversized))
    }

    @Test
    fun pollutedRuntimeNullsFailCanonicalizationAndEncodingWithoutCrashing() {
        listOf(
            """{"uploadUrl":null,"downloadUrlRule":"d","summary":"s"}""",
            """{"uploadUrl":"u","downloadUrlRule":null,"summary":"s"}""",
            """{"uploadUrl":"u","downloadUrlRule":"d","summary":null}"""
        ).forEach { json ->
            val polluted = GSON.fromJson(json, DirectLinkUpload.Rule::class.java)

            assertFailureWithoutNullPointer(DirectLinkUploadRuleCodec.canonicalize(polluted))
            assertFailureWithoutNullPointer(DirectLinkUploadRuleCodec.encode(polluted))
        }
    }

    private fun assertRejected(json: String) {
        assertFailureWithoutNullPointer(DirectLinkUploadRuleCodec.decode(json))
    }

    private fun assertFailureWithoutNullPointer(result: Result<*>) {
        assertTrue("Expected validation to fail", result.isFailure)
        assertFalse(
            "Validation leaked a NullPointerException",
            result.exceptionOrNull() is NullPointerException
        )
    }

    private fun jsonWithTotalBytes(totalBytes: Int): String {
        val prefix = "{\"uploadUrl\":\"u\",\"downloadUrlRule\":\"d\",\"summary\":\""
        val suffix = "\",\"compress\":false}"
        val contentBytes = totalBytes - prefix.length - suffix.length
        require(contentBytes >= 0)
        return prefix + "x".repeat(contentBytes) + suffix
    }
}
