package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SvgBitmapSizePolicyTest {

    @Test
    fun neverExceedsEitherBound() {
        assertEquals(
            SvgBitmapSize(563, 187),
            SvgBitmapSizePolicy.fitWithin(600, 200, 563, 188)
        )
        assertEquals(
            SvgBitmapSize(470, 188),
            SvgBitmapSizePolicy.fitWithin(1000, 400, 563, 188)
        )
    }

    @Test
    fun doesNotUpscaleSmallSvg() {
        assertEquals(
            SvgBitmapSize(120, 40),
            SvgBitmapSizePolicy.fitWithin(120, 40, 1200, 1200)
        )
    }

    @Test
    fun supportsSingleBoundAndRejectsInvalidSource() {
        assertEquals(
            SvgBitmapSize(300, 150),
            SvgBitmapSizePolicy.fitWithin(600, 300, 300, null)
        )
        assertNull(SvgBitmapSizePolicy.fitWithin(0, 300, 300, 300))
    }
}
