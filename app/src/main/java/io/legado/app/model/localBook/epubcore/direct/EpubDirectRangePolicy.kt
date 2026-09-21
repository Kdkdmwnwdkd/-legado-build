package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.model.localBook.epubcore.EpubRegex

internal data class EpubDirectByteRange(
    val start: Long,
    val endInclusive: Long
) {
    val length: Long get() = endInclusive - start + 1L
}

internal object EpubDirectRangePolicy {

    fun parse(header: String?, size: Long): EpubDirectByteRange? {
        if (header.isNullOrBlank() || size <= 0L) return null
        val match = RANGE_HEADER.matchEntire(header.trim()) ?: return null
        val value = match.groupValues[1].trim()
        if ('-' !in value) return null
        val startText = value.substringBefore('-').trim()
        val endText = value.substringAfter('-').trim()
        if (startText.isBlank()) {
            val suffixLength = endText.toLongOrNull()?.coerceAtMost(size) ?: return null
            if (suffixLength <= 0L) return null
            return EpubDirectByteRange(size - suffixLength, size - 1L)
        }
        val start = startText.toLongOrNull() ?: return null
        if (start !in 0 until size) return null
        val end = endText.toLongOrNull()?.coerceAtMost(size - 1L) ?: (size - 1L)
        if (end < start) return null
        return EpubDirectByteRange(start, end)
    }

    fun shouldPrepareDiskCache(header: String?, size: Long?): Boolean {
        if (size == null) return false
        return parse(header, size)?.start?.let { it > 0L } == true
    }

    private val RANGE_HEADER = EpubRegex.compile(
        "bytes\\s*=\\s*([^,]+)",
        RegexOption.IGNORE_CASE
    )
}
