package io.legado.app.ui.book.read.page

internal data class StyledLottieJson(
    val json: String,
    val compositionSize: LottieDecodeSize?
)

internal object AdvancedTitleLottieKeys {

    const val MAX_STYLABLE_JSON_CHARS = 512 * 1024
    const val MAX_STYLABLE_JSON_UTF8_BYTES = 512 * 1024

    fun canApplyFallbackStyle(rawJson: String): Boolean {
        if (rawJson.length > MAX_STYLABLE_JSON_CHARS) return false
        var bytes = 0
        var index = 0
        while (index < rawJson.length) {
            val char = rawJson[index]
            bytes += when {
                char.code <= 0x7f -> 1
                char.code <= 0x7ff -> 2
                Character.isHighSurrogate(char) &&
                    index + 1 < rawJson.length &&
                    Character.isLowSurrogate(rawJson[index + 1]) -> {
                    index++
                    4
                }
                else -> 3
            }
            if (bytes > MAX_STYLABLE_JSON_UTF8_BYTES) return false
            index++
        }
        return true
    }

    fun styledJson(rawJson: String, fallbackColor: Int, textScaleBits: Int): String {
        return "advanced_title_style:" +
            LottieImageMemoryPolicy.sourceSha256(rawJson) +
            ":$fallbackColor:$textScaleBits"
    }

    fun composition(styledJson: String, viewWidth: Int, viewHeight: Int): String {
        return "advanced_title:" +
            LottieImageMemoryPolicy.sourceSha256(styledJson) +
            ":$viewWidth:$viewHeight"
    }
}

/** Access-ordered cache bounded by both character count and serialized UTF-8 size. */
internal class StyledLottieJsonCache(
    private val maxChars: Int,
    private val maxUtf8Bytes: Int,
    private val maxEntryChars: Int = maxChars / 2,
    private val maxEntryUtf8Bytes: Int = maxUtf8Bytes / 2
) {

    private data class Entry(
        val value: StyledLottieJson,
        val chars: Int,
        val utf8Bytes: Int
    )

    private val entries = LinkedHashMap<String, Entry>(8, 0.75f, true)
    private var charCount = 0
    private var utf8ByteCount = 0

    init {
        require(maxChars > 0 && maxUtf8Bytes > 0)
        require(maxEntryChars in 1..maxChars)
        require(maxEntryUtf8Bytes in 1..maxUtf8Bytes)
    }

    @Synchronized
    operator fun get(key: String): StyledLottieJson? = entries[key]?.value

    @Synchronized
    fun put(key: String, value: StyledLottieJson): Boolean {
        val chars = value.json.length
        val utf8Bytes = utf8Size(value.json, maxEntryUtf8Bytes)
        entries.remove(key)?.let(::subtract)
        if (chars > maxEntryChars || utf8Bytes > maxEntryUtf8Bytes) return false

        while (entries.isNotEmpty() &&
            (charCount + chars > maxChars || utf8ByteCount + utf8Bytes > maxUtf8Bytes)
        ) {
            val eldest = entries.entries.iterator().next()
            entries.remove(eldest.key)
            subtract(eldest.value)
        }
        entries[key] = Entry(value, chars, utf8Bytes)
        charCount += chars
        utf8ByteCount += utf8Bytes
        return true
    }

    @Synchronized
    fun clear() {
        entries.clear()
        charCount = 0
        utf8ByteCount = 0
    }

    @Synchronized
    internal fun stats(): Triple<Int, Int, Int> = Triple(entries.size, charCount, utf8ByteCount)

    private fun subtract(entry: Entry) {
        charCount -= entry.chars
        utf8ByteCount -= entry.utf8Bytes
    }

    private fun utf8Size(value: String, stopAfter: Int): Int {
        var bytes = 0L
        var index = 0
        while (index < value.length && bytes <= stopAfter) {
            val char = value[index]
            bytes += when {
                char.code <= 0x7f -> 1
                char.code <= 0x7ff -> 2
                Character.isHighSurrogate(char) &&
                    index + 1 < value.length &&
                    Character.isLowSurrogate(value[index + 1]) -> {
                    index++
                    4
                }
                else -> 3
            }
            index++
        }
        return bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
