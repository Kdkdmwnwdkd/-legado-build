package io.legado.app.help.config

import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedTitleFontAssetDelegateTest {

    @Test
    fun overridesLegacyAndLottie6FontCallbacks() {
        val parameterCounts = AdvancedTitleFontAssetDelegate::class.java.declaredMethods
            .asSequence()
            .filter { it.name == "fetchFont" }
            .map { it.parameterCount }
            .toSet()

        assertTrue(1 in parameterCounts)
        assertTrue(3 in parameterCounts)
    }
}
