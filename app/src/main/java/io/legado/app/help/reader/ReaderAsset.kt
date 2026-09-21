package io.legado.app.help.reader

import androidx.annotation.Keep

@Keep
data class ReaderAsset(
    val id: String,
    val name: String,
    val kind: String,
    val mimeType: String,
    val extension: String,
    val size: Long,
    val width: Int = 0,
    val height: Int = 0
)

@Keep
data class ReaderAssetFolder(val id: String, val name: String, val uri: String)

@Keep
data class ReaderAssetLibrary(
    val version: Int = 1,
    val assets: List<ReaderAsset> = emptyList(),
    val folders: List<ReaderAssetFolder> = emptyList()
)
