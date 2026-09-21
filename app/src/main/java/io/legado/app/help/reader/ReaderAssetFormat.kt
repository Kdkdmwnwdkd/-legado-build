package io.legado.app.help.reader

/** Bounded binary inspection, also used when resources arrive in a package. */
object ReaderAssetFormat {
    data class Format(val kind: String, val mime: String, val extension: String, val width: Int = 0, val height: Int = 0)
    const val MAX_FILE_BYTES = 64L * 1024 * 1024
    private const val MAX_PIXELS = 40_000_000L

    fun inspect(bytes: ByteArray): Format {
        require(bytes.size in 12..MAX_FILE_BYTES.toInt()) { "素材为空、损坏或超过 64 MiB" }
        fun byte(at: Int) = bytes[at].toInt() and 255
        fun u16(at: Int) = (byte(at) shl 8) or byte(at + 1)
        fun u32(at: Int) = (0..3).fold(0L) { value, index -> (value shl 8) or byte(at + index).toLong() }
        fun le(at: Int, count: Int) = (0 until count).fold(0) { value, index -> value or (byte(at + index) shl (index * 8)) }
        fun tag(at: Int, count: Int = 4) = String(bytes, at, count, Charsets.ISO_8859_1)
        fun image(mime: String, extension: String, width: Int, height: Int): Format {
            require(width in 1..32768 && height in 1..32768 && width.toLong() * height <= MAX_PIXELS) { "图片尺寸无效或超过 4000 万像素" }
            return Format("image", mime, extension, width, height)
        }
        if (byte(0) == 137 && tag(1, 3) == "PNG") {
            require(bytes.size >= 45 && bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte) &&
                u32(8) == 13L && tag(12) == "IHDR") { "PNG 图片头无效" }
            var at = 8
            var data = false
            var end = false
            while (at.toLong() + 12 <= bytes.size) {
                val length = u32(at)
                require(length + at + 12 <= bytes.size) { "PNG 图片内容不完整" }
                val type = tag(at + 4)
                val crc = java.util.zip.CRC32().apply { update(bytes, at + 4, length.toInt() + 4) }.value
                require(crc == u32(at + 8 + length.toInt())) { "PNG 图片校验失败" }
                if (type == "IDAT" && length > 0) data = true
                at += length.toInt() + 12
                if (type == "IEND") { require(length == 0L); end = true; break }
            }
            require(data && end && at == bytes.size) { "PNG 图片缺少内容或结束标记" }
            return image("image/png", "png", u32(16).toInt(), u32(20).toInt())
        }
        if (tag(0, 3) == "GIF" && tag(3, 3) in setOf("87a", "89a")) {
            require(bytes.size >= 14 && byte(bytes.lastIndex) == 59) { "GIF 图片内容不完整" }
            return image("image/gif", "gif", le(6, 2), le(8, 2))
        }
        if (byte(0) == 255 && byte(1) == 216) {
            var at = 2
            var format: Format? = null
            var inScan = false
            var scanData = false
            var completedScan = false
            // EOI ends the JPEG stream, not necessarily the file. Editors and motion
            // photos may append data; APP segments can also contain thumbnail EOIs.
            while (at < bytes.size) {
                if (inScan) {
                    while (at < bytes.size && byte(at) != 255) { at++; scanData = true }
                    if (at >= bytes.size) break
                }
                require(byte(at) == 255) { "JPEG 图片结构无效" }
                while (at < bytes.size && byte(at) == 255) at++
                if (at >= bytes.size) break
                val marker = byte(at++)
                if (marker == 0 || marker in 208..215) {
                    require(inScan) { "JPEG 图片结构无效" }
                    if (marker == 0) scanData = true
                    continue
                }
                if (marker == 1) continue
                if (inScan) {
                    require(scanData) { "JPEG 图片缺少图像数据" }
                    completedScan = true
                }
                if (marker == 217) {
                    require(completedScan) { "JPEG 图片缺少图像数据" }
                    return requireNotNull(format) { "JPEG 图片尺寸缺失" }
                }
                require(marker != 216) { "JPEG 图片结构无效" }
                require(at + 2 <= bytes.size) { "JPEG 图片内容不完整" }
                val length = u16(at)
                require(length >= 2 && at.toLong() + length <= bytes.size) { "JPEG 图片内容不完整" }
                if (marker in setOf(192, 193, 194, 195, 197, 198, 199, 201, 202, 203, 205, 206, 207)) {
                    require(length >= 8) { "JPEG 图片尺寸缺失" }
                    format = image("image/jpeg", "jpg", u16(at + 5), u16(at + 3))
                }
                if (marker == 218) {
                    require(format != null && length >= 6) { "JPEG 图片扫描头无效" }
                    scanData = false
                }
                // DNL may be embedded in entropy data; other segments end the scan.
                inScan = marker == 218 || (inScan && marker == 220)
                at += length
            }
            throw IllegalArgumentException("JPEG 图片缺少结束标记或内容不完整")
        }
        if (tag(0) == "RIFF" && tag(8) == "WEBP" && bytes.size >= 30) {
            require((le(4, 4).toLong() and 0xffffffffL) + 8 == bytes.size.toLong()) { "WebP 图片内容不完整" }
            var chunk = 12
            var imageData = false
            while (chunk.toLong() + 8 <= bytes.size) {
                val length = le(chunk + 4, 4).toLong() and 0xffffffffL
                require(chunk + 8L + length <= bytes.size) { "WebP 图片块不完整" }
                if (tag(chunk) in setOf("VP8 ", "VP8L", "ANMF") && length > 0) imageData = true
                chunk += 8 + length.toInt() + (length.toInt() and 1)
            }
            require(imageData && chunk == bytes.size) { "WebP 图片缺少图像数据" }
            return when (tag(12)) {
                "VP8X" -> image("image/webp", "webp", 1 + le(24, 3), 1 + le(27, 3))
                "VP8L" -> { require(byte(20) == 47); val bits = le(21, 4)
                    image("image/webp", "webp", 1 + (bits and 16383), 1 + (bits ushr 14 and 16383)) }
                "VP8 " -> { require(byte(23) == 157 && byte(24) == 1 && byte(25) == 42)
                    image("image/webp", "webp", le(26, 2) and 16383, le(28, 2) and 16383) }
                else -> error("不支持的 WebP 图片结构")
            }
        }
        fun sfnt(at: Int) {
            require(at >= 0 && at.toLong() + 12 <= bytes.size) { "字体文件头不完整" }
            require(tag(at) in setOf("OTTO", "true", "typ1") || u32(at) == 65536L) { "字体文件格式无效" }
            val count = u16(at + 4)
            require(count in 1..4096 && at.toLong() + 12 + count * 16 <= bytes.size) { "字体表目录无效" }
            repeat(count) { index ->
                val table = at + 12 + index * 16
                require(u32(table + 8) + u32(table + 12) <= bytes.size) { "字体文件内容不完整" }
            }
        }
        return when (val signature = tag(0)) {
            "ttcf" -> {
                val count = u32(8)
                require(count in 1..256 && 12 + count * 4 <= bytes.size) { "字体集合目录无效" }
                repeat(count.toInt()) { sfnt(u32(12 + it * 4).toInt()) }
                Format("font", "font/collection", "ttc")
            }
            "wOFF", "wOF2" -> {
                val header = if (signature == "wOFF") 44 else 48
                require(bytes.size >= header && u32(8) == bytes.size.toLong() && u16(12) in 1..4096 &&
                    u16(14) == 0 && u32(16) in 12..MAX_FILE_BYTES) { "网页字体文件头无效或内容不完整" }
                if (signature == "wOFF") {
                    val count = u16(12)
                    require(44L + count * 20 <= bytes.size) { "网页字体表目录无效" }
                    repeat(count) { index ->
                        val at = 44 + index * 20
                        require(u32(at + 4) + u32(at + 8) <= bytes.size && u32(at + 8) <= u32(at + 12)) { "网页字体内容不完整" }
                    }
                }
                if (signature == "wOF2") require(u32(20) > 0 && u32(20) + header <= bytes.size) { "网页字体压缩内容不完整" }
                Format("font", if (signature == "wOFF") "font/woff" else "font/woff2", if (signature == "wOFF") "woff" else "woff2")
            }
            else -> {
                sfnt(0)
                if (signature == "OTTO") Format("font", "font/otf", "otf") else Format("font", "font/ttf", "ttf")
            }
        }
    }
}
