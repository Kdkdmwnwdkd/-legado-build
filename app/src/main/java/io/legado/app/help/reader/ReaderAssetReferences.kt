package io.legado.app.help.reader

/** Stable local references contain a content hash, never a device path or access grant. */
object ReaderAssetReferences {
    const val HOST = "reader-assets.epub.local"
    const val URL_PREFIX = "https://$HOST/"
    const val FONT_PREFIX = "legado-resource-"
    private val idPattern = Regex("[a-f0-9]{64}")
    private val urls = Regex("(?i:" + Regex.escape(URL_PREFIX) + ")([a-f0-9]{64})(?![a-zA-Z0-9_/-])")
    private val fonts = Regex(Regex.escape(FONT_PREFIX) + "([a-f0-9]{64})(?![a-zA-Z0-9_-])")

    fun validId(id: String) = idPattern.matches(id)
    fun url(id: String): String { require(validId(id)); return URL_PREFIX + id }
    fun fontFamily(id: String): String { require(validId(id)); return FONT_PREFIX + id }
    fun fontIds(source: String): Set<String> = fonts.findAll(source).map { it.groupValues[1] }.toSet()
    fun ids(source: String): Set<String> = fontIds(source) + urls.findAll(source).map { it.groupValues[1] }

    fun fontCss(source: String): String = fontIds(source).joinToString("\n") { id ->
        "@font-face{font-family:'${fontFamily(id)}';src:url('${url(id)}');font-display:block;}"
    }

    fun idFromUrl(value: String): String? = runCatching {
        val uri = java.net.URI(value)
        if (!"https".equals(uri.scheme, true) || !HOST.equals(uri.host, true) || uri.port != -1 ||
            uri.rawUserInfo != null || uri.rawQuery != null) return@runCatching null
        uri.rawPath?.removePrefix("/")?.takeIf { uri.rawPath == "/$it" && validId(it) }
    }.getOrNull()

    fun selectedFont(css: String): String? = declarations(css).lastOrNull {
        it.substringBefore(':').trim().equals("font-family", true)
    }?.substringAfter(':')?.let { fontIds(it).firstOrNull() }

    /** Replace only font-family, including duplicate old declarations. */
    fun withFont(css: String, id: String?): String {
        val kept = declarations(css).filterNot { it.substringBefore(':').trim().equals("font-family", true) }
            .filter { it.isNotBlank() }.toMutableList()
        if (id != null) kept.add("font-family:'${fontFamily(id)}'")
        return kept.joinToString(";")
    }

    /** Semicolons in strings, data URLs and functions are not declaration boundaries. */
    private fun declarations(css: String): List<String> {
        val parts = arrayListOf<String>()
        val part = StringBuilder()
        var quote: Char? = null
        var depth = 0
        var escaped = false
        var comment = false
        var index = 0
        while (index < css.length) {
            val char = css[index]
            val next = css.getOrNull(index + 1)
            if (comment) {
                if (char == '*' && next == '/') { comment = false; index++ }
            } else if (escaped) {
                part.append(char); escaped = false
            } else if (char == '\\') {
                part.append(char); escaped = true
            } else if (quote != null) {
                part.append(char); if (char == quote) quote = null
            } else when {
                char == '/' && next == '*' -> { comment = true; part.append(' '); index++ }
                char == '\'' || char == '"' -> { quote = char; part.append(char) }
                char == '(' -> { depth++; part.append(char) }
                char == ')' -> { depth = maxOf(0, depth - 1); part.append(char) }
                char == ';' && depth == 0 -> { parts.add(part.toString()); part.setLength(0) }
                else -> part.append(char)
            }
            index++
        }
        parts.add(part.toString())
        return parts
    }
}
