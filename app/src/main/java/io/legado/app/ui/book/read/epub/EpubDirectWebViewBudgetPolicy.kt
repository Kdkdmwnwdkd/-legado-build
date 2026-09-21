package io.legado.app.ui.book.read.epub

internal enum class EpubPerformanceMode(val key: String) {
    Smart("smart"),
    PowerSave("power_save"),
    Balanced("balanced"),
    Smooth("smooth"),
    Extreme("extreme");

    companion object {
        fun fromKey(key: String?): EpubPerformanceMode {
            return when (key) {
                "light" -> PowerSave
                "normal" -> Smart
                "performance" -> Extreme
                else -> values().firstOrNull { it.key == key } ?: Smart
            }
        }
    }
}

internal data class EpubPerformanceBudget(
    val selectedMode: EpubPerformanceMode,
    val effectiveMode: EpubPerformanceMode,
    val preloadedWebViews: Int,
    val chapterPreloadLimit: Int,
    val chapterPreloadStaggerMillis: Long,
    val adjacentFramesEnabled: Boolean,
    val farFramePrefetchEnabled: Boolean,
    val adjacentFrameCacheCapacity: Int
)

internal object EpubDirectWebViewBudgetPolicy {

    private const val LOW_MEMORY_SNAPSHOT_PIXELS = 2_097_152L
    private const val NORMAL_SNAPSHOT_PIXELS = 6_291_456L
    private const val HIGH_MEMORY_SNAPSHOT_PIXELS = 8_388_608L

    fun resolve(
        modeKey: String?,
        isLowRamDevice: Boolean,
        memoryClassMb: Int
    ): EpubPerformanceBudget {
        val selected = EpubPerformanceMode.fromKey(modeKey)
        val effective = if (selected == EpubPerformanceMode.Smart) {
            // The default prioritizes ready pages. A small Java heap is not a reason
            // to disable the compositor-backed frame pipeline on modern WebView.
            if (!isLowRamDevice && memoryClassMb >= 768) EpubPerformanceMode.Extreme
            else EpubPerformanceMode.Smooth
        } else {
            selected
        }
        return when (effective) {
            EpubPerformanceMode.PowerSave -> EpubPerformanceBudget(
                selectedMode = selected,
                effectiveMode = effective,
                preloadedWebViews = 1,
                chapterPreloadLimit = 1,
                chapterPreloadStaggerMillis = 500L,
                adjacentFramesEnabled = false,
                farFramePrefetchEnabled = false,
                adjacentFrameCacheCapacity = 2
            )
            EpubPerformanceMode.Balanced -> EpubPerformanceBudget(
                selectedMode = selected,
                effectiveMode = effective,
                preloadedWebViews = 2,
                chapterPreloadLimit = 2,
                chapterPreloadStaggerMillis = 240L,
                adjacentFramesEnabled = true,
                farFramePrefetchEnabled = false,
                adjacentFrameCacheCapacity = 3
            )
            EpubPerformanceMode.Smooth -> EpubPerformanceBudget(
                selectedMode = selected,
                effectiveMode = effective,
                preloadedWebViews = 4,
                chapterPreloadLimit = 4,
                chapterPreloadStaggerMillis = 80L,
                adjacentFramesEnabled = true,
                farFramePrefetchEnabled = true,
                adjacentFrameCacheCapacity = 9
            )
            EpubPerformanceMode.Extreme -> EpubPerformanceBudget(
                selectedMode = selected,
                effectiveMode = effective,
                preloadedWebViews = 6,
                chapterPreloadLimit = 6,
                chapterPreloadStaggerMillis = 32L,
                adjacentFramesEnabled = true,
                farFramePrefetchEnabled = true,
                adjacentFrameCacheCapacity = 13
            )
            EpubPerformanceMode.Smart -> error("Smart mode must resolve to a concrete budget")
        }
    }

    fun maxPreloadedWebViews(
        isLowRamDevice: Boolean,
        memoryClassMb: Int,
        modeKey: String? = EpubPerformanceMode.Smart.key
    ): Int {
        return resolve(modeKey, isLowRamDevice, memoryClassMb).preloadedWebViews
    }

    fun maxSnapshotPixels(isLowRamDevice: Boolean, memoryClassMb: Int): Long {
        return when {
            isLowRamDevice || memoryClassMb < 384 -> LOW_MEMORY_SNAPSHOT_PIXELS
            memoryClassMb >= 768 -> HIGH_MEMORY_SNAPSHOT_PIXELS
            else -> NORMAL_SNAPSHOT_PIXELS
        }
    }
}
