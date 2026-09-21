package io.legado.app.help.book.highlight

import org.jcodings.specific.UTF8Encoding
import org.joni.Matcher
import org.joni.Option
import org.joni.Regex
import org.joni.Syntax
import org.joni.WarnCallback
import org.joni.constants.SyntaxProperties
import org.joni.exception.SyntaxException

/** Matching never changes text offsets; overlapping rules retain their list order. */
class HighlightMatcher(rules: List<HighlightRule>) {
    data class Match(val start: Int, val end: Int, val rule: HighlightRule)
    private data class Lookbehind(val pattern: Regex, val negative: Boolean)
    private data class Compiled(val pattern: Regex, val lookbehinds: List<Lookbehind> = emptyList())
    private val compiled = rules.filter { it.enabled }.mapNotNull { rule ->
        runCatching { rule to compile(rule) }.getOrNull()
    }

    fun matches(text: String, title: Boolean = false): List<Match> {
        if (text.isEmpty() || compiled.isEmpty()) return emptyList()
        val result = ArrayList<Match>()
        val deadline = System.nanoTime() + 40_000_000L
        val input by lazy(LazyThreadSafetyMode.NONE) { text.toByteArray(Charsets.UTF_8) }
        for ((rule, pattern) in compiled) {
            if (System.nanoTime() >= deadline || result.size >= 4096) break
            if (rule.titleOnly && !title) continue
            if (rule.keyword.isEmpty()) {
                if (rule.titleOnly && title) result.add(Match(0, text.length, rule))
                continue
            }
            // Android's java.util.regex converts CharSequence to String before native matching,
            // so a charAt/read budget cannot interrupt its backtracking. Joni checks its own
            // execution deadline and returns without leaving a runaway worker behind.
            val bytes = input
            val matcher = pattern.pattern.matcher(bytes)
            val lookbehinds = pattern.lookbehinds.map { it.pattern.matcher(bytes) to it.negative }
            val offsets = Utf16Offsets(bytes)
            var cursor = 0
            var previousEnd = 0
            search@ while (result.size < 4096) {
                if (boundedSearch(matcher, bytes, previousEnd, cursor, bytes.size, deadline) < 0) break
                val begin = matcher.begin
                val end = matcher.end
                for ((assertion, negative) in lookbehinds) {
                    // \G pins the assertion's end to this candidate while retaining the
                    // complete input for lookaheads, word boundaries and end anchors.
                    val found = boundedSearch(assertion, bytes, begin, 0, begin, deadline)
                    if (found == Matcher.INTERRUPTED) break@search
                    if ((found >= 0) == negative) {
                        if (begin >= bytes.size) break@search
                        cursor = begin + utf8Width(bytes[begin])
                        continue@search
                    }
                }
                val startOffset = offsets.at(begin)
                val endOffset = offsets.at(end)
                if (end > begin && startOffset >= 0 && endOffset > startOffset) {
                    result.add(Match(startOffset, endOffset, rule))
                }
                previousEnd = end
                if (end > begin) cursor = end
                else if (end < bytes.size) cursor = end + utf8Width(bytes[end])
                else break
            }
        }
        return result
    }

    /** Each rule visits offsets in order: no repeated prefix decoding or large offset table. */
    private class Utf16Offsets(private val bytes: ByteArray) {
        private var byteOffset = 0
        private var charOffset = 0
        fun at(position: Int): Int {
            if (position < byteOffset || position > bytes.size) return -1
            while (byteOffset < position) {
                val width = utf8Width(bytes[byteOffset])
                if (byteOffset + width > position) return -1
                byteOffset += width
                charOffset += if (width == 4) 2 else 1
            }
            return charOffset
        }
    }

