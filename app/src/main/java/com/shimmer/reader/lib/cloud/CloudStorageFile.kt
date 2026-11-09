package com.shimmer.reader.lib.cloud

data class CloudStorageFile(
    val path: String,
    val displayName: String,
    val size: Long = 0,
    val lastModify: Long = 0,
    val isDir: Boolean = false
)

