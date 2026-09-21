package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDirectSnapshotVisualPolicyTest {

    @Test
    fun `uniform reader background is not visual content`() {
        assertFalse(
            EpubDirectSnapshotVisualPolicy.hasVisualContent(
                backgroundColor = 0xfff7f7f7.toInt(),
                sampledColors = intArrayOf(0xfff7f7f7.toInt(), 0xfffaf6f8.toInt())
            )
        )
    }

    @Test
    fun `text or artwork pixel makes snapshot usable`() {
        assertTrue(
            EpubDirectSnapshotVisualPolicy.hasVisualContent(
                backgroundColor = 0xfff7f7f7.toInt(),
                sampledColors = intArrayOf(0xfff7f7f7.toInt(), 0xff202020.toInt())
            )
        )
    }

    @Test
    fun `rgb565 quantization does not turn a blank snapshot into content`() {
        assertFalse(
            EpubDirectSnapshotVisualPolicy.hasVisualContent(
                backgroundColor = 0xfffafafa.toInt(),
                sampledColors = intArrayOf(0xfffffbff.toInt(), 0xfff8fbf8.toInt())
            )
        )
    }
}
