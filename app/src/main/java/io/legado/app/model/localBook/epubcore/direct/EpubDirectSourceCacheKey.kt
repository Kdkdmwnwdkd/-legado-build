package io.legado.app.model.localBook.epubcore.direct

internal object EpubDirectSourceCacheKey {

    fun create(
        href: String,
        mediaType: String?,
        title: String,
        continuationHrefs: List<String>
    ): String {
        val normalizedType = mediaType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            .orEmpty()
        return buildString {
            append(href).append('|').append(normalizedType)
            if (normalizedType.startsWith("image/") ||
                normalizedType.startsWith("video/") ||
                normalizedType.startsWith("audio/")
            ) {
                append('|').append(title)
            } else {
                continuationHrefs.forEach { append('|').append(it) }
            }
        }
    }
}
