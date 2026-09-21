package io.legado.app.ui.book.read.page

import io.legado.app.utils.Utf8Sha256
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

internal data class LottieDecodeSize(val width: Int, val height: Int)

internal data class LottieImageCacheKey(
    val sourceSha256: String,
    val width: Int,
    val height: Int,
    val decoderVersion: Int = 1
)

internal object LottieImageMemoryPolicy {
    const val MAX_EDGE = 1200
    const val MAX_PIXELS = 1_440_000L
    const val MAX_COMPOSITION_PIXELS = 3_000_000L
    private const val QUALITY_SCALE = 1.25f
    private const val MIN_BUDGET_SCALE = 0.25f
    private const val MIN_CACHE_BYTES = 8 * 1024 * 1024
    private const val MAX_CACHE_BYTES = 16 * 1024 * 1024
    private val sourceDigestCache = WeakHashMap<String, String>()

    fun decodeSize(
        assetWidth: Int,
        assetHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        compositionWidth: Int,
        compositionHeight: Int
    ): LottieDecodeSize? {
        if (assetWidth <= 0 || assetHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return null
        val compWidth = compositionWidth.takeIf { it > 0 } ?: assetWidth
        val compHeight = compositionHeight.takeIf { it > 0 } ?: assetHeight
        val fitScale = min(viewWidth.toDouble() / compWidth, viewHeight.toDouble() / compHeight)
        if (!fitScale.isFinite() || fitScale <= 0.0) return null
        var width = ceil(assetWidth * fitScale * QUALITY_SCALE).toInt().coerceAtLeast(1)
        var height = ceil(assetHeight * fitScale * QUALITY_SCALE).toInt().coerceAtLeast(1)
        val edgeScale = min(1.0, MAX_EDGE.toDouble() / maxOf(width, height))
        width = (width * edgeScale).toInt().coerceAtLeast(1)
        height = (height * edgeScale).toInt().coerceAtLeast(1)
        val pixels = width.toLong() * height
        if (pixels > MAX_PIXELS) {
            val pixelScale = sqrt(MAX_PIXELS.toDouble() / pixels)
            width = (width * pixelScale).toInt().coerceAtLeast(1)
            height = (height * pixelScale).toInt().coerceAtLeast(1)
        }
        return LottieDecodeSize(width, height)
    }

    fun fitSourceInto(sourceWidth: Int, sourceHeight: Int, box: LottieDecodeSize): LottieDecodeSize? {
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val scale = min(1.0, min(box.width.toDouble() / sourceWidth, box.height.toDouble() / sourceHeight))
        return LottieDecodeSize(
            width = (sourceWidth * scale).toInt().coerceAtLeast(1),
            height = (sourceHeight * scale).toInt().coerceAtLeast(1)
        )
    }

    fun sampleSize(sourceWidth: Int, sourceHeight: Int, target: LottieDecodeSize): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0 || target.width <= 0 || target.height <= 0) return 1
        var sample = 1
        while (sourceWidth / (sample * 2L) >= target.width &&
            sourceHeight / (sample * 2L) >= target.height
        ) {
            sample *= 2
        }
        return sample
    }

    fun fitsCompositionBudget(sizes: Collection<LottieDecodeSize>): Boolean {
        var pixels = 0L
        sizes.forEach { size ->
            if (size.width <= 0 || size.height <= 0) return false
            val next = size.width.toLong() * size.height.toLong()
            if (next > MAX_COMPOSITION_PIXELS - pixels) return false
            pixels += next
        }
        return true
    }

    /**
     * Linear factor that brings the aggregate pixel cost of [sizes] inside
     * [MAX_COMPOSITION_PIXELS]. Oversized packages are shrunk instead of being
     * refused, so an advanced title/tip still renders rather than falling back.
     */
    fun compositionBudgetScale(sizes: Collection<LottieDecodeSize>): Float {
        var pixels = 0L
        sizes.forEach { size ->
            if (size.width <= 0 || size.height <= 0) return@forEach
            pixels += size.width.toLong() * size.height.toLong()
        }
        if (pixels <= MAX_COMPOSITION_PIXELS) return 1f
        // Pixels scale with the square of the linear factor.
        return sqrt(MAX_COMPOSITION_PIXELS.toDouble() / pixels.toDouble())
            .toFloat()
            .coerceIn(MIN_BUDGET_SCALE, 1f)
    }

    fun scaled(size: LottieDecodeSize, scale: Float): LottieDecodeSize {
        if (scale >= 1f) return size
        return LottieDecodeSize(
            width = (size.width * scale).toInt().coerceAtLeast(1),
            height = (size.height * scale).toInt().coerceAtLeast(1)
        )
    }

    fun cacheBudgetBytes(maxHeapBytes: Long): Int {
        return (maxHeapBytes / 32L).coerceIn(MIN_CACHE_BYTES.toLong(), MAX_CACHE_BYTES.toLong()).toInt()
    }

    fun chargeBytes(allocationBytes: Int, rowBytes: Int, height: Int): Int {
        if (allocationBytes > 0) return allocationBytes
        return (rowBytes.toLong().coerceAtLeast(0L) * height.coerceAtLeast(0))
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    fun sourceSha256(source: String): String {
        synchronized(sourceDigestCache) {
            sourceDigestCache[source]?.let { return it }
        }
        val hash = Utf8Sha256.digestHex(source)
        synchronized(sourceDigestCache) {
            return sourceDigestCache[source] ?: hash.also { sourceDigestCache[source] = it }
        }
    }
}
