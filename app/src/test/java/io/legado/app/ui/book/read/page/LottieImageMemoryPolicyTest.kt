package io.legado.app.ui.book.read.page

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LottieImageMemoryPolicyTest {

    @Test
    fun calculatesDisplayedAssetSizeFromCompositionScale() {
        assertEquals(
            LottieDecodeSize(563, 188),
            LottieImageMemoryPolicy.decodeSize(600, 200, 900, 300, 1000, 400)
        )
    }

    @Test
    fun capsEdgeAndPixelsWhileKeepingAspectRatio() {
        val size = LottieImageMemoryPolicy.decodeSize(2400, 600, 2400, 600, 2400, 600)!!
        assertEquals(1200, size.width)
        assertEquals(300, size.height)
        assertTrue(size.width.toLong() * size.height <= LottieImageMemoryPolicy.MAX_PIXELS)
    }

    @Test
    fun rejectsInvalidDimensions() {
        assertNull(LottieImageMemoryPolicy.decodeSize(0, 100, 100, 100, 100, 100))
        assertNull(LottieImageMemoryPolicy.decodeSize(100, 100, -1, 100, 100, 100))
    }

    @Test
    fun rasterFitNeverUpscalesSource() {
        assertEquals(
            LottieDecodeSize(400, 200),
            LottieImageMemoryPolicy.fitSourceInto(400, 200, LottieDecodeSize(1000, 1000))
        )
        assertEquals(
            LottieDecodeSize(200, 100),
            LottieImageMemoryPolicy.fitSourceInto(400, 200, LottieDecodeSize(200, 200))
        )
    }

    @Test
    fun cacheBudgetIsBounded() {
        assertEquals(8 * 1024 * 1024, LottieImageMemoryPolicy.cacheBudgetBytes(64L * 1024 * 1024))
        assertEquals(16 * 1024 * 1024, LottieImageMemoryPolicy.cacheBudgetBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun byteChargeUsesAllocationThenSafeFallback() {
        assertEquals(1234, LottieImageMemoryPolicy.chargeBytes(1234, 100, 20))
        assertEquals(2000, LottieImageMemoryPolicy.chargeBytes(0, 100, 20))
        assertEquals(Int.MAX_VALUE, LottieImageMemoryPolicy.chargeBytes(0, Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun cacheKeyUsesStableStrongDigestAndDimensions() {
        val firstDigest = LottieImageMemoryPolicy.sourceSha256("data:image/svg+xml;base64,abc")
        val secondDigest = LottieImageMemoryPolicy.sourceSha256("data:image/svg+xml;base64,abd")
        assertEquals(firstDigest, LottieImageMemoryPolicy.sourceSha256("data:image/svg+xml;base64,abc"))
        assertNotEquals(firstDigest, secondDigest)
        assertEquals(64, firstDigest.length)
        assertNotEquals(
            LottieImageCacheKey(firstDigest, 100, 100),
            LottieImageCacheKey(firstDigest, 200, 100)
        )
    }

    @Test
    fun compositionBudgetRejectsAggregateImagePressure() {
        assertTrue(
            LottieImageMemoryPolicy.fitsCompositionBudget(
                listOf(LottieDecodeSize(1200, 1200), LottieDecodeSize(1200, 1200))
            )
        )
        assertTrue(
            !LottieImageMemoryPolicy.fitsCompositionBudget(
                listOf(
                    LottieDecodeSize(1200, 1200),
                    LottieDecodeSize(1200, 1200),
                    LottieDecodeSize(400, 400)
                )
            )
        )
        assertTrue(!LottieImageMemoryPolicy.fitsCompositionBudget(listOf(LottieDecodeSize(0, 10))))
    }

    @Test
    fun compositionBudgetScaleShrinksOversizedPackagesInsteadOfRefusing() {
        val withinBudget = listOf(LottieDecodeSize(1000, 1000), LottieDecodeSize(1000, 1000))
        assertEquals(1f, LottieImageMemoryPolicy.compositionBudgetScale(withinBudget))

        val oversized = listOf(
            LottieDecodeSize(1200, 1200),
            LottieDecodeSize(1200, 1200),
            LottieDecodeSize(1200, 1200)
        )
        val scale = LottieImageMemoryPolicy.compositionBudgetScale(oversized)
        assertTrue(scale < 1f)
        assertTrue(scale > 0f)

        // Scaling down must bring the aggregate cost back inside the budget.
        val scaled = oversized.map { LottieImageMemoryPolicy.scaled(it, scale) }
        val pixels = scaled.sumOf { it.width.toLong() * it.height.toLong() }
        assertTrue(pixels <= LottieImageMemoryPolicy.MAX_COMPOSITION_PIXELS)
        scaled.forEach { assertTrue(it.width >= 1 && it.height >= 1) }
    }

    @Test
    fun digestMatchesUtf8Sha256() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            LottieImageMemoryPolicy.sourceSha256("abc")
        )
    }

    @Test
    fun digestHandlesLargeSourcesAcrossEncoderBufferBoundaries() {
        listOf(8_191, 8_192, 8_193, 220_000).forEach { length ->
            val source = "A".repeat(length)
            assertEquals(referenceSha256(source), LottieImageMemoryPolicy.sourceSha256(source))
        }
    }

    @Test
    fun digestHandlesMultibyteTextAcrossEncoderBufferBoundary() {
        val source = "A".repeat(8_191) + "\ud83d\ude00\u4e2d\u6587" + "B".repeat(16_384)
        assertEquals(referenceSha256(source), LottieImageMemoryPolicy.sourceSha256(source))
    }

    @Test
    fun digestReplacesMalformedUtf16LikeStandardUtf8Encoding() {
        listOf("prefix\uD800suffix", "prefix\uDC00suffix").forEach { source ->
            assertEquals(referenceSha256(source), LottieImageMemoryPolicy.sourceSha256(source))
        }
    }

    @Test
    fun sampleSizeHandlesExtremeAspectRatiosWithoutHugeIntermediateBitmap() {
        val target = LottieImageMemoryPolicy.fitSourceInto(
            100_000,
            1_000,
            LottieDecodeSize(256, 256)
        )!!

        assertEquals(LottieDecodeSize(256, 2), target)
        assertEquals(256, LottieImageMemoryPolicy.sampleSize(100_000, 1_000, target))
    }

    @Test
    fun digestDoesNotReuseKnownStringHashCollision() {
        assertEquals("FB".hashCode(), "Ea".hashCode())
        assertNotEquals(
            LottieImageMemoryPolicy.sourceSha256("FB"),
            LottieImageMemoryPolicy.sourceSha256("Ea")
        )
    }

    private fun referenceSha256(source: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
