package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubSnapshotSizePolicyTest {

    @Test
    fun `small snapshot keeps native dimensions`() {
        assertEquals(
            EpubSnapshotSizePolicy.Size(600, 800),
            EpubSnapshotSizePolicy.sizeFor(600, 800, 600_000L)
        )
    }

    @Test
    fun `large snapshot remains at physical viewport resolution`() {
        val size = EpubSnapshotSizePolicy.sizeFor(1440, 3200, 600_000L)

        assertEquals(EpubSnapshotSizePolicy.Size(1440, 3200), size)
    }

    @Test
    fun `invalid dimensions and budget remain drawable`() {
        assertEquals(
            EpubSnapshotSizePolicy.Size(1, 1),
            EpubSnapshotSizePolicy.sizeFor(0, -1, 0)
        )
    }
}
