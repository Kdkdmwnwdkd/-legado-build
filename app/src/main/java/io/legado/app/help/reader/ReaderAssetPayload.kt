package io.legado.app.help.reader

/** A verified, portable resource. Bytes are kept out of the on-device library index. */
data class ReaderAssetPayload(val id: String, val name: String, val bytes: ByteArray) {
    fun validate(): ReaderAssetFormat.Format {
        require(ReaderAssetReferences.validId(id) && ReaderAssetStore.sha256(bytes) == id) { "素材校验失败" }
        require(name.isNotBlank() && name.length <= 256) { "素材名称无效" }
        return ReaderAssetFormat.inspect(bytes)
    }
}
