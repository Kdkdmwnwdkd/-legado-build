package io.legado.app.help.config

import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LottieDerivedResourceCacheTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun derivesExactResourceBytesAndKeepsOriginalFallback() {
        val bytes = pngBytes(64)
        val source = dataUrl("image/png", bytes)
        val raw = lottieJson(source)
        val compiler = compiler()

        val prepared = compiler.prepare(raw)

        assertTrue(prepared.derived)
        assertNotEquals(raw, prepared.json)
        assertEquals(1, prepared.fallbackAssets.size)
        val (alias, fallback) = prepared.fallbackAssets.entries.single()
        assertEquals(source, fallback)
        assertTrue(alias.startsWith(LottieDerivedResourceCompiler.RESOURCE_PREFIX))
        assertTrue(prepared.json.contains("\"p\":\"$alias\""))
        assertArrayEquals(bytes, compiler.resourceFile(alias)!!.readBytes())
        assertTrue(raw.contains(source))
    }

    @Test
    fun duplicateAssetsShareOneFallbackAndRemainPresentInDerivedJson() {
        val source = dataUrl("image/png", pngBytes(48))
        val raw = lottieJson(source, source)

        val prepared = compiler().prepare(raw)

        assertTrue(prepared.derived)
        assertEquals(1, prepared.fallbackAssets.size)
        val alias = prepared.fallbackAssets.keys.single()
        assertEquals(2, Regex(Regex.escape(alias)).findAll(prepared.json).count())
    }

    @Test
    fun warmDiskCacheDoesNotDecodeEmbeddedBase64Again() {
        val decodeCalls = AtomicInteger()
        val source = dataUrl("image/png", pngBytes(96))
        val raw = lottieJson(source)
        val root = temporaryFolder.newFolder("cache")
        val first = compiler(root) { value ->
            decodeCalls.incrementAndGet()
            decodeDataUrl(value)
        }
        assertTrue(first.prepare(raw).derived)
        assertEquals(1, decodeCalls.get())

        val second = compiler(root) { value ->
            decodeCalls.incrementAndGet()
            decodeDataUrl(value)
        }
        val prepared = second.prepare(raw)

        assertTrue(prepared.derived)
        assertEquals(1, decodeCalls.get())
    }

    @Test
    fun missingResourceIsRebuiltWithoutChangingDerivedDocument() {
        val source = dataUrl("image/png", pngBytes(72))
        val raw = lottieJson(source)
        val root = temporaryFolder.newFolder("cache")
        val first = compiler(root).prepare(raw)
        val alias = first.fallbackAssets.keys.single()
        val file = compiler(root).resourceFile(alias)!!
        assertTrue(file.delete())

        val second = compiler(root).prepare(raw)

        assertTrue(second.derived)
        assertEquals(first.json, second.json)
        assertTrue(file.isFile)
    }

    @Test
    fun resourceWriteFailureStillReturnsSelfContainedFallback() {
        val source = dataUrl("image/png", pngBytes(40))
        val raw = lottieJson(source)
        val compiler = LottieDerivedResourceCompiler(
            cacheRoot = temporaryFolder.newFolder("cache"),
            decodeDataUrl = ::decodeDataUrl,
            validateDocument = ::hasLayers,
            writeResource = { _, _ -> error("injected write failure") }
        )

        val prepared = compiler.prepare(raw)

        assertTrue(prepared.derived)
        val alias = prepared.fallbackAssets.keys.single()
        assertEquals(source, prepared.fallbackAssets[alias])
        assertFalse(compiler.resourceFile(alias)!!.exists())
    }

    @Test
    fun corruptCachedDocumentIsRejectedAndReplaced() {
        val source = dataUrl("image/png", pngBytes(56))
        val raw = lottieJson(source)
        val root = temporaryFolder.newFolder("cache")
        val firstCompiler = compiler(root)
        val first = firstCompiler.prepare(raw)
        val document = File(File(root, "documents"), "${io.legado.app.utils.Utf8Sha256.digestHex(raw)}.json")
        document.writeText("""{"assets":[{"p":"${LottieDerivedResourceCompiler.RESOURCE_PREFIX}${"0".repeat(64)}.png"}],"layers":[{}]}""")

        val repaired = compiler(root).prepare(raw)

        assertTrue(repaired.derived)
        assertEquals(first.json, repaired.json)
        assertEquals(first.json, document.readText())
    }

    @Test
    fun malformedAndOversizedInputsAlwaysUseOriginalDocument() {
        listOf("", "not json", "{\"layers\":[]}").forEach { raw ->
            val prepared = compiler().prepare(raw)
            assertFalse(prepared.derived)
            assertEquals(raw, prepared.json)
            assertTrue(prepared.fallbackAssets.isEmpty())
        }
        val oversized = "x".repeat(2 * 1024 * 1024 + 1)
        val prepared = compiler().prepare(oversized)
        assertFalse(prepared.derived)
        assertEquals(oversized, prepared.json)
    }

    @Test
    fun aliasesCannotEscapeDedicatedResourceDirectory() {
        val compiler = compiler()

        assertEquals(null, compiler.resourceFile("../outside.png"))
        assertEquals(null, compiler.resourceFile(LottieDerivedResourceCompiler.RESOURCE_PREFIX + "../outside.png"))
        assertEquals(null, compiler.resourceFile(LottieDerivedResourceCompiler.RESOURCE_PREFIX + "a".repeat(63) + ".png"))
    }

    private fun compiler(
        root: File = temporaryFolder.newFolder(),
        decoder: (String) -> ByteArray? = ::decodeDataUrl
    ) = LottieDerivedResourceCompiler(
        cacheRoot = root,
        decodeDataUrl = decoder,
        validateDocument = ::hasLayers
    )

    private fun lottieJson(vararg sources: String): String {
        val assets = sources.mapIndexed { index, source ->
            """{"id":"image_$index","w":2,"h":2,"u":"images/","p":"$source","e":1}"""
        }.joinToString(",")
        return """{"v":"5.7.0","w":2,"h":2,"assets":[$assets],"layers":[{}],"unknown":{"kept":true}}"""
    }

    private fun dataUrl(mime: String, bytes: ByteArray): String {
        return "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    private fun decodeDataUrl(value: String): ByteArray? {
        return runCatching { Base64.getDecoder().decode(value.substringAfter(',')) }.getOrNull()
    }

    private fun hasLayers(json: String): Boolean = json.contains("\"layers\"")

    private fun pngBytes(size: Int): ByteArray {
        return ByteArray(size.coerceAtLeast(8)) { index -> index.toByte() }.apply {
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
                .copyInto(this)
        }
    }
}