    companion object {
        /** Bound the scan too: many short failed attempts may never hit Joni's VM timer. */
        private fun boundedSearch(
            matcher: Matcher, bytes: ByteArray, previousEnd: Int, start: Int, end: Int, deadline: Long
        ): Int {
            var cursor = start
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return Matcher.INTERRUPTED
                var limit = minOf(end, cursor + 16)
                while (limit < end && bytes[limit].toInt() and 0xc0 == 0x80) limit--
                matcher.setTimeout(remaining)
                // Only candidate starting positions are limited. The input is never sliced,
                // preserving lookarounds and matches that continue beyond this batch.
                val found = matcher.search(previousEnd, cursor, limit, Option.NONE)
                if (found >= 0 || found == Matcher.INTERRUPTED || limit >= end) return found
                cursor = limit
            }
        }

        // Keep Java-style groups, lookarounds, backreferences and inline flags, including
        // modern Unicode/newline escapes used in imported rules. Use UTF-8: Joni's quoted
        // literal parser is not safe with the UTF-16 encoding.
        private val syntax = Syntax(
            "HighlightJava",
            Syntax.Java.op or SyntaxProperties.OP_ESC_X_BRACE_HEX8,
            (Syntax.Java.op2 and SyntaxProperties.OP2_ESC_V_VTAB.inv()) or
                SyntaxProperties.OP2_ESC_CAPITAL_R_LINEBREAK or
                SyntaxProperties.OP2_ESC_CAPITAL_X_EXTENDED_GRAPHEME_CLUSTER or
                SyntaxProperties.OP2_ESC_V_VERTICAL_WHITESPACE or
                SyntaxProperties.OP2_ESC_H_HORIZONTAL_WHITESPACE,
            Syntax.Java.op3,
            Syntax.Java.behavior,
            Syntax.Java.options,
            Syntax.Java.metaCharTable
        )

        private fun utf8Width(byte: Byte): Int = when (byte.toInt() and 0xff) {
            in 0..0x7f -> 1
            in 0xc0..0xdf -> 2
            in 0xe0..0xef -> 3
            else -> 4
        }

        private fun compile(rule: HighlightRule): Compiled {
            require(rule.keyword.length <= 16384) { "正则表达式过长" }
            val expression = if (rule.isRegex) normalizeProperties(rule.keyword) else rule.keyword
            val bytes = expression.toByteArray(Charsets.UTF_8)
            val options = if (rule.isMultiline) Option.MULTILINE or Option.NEGATE_SINGLELINE else Option.NONE
            if (!rule.isRegex) return Compiled(Regex(bytes, 0, bytes.size, options,
                UTF8Encoding.INSTANCE, Syntax.ASIS, WarnCallback.NONE))
            return try {
                Compiled(regex(expression, options))
            } catch (error: SyntaxException) {
                leadingLookbehinds(expression, options) ?: throw error
            }
        }

        private fun regex(expression: String, options: Int): Regex {
            val bytes = expression.toByteArray(Charsets.UTF_8)
            return Regex(bytes, 0, bytes.size, options, UTF8Encoding.INSTANCE, syntax, WarnCallback.NONE)
        }

