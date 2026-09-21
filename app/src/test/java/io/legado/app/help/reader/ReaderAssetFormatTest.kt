package io.legado.app.help.reader

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Base64

class ReaderAssetFormatTest {
    @Test fun recognizesPngDimensionsAndTableBasedFontsWithoutTrustingFileNames() {
        assertEquals(ReaderAssetFormat.Format("image", "image/png", "png", 1, 1), ReaderAssetFormat.inspect(ReaderAssetFixtures.png()))
        assertEquals("font/ttf", ReaderAssetFormat.inspect(ReaderAssetFixtures.sfnt()).mime)
        assertEquals("font/otf", ReaderAssetFormat.inspect(ReaderAssetFixtures.sfnt().apply {
            "OTTO".toByteArray().copyInto(this)
        }).mime)
    }

    @Test fun truncatedPngAndFontTablesCannotPassInspection() {
        for (bytes in listOf(ReaderAssetFixtures.png(), ReaderAssetFixtures.sfnt())) {
            for (size in 0 until bytes.size) assertThrows("length=$size", IllegalArgumentException::class.java) {
                ReaderAssetFormat.inspect(bytes.copyOf(size))
            }
        }
    }

    @Test fun corruptedImageCrcAndExtremePixelCountsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ReaderAssetFormat.inspect(ReaderAssetFixtures.png().apply { this[20] = 8 })
        }
        assertThrows(IllegalArgumentException::class.java) { ReaderAssetFormat.inspect(ReaderAssetFixtures.png(width = 10000, height = 10000)) }
        assertThrows(IllegalArgumentException::class.java) { ReaderAssetFormat.inspect(ReaderAssetFixtures.png(width = 0)) }
    }

    @Test fun overflowingFontTableAndCollectionOffsetsFailBeforeReadingThem() {
        val table = ReaderAssetFixtures.sfnt()
        ByteBuffer.wrap(table).putInt(20, Int.MAX_VALUE).putInt(24, Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { ReaderAssetFormat.inspect(table) }
        val collection = ByteBuffer.allocate(16).put("ttcf".toByteArray()).putInt(65536).putInt(1).putInt(Int.MAX_VALUE).array()
        assertThrows(IllegalArgumentException::class.java) { ReaderAssetFormat.inspect(collection) }
    }

    @Test fun woffHeaderLengthReservedFieldsAndTableBoundsAreChecked() {
        val bytes = ByteBuffer.allocate(68).put("wOFF".toByteArray()).putInt(65536).putInt(68).putShort(1).putShort(0)
            .putInt(32).array()
        ByteBuffer.wrap(bytes).putInt(44, 0x6e616d65).putInt(48, 64).putInt(52, 4).putInt(56, 4)
        assertEquals("font/woff", ReaderAssetFormat.inspect(bytes).mime)
        for ((offset, value) in listOf(8 to 999, 16 to Int.MAX_VALUE, 48 to Int.MAX_VALUE, 52 to 999)) {
            val malformed = bytes.copyOf(); ByteBuffer.wrap(malformed).putInt(offset, value)
            assertThrows(IllegalArgumentException::class.java) { ReaderAssetFormat.inspect(malformed) }
        }
        assertThrows(IllegalArgumentException::class.java) { ReaderAssetFormat.inspect(bytes.copyOf().apply { this[15] = 1 }) }
    }

    @Test fun gifHasBoundedDimensionsAndRequiresItsTerminator() {
        val bytes = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
        assertEquals("image/gif", ReaderAssetFormat.inspect(bytes).mime)
        assertThrows(IllegalArgumentException::class.java) { ReaderAssetFormat.inspect(bytes.copyOf(bytes.size - 1)) }
    }

    @Test fun unsupportedFilesAndFakeImageSignaturesAreRejected() {
        listOf("<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray(), ByteArray(100),
            "RIFFabcdefghWEBPVP8Xxxxx".toByteArray()).forEach { bytes ->
            assertThrows(IllegalArgumentException::class.java) { ReaderAssetFormat.inspect(bytes) }
        }
    }
}
