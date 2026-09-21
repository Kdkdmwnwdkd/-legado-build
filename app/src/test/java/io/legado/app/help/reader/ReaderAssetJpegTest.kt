package io.legado.app.help.reader

import org.junit.Assert.*
import org.junit.Test

class ReaderAssetJpegTest {
    private fun sample(name: String) = requireNotNull(javaClass.getResourceAsStream("/reader-assets/$name.jpg")).use { it.readBytes() }
    private fun metadata(payload: ByteArray) = byteArrayOf(255.toByte(), 225.toByte(),
        ((payload.size + 2) ushr 8).toByte(), (payload.size + 2).toByte()) + payload

    @Test fun acceptsPaddingAndEditorDataAfterTheMainImageEnds() {
        val image = ReaderAssetFixtures.jpeg()
        val expected = ReaderAssetFormat.Format("image", "image/jpeg", "jpg", 2, 3)
        for (trailer in listOf(ByteArray(128), "\nPhoto editor metadata\n".toByteArray(),
            byteArrayOf(0, 0, 0, 24) + "ftypmp42".toByteArray() + ByteArray(64), image)) {
            assertEquals(expected, ReaderAssetFormat.inspect(image + trailer))
        }
    }

    @Test fun progressiveAndRestartImagesKeepTheirDimensionsWithTrailingData() {
        for (name in listOf("progressive", "restart")) {
            val image = sample(name)
            assertTrue("fixture must contain byte-stuffed entropy", (0 until image.lastIndex).any {
                image[it] == 255.toByte() && image[it + 1] == 0.toByte()
            })
            val expected = ReaderAssetFormat.Format("image", "image/jpeg", "jpg", 40, 24)
            assertEquals(expected, ReaderAssetFormat.inspect(image))
            assertEquals(expected, ReaderAssetFormat.inspect(image + ByteArray(32)))
        }
    }

    @Test fun embeddedThumbnailDoesNotSupplyTheMainImageDimensionsOrEndMarker() {
        val image = sample("progressive")
        val thumbnail = metadata("Exif\u0000\u0000".toByteArray() + ReaderAssetFixtures.jpeg())
        val outer = image.copyOfRange(0, 2) + thumbnail + image.copyOfRange(2, image.size)
        assertEquals(ReaderAssetFormat.Format("image", "image/jpeg", "jpg", 40, 24),
            ReaderAssetFormat.inspect(outer + ByteArray(8)))
        assertThrows(IllegalArgumentException::class.java) { ReaderAssetFormat.inspect(outer.copyOf(outer.size - 2)) }
    }

    @Test fun truncatedJpegHeadersAndScansAreRejectedWithAFormatError() {
        for (image in listOf(ReaderAssetFixtures.jpeg(), sample("progressive"), sample("restart"))) {
            for (size in image.indices) {
                assertThrows("length=$size/${image.size}", IllegalArgumentException::class.java) {
                    ReaderAssetFormat.inspect(image.copyOf(size))
                }
            }
        }
    }

    @Test fun anEndMarkerInsideMetadataCannotHideATruncatedScan() {
        val image = ReaderAssetFixtures.jpeg()
        val embeddedEnd = metadata(byteArrayOf(255.toByte(), 217.toByte()))
        val truncated = image.copyOfRange(0, 2) + embeddedEnd + image.copyOfRange(2, image.size - 2)
        assertThrows(IllegalArgumentException::class.java) { ReaderAssetFormat.inspect(truncated) }
        val oversized = image.copyOfRange(0, 2) + byteArrayOf(255.toByte(), 225.toByte(), 127, 127) + image
        assertThrows(IllegalArgumentException::class.java) { ReaderAssetFormat.inspect(oversized) }
    }

    @Test fun dimensionsAndAnEndMarkerWithoutImageDataAreRejected() {
        val image = ReaderAssetFixtures.jpeg()
        val scan = (0 until image.lastIndex).first { image[it] == 255.toByte() && image[it + 1] == 218.toByte() }
        val end = byteArrayOf(255.toByte(), 217.toByte())
        assertThrows(IllegalArgumentException::class.java) { ReaderAssetFormat.inspect(image.copyOf(scan) + end) }
        val length = ((image[scan + 2].toInt() and 255) shl 8) or (image[scan + 3].toInt() and 255)
        assertThrows(IllegalArgumentException::class.java) { ReaderAssetFormat.inspect(image.copyOf(scan + 2 + length) + end) }
    }
}