        /** Reeden uses leading, variable-length lookbehinds for chapter initials. */
        private fun leadingLookbehinds(expression: String, options: Int): Compiled? {
            // An assertion before a top-level alternative must not constrain the other branch.
            var scan = 0
            while (scan < expression.length) {
                when (expression[scan]) {
                    '\\' -> scan += 2
                    '[' -> scan = classEnd(expression, scan) ?: return null
                    '(' -> scan = groupEnd(expression, scan) ?: return null
                    '|' -> return null
                    else -> scan++
                }
            }
            val kept = StringBuilder()
            val assertions = arrayListOf<Lookbehind>()
            var flags = ""
            var index = 0
            while (index < expression.length) {
                when {
                    expression[index] in "^$" -> kept.append(expression[index++])
                    expression[index] == '\\' && expression.getOrNull(index + 1) in listOf('A', 'b', 'B', 'G') -> {
                        kept.append(expression, index, index + 2)
                        index += 2
                    }
                    expression.startsWith("(?", index) -> {
                        val end = groupEnd(expression, index) ?: return null
                        val group = expression.substring(index, end)
                        when {
                            group.startsWith("(?<=") || group.startsWith("(?<!") -> {
                                if (runCatching { regex(flags + group, options) }.isSuccess) {
                                    kept.append(group)
                                } else {
                                    val body = group.substring(4, group.lastIndex)
                                    // Captures/backreferences cannot be moved to a separate assertion
                                    // matcher; leave those unsupported instead of changing their meaning.
                                    if (body.contains("\\G") || body.contains("\\k") ||
                                        (1..9).any { body.contains("\\$it") }) return null
                                    val assertion = regex(flags + "(?:$body)\\G", options)
                                    if (assertion.numberOfCaptures() != 0) return null
                                    assertions.add(Lookbehind(assertion, group.startsWith("(?<!")))
                                }
                            }
                            group.startsWith("(?=") || group.startsWith("(?!") -> kept.append(group)
                            group.substring(2, group.lastIndex).all { it in "imsxduU-" } -> {
                                flags += group
                                kept.append(group)
                            }
                            else -> break
                        }
                        index = end
                    }
                    else -> break
                }
            }
            if (assertions.isEmpty()) return null
            kept.append(expression, index, expression.length)
            return Compiled(regex(kept.toString(), options), assertions)
        }

        private fun classEnd(expression: String, start: Int): Int? {
            var index = start + 1
            if (expression.getOrNull(index) == '^') index++
            if (expression.getOrNull(index) == ']') index++
            while (index < expression.length) {
                when (expression[index++]) {
                    '\\' -> index++
                    ']' -> return index
                }
            }
            return null
        }

        private fun groupEnd(expression: String, start: Int): Int? {
            var depth = 1
            var index = start + 1
            var quoted = false
            while (index < expression.length) {
                if (quoted) {
                    if (expression.startsWith("\\E", index)) { quoted = false; index += 2 }
                    else index++
                    continue
                }
                when (expression[index++]) {
                    '\\' -> { if (expression.getOrNull(index) == 'Q') quoted = true; index++ }
                    '[' -> index = classEnd(expression, index - 1) ?: return null
                    '(' -> depth++
                    ')' -> if (--depth == 0) return index
                }
            }
            return null
        }

        /** Normalize Java/ICU property aliases without rewriting escaped or quoted literals. */
        private fun normalizeProperties(expression: String): String = buildString {
            var index = 0
            var quoted = false
            while (index < expression.length) {
                if (quoted) {
                    if (expression.startsWith("\\E", index)) {
                        append("\\E"); index += 2; quoted = false
                    } else append(expression[index++])
                    continue
                }
                val char = expression[index++]
                if (char != '\\' || index == expression.length) {
                    append(char)
                    continue
                }
                val escaped = expression[index++]
                if (escaped == 'Q' && !quoted) quoted = true
                if (!quoted && escaped in "pP" && expression.getOrNull(index) == '{') {
                    val end = expression.indexOf('}', index + 1)
                    if (end >= 0) {
                        val property = expression.substring(index + 1, end)
                        val normalized = when {
                            property.startsWith("Is") -> property.substring(2)
                            property.substringBefore('=') in setOf("sc", "script", "gc", "general_category") ->
                                property.substringAfter('=')
                            property.substringBefore('=') in setOf("blk", "block") -> "In" + property.substringAfter('=')
                            else -> property
                        }
                        append('\\').append(escaped).append('{').append(normalized).append('}')
                        index = end + 1
                        continue
                    }
                }
                append('\\').append(escaped)
            }
        }

        fun validationError(rule: HighlightRule): String? {
            if (rule.keyword.isEmpty() && !rule.titleOnly) return "正文匹配内容不能为空"
            return runCatching { compile(rule); null }.getOrElse { it.message ?: "正则表达式无效" }
        }
    }
}
