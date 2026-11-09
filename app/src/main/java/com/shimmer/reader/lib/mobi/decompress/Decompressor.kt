package com.shimmer.reader.lib.mobi.decompress

interface Decompressor {

    fun decompress(data: ByteArray): ByteArray

}
