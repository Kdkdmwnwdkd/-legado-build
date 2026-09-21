package io.legado.app.ui.book.read.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedTitleLottieCacheTest {

    @Test
    fun compositionKeysDoNotReuseKnownHashCollision() {
        assertEquals("FB".hashCode(), "Ea".hashCode())
        assertNotEquals(
            AdvancedTitleLottieKeys.composition("FB", 100, 40),
            AdvancedTitleLottieKeys.composition("Ea", 100, 40)
        )
        assertNotEquals(
            AdvancedTitleLottieKeys.styledJson("FB", 0xff00, 1),
            AdvancedTitleLottieKeys.styledJson("Ea", 0xff00, 1)
        )
    }

    @Test
    fun largeEmbeddedAssetBuildsStableCacheKeys() {
        val json = """{"w":1920,"h":1530,"assets":[{"p":"data:image/png;base64,${"A".repeat(190_000)}"}],"layers":[{}]}"""
        val styledKey = AdvancedTitleLottieKeys.styledJson(json, 0x123456, 1)
        val compositionKey = AdvancedTitleLottieKeys.composition(json, 900, 717)

        assertEquals(styledKey, AdvancedTitleLottieKeys.styledJson(json, 0x123456, 1))
        assertEquals(compositionKey, AdvancedTitleLottieKeys.composition(json, 900, 717))
        assertTrue(styledKey.startsWith("advanced_title_style:"))
        assertTrue(compositionKey.startsWith("advanced_title:"))
    }

    @Test
    fun oversizedJsonSkipsObjectTreeStyling() {
        assertTrue(
            AdvancedTitleLottieKeys.canApplyFallbackStyle(
                "x".repeat(AdvancedTitleLottieKeys.MAX_STYLABLE_JSON_CHARS)
            )
        )
        assertFalse(
            AdvancedTitleLottieKeys.canApplyFallbackStyle(
                "x".repeat(AdvancedTitleLottieKeys.MAX_STYLABLE_JSON_CHARS + 1)
            )
        )
        assertFalse(
            AdvancedTitleLottieKeys.canApplyFallbackStyle(
                "\u4e2d".repeat(AdvancedTitleLottieKeys.MAX_STYLABLE_JSON_UTF8_BYTES / 3 + 1)
            )
        )
    }

    @Test
    fun oversizedStyledJsonIsNotCached() {
        val cache = StyledLottieJsonCache(
            maxChars = 12,
            maxUtf8Bytes = 16,
            maxEntryChars = 8,
            maxEntryUtf8Bytes = 8
        )

        assertFalse(cache.put("large", StyledLottieJson("123456789", null)))
        assertNull(cache["large"])
        assertEquals(Triple(0, 0, 0), cache.stats())
    }

    @Test
    fun cacheEvictsByCharacterAndUtf8Budgets() {
        val cache = StyledLottieJsonCache(
            maxChars = 8,
            maxUtf8Bytes = 9,
            maxEntryChars = 8,
            maxEntryUtf8Bytes = 9
        )
        assertTrue(cache.put("ascii", StyledLottieJson("1234", null)))
        assertTrue(cache.put("cjk", StyledLottieJson("\u4e2d\u6587", LottieDecodeSize(10, 20))))

        assertNull(cache["ascii"])
        assertEquals(
            StyledLottieJson("\u4e2d\u6587", LottieDecodeSize(10, 20)),
            cache["cjk"]
        )
        assertEquals(Triple(1, 2, 6), cache.stats())
    }
}
