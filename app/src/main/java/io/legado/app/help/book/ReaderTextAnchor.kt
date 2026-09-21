package io.legado.app.help.book

/** Whitespace-independent anchor for switching between Canvas and generated HTML. */
@androidx.annotation.Keep
data class ReaderTextAnchor(val cue: String, val cueOffset: Int) {
    companion object {
        fun capture(text: String, offset: Int): ReaderTextAnchor {
            val position = offset.coerceIn(0, text.length)
            var start = (position - 12).coerceAtLeast(0)
            var end = (position + 64).coerceAtMost(text.length)
            // The bounded cue is persisted as text. Never cut a surrogate pair at
            // either end, otherwise UTF-8/XML persistence can corrupt the anchor.
            if (start > 0 && start < text.length && text[start].isLowSurrogate() &&
                text[start - 1].isHighSurrogate()) start++
            if (end > 0 && end < text.length && text[end - 1].isHighSurrogate() &&
                text[end].isLowSurrogate()) end--
            return ReaderTextAnchor(text.substring(start, end), position - start)
        }
    }

    fun locate(text: String, expectedOffset: Int): Int? {
        fun significant(c: Char) = !c.isWhitespace() && c != '\u200b' && c != '\ufeff'
        val needle = cue.filter(::significant)
        if (needle.isEmpty()) return null
        val offsets = IntArray(text.length)
        var count = 0
        val normalized = buildString(text.length) {
            text.forEachIndexed { index, c -> if (significant(c)) { offsets[count++] = index; append(c) } }
        }
        val inside = cue.take(cueOffset.coerceIn(0, cue.length)).count(::significant)
        var occurrence = normalized.indexOf(needle)
        var best: Int? = null
        while (occurrence >= 0) {
            val position = if (occurrence + inside >= count) text.length else offsets[occurrence + inside]
            if (best == null || kotlin.math.abs(position.toLong() - expectedOffset) < kotlin.math.abs(best.toLong() - expectedOffset)) best = position
            occurrence = normalized.indexOf(needle, occurrence + 1)
        }
        return best
    }
}
