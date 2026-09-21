package io.legado.app.help.book.highlight

object HighlightRuleSearch {
    /** Queries are literal text, including regexp punctuation in a rule's keyword. */
    fun filter(rules: List<HighlightRule>, query: String): List<HighlightRule> {
        val text = query.trim()
        if (text.isEmpty()) return rules
        return rules.filter {
            it.displayName().contains(text, ignoreCase = true) ||
                it.groupName.contains(text, ignoreCase = true) ||
                it.keyword.contains(text, ignoreCase = true)
        }
    }
}
