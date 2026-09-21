package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.model.localBook.epubcore.EpubRegex

/** Keeps classification heuristics from treating inactive print or speech CSS as screen layout. */
internal object EpubDirectCssMediaPolicy {

    fun mayApplyToScreen(media: String): Boolean {
        if (media.isBlank()) return true
        return splitQueries(media).any(::queryMayApplyToScreen)
    }

    fun screenRelevantCss(css: String): String {
        if (!css.contains("@media", ignoreCase = true)) return css
        val output = StringBuilder(css.length)
        var index = 0
        while (index < css.length) {
            when {
                css.startsWith("/*", index) -> {
                    val end = css.indexOf("*/", index + 2).let { if (it < 0) css.length else it + 2 }
                    output.append(css, index, end)
                    index = end
                }
                css[index] == '\'' || css[index] == '"' -> {
                    val end = quotedEnd(css, index)
                    output.append(css, index, end)
                    index = end
                }
                css.regionMatches(index, "@media", 0, 6, ignoreCase = true) &&
                    isAtRuleBoundary(css, index, index + 6) -> {
                    val blockStart = blockStart(css, index + 6)
                    if (blockStart < 0) {
                        output.append(css, index, css.length)
                        break
                    }
                    val blockEnd = matchingBrace(css, blockStart)
                    if (blockEnd < 0) {
                        output.append(css, index, css.length)
                        break
                    }
                    val query = css.substring(index + 6, blockStart).trim()
                    if (mayApplyToScreen(query)) {
                        output.append(css, index, blockStart + 1)
                        output.append(screenRelevantCss(css.substring(blockStart + 1, blockEnd)))
                        output.append('}')
                    }
                    index = blockEnd + 1
                }
                else -> output.append(css[index++])
            }
        }
        return output.toString()
    }

    private fun queryMayApplyToScreen(query: String): Boolean {
        val normalized = COMMENTS.replace(query, " ").trim().lowercase()
        if (normalized.isBlank()) return true
        val tokens = normalized.split(WHITESPACE).filter(String::isNotBlank)
        if (tokens.isEmpty()) return true
        val negated = tokens.first() == "not"
        val typeIndex = when {
            negated && tokens.getOrNull(1) == "only" -> 2
            negated -> 1
            tokens.first() == "only" -> 1
            else -> 0
        }
        val rawType = tokens.getOrNull(typeIndex).orEmpty()
        if (rawType.isBlank() || rawType.startsWith('(')) {
            // Feature-only queries may match a screen, including negated features.
            return true
        }
        val mediaType = rawType.substringBefore('(').trim()
        val typeMatchesScreen = mediaType == "screen" || mediaType == "all"
        if (!negated) return typeMatchesScreen

        // `not` negates the complete query. A condition can therefore make even
        // `not screen and (...)` true; retain it unless it is a simple not-screen query.
        val hasFeatureCondition = tokens.drop(typeIndex + 1).any { '(' in it }
        return !typeMatchesScreen || hasFeatureCondition
    }

    private fun splitQueries(media: String): List<String> {
        val result = arrayListOf<String>()
        var start = 0
        var parentheses = 0
        var index = 0
        while (index < media.length) {
            when {
                media.startsWith("/*", index) -> {
                    index = media.indexOf("*/", index + 2).let { if (it < 0) media.length else it + 2 }
                    continue
                }
                media[index] == '\'' || media[index] == '"' -> {
                    index = quotedEnd(media, index)
                    continue
                }
                media[index] == '(' -> parentheses++
                media[index] == ')' && parentheses > 0 -> parentheses--
                media[index] == ',' && parentheses == 0 -> {
                    result += media.substring(start, index)
                    start = index + 1
                }
            }
            index++
        }
        result += media.substring(start)
        return result
    }

    private fun blockStart(css: String, from: Int): Int {
        var index = from
        while (index < css.length) {
            when {
                css.startsWith("/*", index) -> {
                    index = css.indexOf("*/", index + 2).let { if (it < 0) return -1 else it + 2 }
                    continue
                }
                css[index] == '\'' || css[index] == '"' -> {
                    index = quotedEnd(css, index)
                    continue
                }
                css[index] == '{' -> return index
                css[index] == ';' -> return -1
            }
            index++
        }
        return -1
    }

    private fun matchingBrace(css: String, opening: Int): Int {
        var depth = 1
        var index = opening + 1
        while (index < css.length) {
            when {
                css.startsWith("/*", index) -> {
                    index = css.indexOf("*/", index + 2).let { if (it < 0) return -1 else it + 2 }
                    continue
                }
                css[index] == '\'' || css[index] == '"' -> {
                    index = quotedEnd(css, index)
                    continue
                }
                css[index] == '{' -> depth++
                css[index] == '}' && --depth == 0 -> return index
            }
            index++
        }
        return -1
    }

    private fun quotedEnd(source: String, opening: Int): Int {
        val quote = source[opening]
        var index = opening + 1
        while (index < source.length) {
            when {
                source[index] == '\\' -> index += 2
                source[index] == quote -> return index + 1
                else -> index++
            }
        }
        return source.length
    }

    private fun isAtRuleBoundary(source: String, start: Int, end: Int): Boolean {
        val before = source.getOrNull(start - 1)
        val after = source.getOrNull(end)
        return before?.let { !it.isLetterOrDigit() && it != '-' && it != '_' } != false &&
            after?.let { !it.isLetterOrDigit() && it != '-' && it != '_' } != false
    }

    private val COMMENTS = EpubRegex.compile("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    private val WHITESPACE = EpubRegex.compile("\\s+")
}
