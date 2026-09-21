package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubDirectWebViewBudgetPolicyTest {

    @Test
    fun `smart mode prioritizes warm pages even with a small Java heap`() {
        assertEquals(
            EpubPerformanceMode.Smooth,
            EpubDirectWebViewBudgetPolicy.resolve("smart", true, 768).effectiveMode
        )
        assertEquals(
            EpubPerformanceMode.Smooth,
            EpubDirectWebViewBudgetPolicy.resolve("smart", false, 512).effectiveMode
        )
        assertEquals(
            EpubPerformanceMode.Extreme,
            EpubDirectWebViewBudgetPolicy.resolve("smart", false, 768).effectiveMode
        )
    }

    @Test
    fun `manual modes only alter resource budgets`() {
        val power = EpubDirectWebViewBudgetPolicy.resolve("power_save", false, 1024)
        val balanced = EpubDirectWebViewBudgetPolicy.resolve("balanced", false, 1024)
        val smooth = EpubDirectWebViewBudgetPolicy.resolve("smooth", false, 1024)
        val extreme = EpubDirectWebViewBudgetPolicy.resolve("extreme", false, 1024)

        assertEquals(1, power.preloadedWebViews)
        assertEquals(false, power.adjacentFramesEnabled)
        assertEquals(2, balanced.preloadedWebViews)
        assertEquals(false, balanced.farFramePrefetchEnabled)
        assertEquals(4, smooth.preloadedWebViews)
        assertEquals(4, smooth.chapterPreloadLimit)
        assertEquals(true, smooth.farFramePrefetchEnabled)
        assertEquals(6, extreme.preloadedWebViews)
        assertEquals(6, extreme.chapterPreloadLimit)
        assertEquals(13, extreme.adjacentFrameCacheCapacity)
    }

    @Test
    fun `legacy preferences migrate to the matching new modes`() {
        assertEquals(EpubPerformanceMode.PowerSave, EpubPerformanceMode.fromKey("light"))
        assertEquals(EpubPerformanceMode.Smart, EpubPerformanceMode.fromKey("normal"))
        assertEquals(EpubPerformanceMode.Extreme, EpubPerformanceMode.fromKey("performance"))
    }

    @Test
    fun `snapshot memory follows device budget`() {
        assertEquals(2_097_152L, EpubDirectWebViewBudgetPolicy.maxSnapshotPixels(true, 512))
        assertEquals(2_097_152L, EpubDirectWebViewBudgetPolicy.maxSnapshotPixels(false, 192))
        assertEquals(2_097_152L, EpubDirectWebViewBudgetPolicy.maxSnapshotPixels(false, 256))
        assertEquals(2_097_152L, EpubDirectWebViewBudgetPolicy.maxSnapshotPixels(false, 383))
        assertEquals(6_291_456L, EpubDirectWebViewBudgetPolicy.maxSnapshotPixels(false, 384))
        assertEquals(8_388_608L, EpubDirectWebViewBudgetPolicy.maxSnapshotPixels(false, 768))
    }
}
