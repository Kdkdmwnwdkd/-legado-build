package com.shimmer.reader.lib.cloud

enum class CloudStorageType {
    WEBDAV,
    S3;

    companion object {
        fun from(value: String?): CloudStorageType {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: WEBDAV
        }
    }
}

