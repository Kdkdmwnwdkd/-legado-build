package com.shimmer.reader.lib.mobi.entities

import android.util.SparseArray

data class IndexEntry(
    val label: String,
    val tags: List<IndexTag>,
    val tagMap: SparseArray<IndexTag>
)
