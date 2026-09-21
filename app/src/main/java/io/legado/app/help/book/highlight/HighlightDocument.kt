package io.legado.app.help.book.highlight

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.text.BreakIterator
import java.util.Locale

/** Decorates text before pagination. Original words, block attributes and resource URLs survive. */
object HighlightDocument {
    private val excluded = setOf("script", "style", "noscript", "template", "svg", "math",
        "rt", "rp", "textarea", "iframe", "object", "audio", "video")
    private data class Block(val parent: Element, val nodes: List<Node>, val text: String, val start: Int, val title: Boolean)

    fun apply(
        html: String,
        rules: List<HighlightRule>,
        palette: HighlightStyle.Palette = HighlightStyle.Palette(),
        assetUrl: (String) -> String
    ): String {
        if (rules.none { it.enabled }) return html
        val document = Jsoup.parse(html)
        if (document.getElementById("legado-reeden-highlight-style") != null) return html
        document.outputSettings().prettyPrint(false)
        val blocks = arrayListOf<Block>()
        val chapterText = StringBuilder()
        fun collect(parent: Element, depth: Int) {
            if (parent.normalName() in excluded || depth > 128 || chapterText.length > 2 * 1024 * 1024) return
            val inline = arrayListOf<Node>()
            fun flush() {
                if (inline.isEmpty()) return
                val text = inline.joinToString("") { visibleText(it) }
                if (text.isNotBlank()) {
                    if (chapterText.isNotEmpty()) chapterText.append('\n')
                    val title = parent.normalName() in setOf("h1", "h2", "h3", "h4", "h5", "h6") ||
                        parent.attr("data-reader-kind") == "title" || parent.hasClass("reader-chapter-title")
                    blocks.add(Block(parent, inline.toList(), text, chapterText.length, title))
                    chapterText.append(text)
                }
                inline.clear()
            }
            parent.childNodes().forEach { node ->
                if (node is Element && node.isBlock && node.normalName() !in excluded) {
                    flush()
                    collect(node, depth + 1)
                } else inline.add(node)
            }
            flush()
        }
        collect(document.body(), 0)
        if (chapterText.length > 2 * 1024 * 1024) return html
        val allMatches = HighlightMatcher(rules.filterNot { it.titleOnly }).matches(chapterText.toString())
        val titles = HighlightMatcher(rules.filter { it.titleOnly })
        val used = hashSetOf<String>()
        val order = rules.withIndex().associate { it.value.id to it.index }
        val classes = rules.mapIndexed { index, rule -> rule.id to "legado-highlight-" + index }.toMap()
        val layouts = rules.mapNotNull { rule ->
            HighlightStyle.inlineLayout(rule, palette)?.let { rule.id to it }
        }.toMap()
        val lineBreaks by lazy(LazyThreadSafetyMode.NONE) { BreakIterator.getLineInstance(Locale.ROOT) }
        for (block in blocks) {
            val matches = (allMatches.filter {
                it.start < block.start + block.text.length && it.end > block.start
            }.map {
                it.copy(start = maxOf(0, it.start - block.start), end = minOf(block.text.length, it.end - block.start))
            } + if (block.title) titles.matches(block.text, true) else emptyList())
                .sortedBy { order[it.rule.id] }
            if (matches.isEmpty()) continue
            matches.forEach { used.add(it.rule.id) }
            val whole = matches.filter { it.start == 0 && it.end == block.text.length }
            // One background for a complete paragraph/title, including its nested emphasis.
            val entireParent = block.nodes == block.parent.childNodes()
            val inlineStyle = matches.any {
                Regex("\\bdisplay\\s*:\\s*inline\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.rule.styleCssText)
            }
            if (entireParent && !inlineStyle && matches.all { it in whole }) {
                whole.forEach { block.parent.addClass(classes.getValue(it.rule.id)) }
                continue
            }
            val cuts = (listOf(0, block.text.length) + matches.flatMap { listOf(it.start, it.end) }).distinct().sorted()
            val originals = block.nodes.map { it.clone() }
            val seenIds = hashSetOf<String>()
            val replacement = arrayListOf<Node>()
            val contentStart = block.text.indexOfFirst { !it.isWhitespace() }
            val contentEnd = block.text.indexOfLast { !it.isWhitespace() } + 1
            if (matches.any { it.rule.id in layouts }) lineBreaks.setText(block.text)
            val activeParts = cuts.zipWithNext { start, end -> matches.filter { it.start <= start && it.end >= end } }
            val artworkParts = activeParts.map { active -> active.filter { it.rule.id in layouts } }
            val gapBefore = artworkParts.indices.map { index ->
                artworkParts[index].isNotEmpty() && cuts[index] > contentStart && breakableGap(block.text, cuts[index], lineBreaks)
            }
            val gapAfter = artworkParts.indices.map { index ->
                artworkParts[index].isNotEmpty() && cuts[index + 1] < contentEnd && breakableGap(block.text, cuts[index + 1], lineBreaks)
            }
            for (index in 0 until cuts.lastIndex) {
                val start = cuts[index]
                val end = cuts[index + 1]
                val active = activeParts[index]
                if (active.isEmpty()) {
                    var leadingEnd = start
                    var trailingStart = end
                    if (gapAfter.getOrNull(index - 1) == true) {
                        while (leadingEnd < end && collapsibleSpace(block.text[leadingEnd])) leadingEnd++
                    }
                    if (gapBefore.getOrNull(index + 1) == true) {
                        while (trailingStart > start && collapsibleSpace(block.text[trailingStart - 1])) trailingStart--
                    }
                    if (leadingEnd == start && trailingStart == end) {
                        replacement.addAll(slice(originals, start, end, block.text.length, seenIds))
                        continue
                    }
                    // Existing whitespace must own the gap. A second generated
                    // separator could remain at the new line after that space
                    // has already wrapped. Keep every source character and its
                    // inline markup, letting CSS collapse the original space.
                    val spaceCuts = listOf(start, leadingEnd, trailingStart, end).distinct().sorted()
                    spaceCuts.zipWithNext().forEach { (left, right) ->
                        val nodes = slice(originals, left, right, block.text.length, seenIds)
                        val space = Element("span").attr("data-legado-highlight-source-gap", "true")
                        if (right <= leadingEnd) artworkParts[index - 1].forEach {
                            space.addClass(classes.getValue(it.rule.id) + "-gap-after")
                        }
                        if (left >= trailingStart) artworkParts[index + 1].forEach {
                            space.addClass(classes.getValue(it.rule.id) + "-gap-before")
                        }
                        if (space.classNames().isEmpty()) replacement.addAll(nodes)
                        else { nodes.forEach(space::appendChild); replacement.add(space) }
                    }
                }
                else {
                    val span = Element("span").attr("data-legado-highlight", "true")
                    active.forEach { span.addClass(classes.getValue(it.rule.id)) }
                    slice(originals, start, end, block.text.length, seenIds).forEach(span::appendChild)
                    val artwork = artworkParts[index]
                    if (artwork.isEmpty()) replacement.add(span)
                    else {
                        val spacing = Element("span").attr("data-legado-highlight-spacing", "true")
                        artwork.forEach { spacing.addClass(classes.getValue(it.rule.id) + "-spacing") }
                        // At paragraph edges the artwork can use the page margin
                        // (and first-line indent). Only internal match boundaries
                        // need separation from neighboring unhighlighted text.
                        val boundaryStyle = buildString {
                            if (start <= contentStart) append("margin-left:0!important;")
                            if (end >= contentEnd) append("margin-right:0!important;")
                        }
                        if (boundaryStyle.isNotEmpty()) spacing.attr("style", boundaryStyle)
                        // An inline margin survives when a match wraps to the
                        // next line. A generated, collapsible gap uses the same
                        // artwork space inside a line and disappears at its edge.
                        // Keep margins at nonbreaking boundaries (punctuation,
                        // word interiors, NBSP) so decoration cannot split them.
                        fun gap(side: String, sourceSpace: Boolean) {
                            spacing.attr("data-legado-highlight-gap-$side", "true")
                            if (!sourceSpace) spacing.appendElement("span").attr("data-legado-highlight-gap", side)
                                .attr("aria-hidden", "true")
                        }
                        if (gapBefore[index]) gap("before",
                            activeParts.getOrNull(index - 1)?.isEmpty() == true && collapsibleSpace(block.text[start - 1]))
                        spacing.appendChild(span)
                        if (gapAfter[index]) gap("after",
                            activeParts.getOrNull(index + 1)?.isEmpty() == true && collapsibleSpace(block.text[end]))
                        replacement.add(spacing)
                    }
                }
            }
            val first = block.nodes.first()
            val insets = matches.mapNotNull { layouts[it.rule.id]?.inset }.distinct()
            if (insets.isEmpty()) replacement.forEach { first.before(it) }
            else {
                // Artwork may use the reader's existing page margins. Reserve only
                // the missing space in narrow-margin layouts, keeping justified text
                // aligned with ordinary prose when the configured margins suffice.
                val inset = "max(${insets.joinToString(",")})"
                val flow = Element("span").attr("data-legado-highlight-flow", "true")
                    .attr("style", "padding-left:max(0px,calc($inset - var(--legado-highlight-room-left,0px)))!important;" +
                        "padding-right:max(0px,calc($inset - var(--legado-highlight-room-right,0px)))!important")
                replacement.forEach(flow::appendChild)
                first.before(flow)
            }
            block.nodes.forEach(Node::remove)
        }
        if (used.isEmpty()) return html
        // CSS order is the management order, independent of which paragraph first matched a rule.
        val css = rules.filter { it.id in used }.joinToString("\n") { rule ->
            val selector = "." + classes.getValue(rule.id)
            selector + "{" + HighlightStyle.css(rule, palette, assetUrl) + "}" +
                layouts[rule.id]?.let { layout ->
                    "$selector-spacing{margin-left:${layout.spaceLeft}!important;margin-right:${layout.spaceRight}!important;" +
                        "--legado-highlight-gap-left:${layout.spaceLeft};--legado-highlight-gap-right:${layout.spaceRight};}" +
                        "$selector-gap-before{--legado-source-gap-left:${layout.spaceLeft};}" +
                        "$selector-gap-after{--legado-source-gap-right:${layout.spaceRight};}"
                }.orEmpty()
        }
        document.head().appendElement("style").attr("id", "legado-reeden-highlight-style").appendText(
            io.legado.app.help.reader.ReaderAssetReferences.fontCss(css) + "\n" + css + if (layouts.isEmpty()) "" else
                "\n[data-legado-highlight-flow]{display:block!important;box-sizing:border-box!important;" +
                    "line-height:var(--legado-highlight-line-grid,inherit)!important;" +
                    "min-width:0!important;break-inside:auto!important;page-break-inside:auto!important;}" +
                    "[data-legado-highlight-spacing]{display:inline!important;" +
                    "box-decoration-break:slice!important;-webkit-box-decoration-break:slice!important;}" +
                    "[data-legado-highlight-spacing][data-legado-highlight-gap-before]{margin-left:0!important;}" +
                    "[data-legado-highlight-spacing][data-legado-highlight-gap-after]{margin-right:0!important;}" +
                    "[data-legado-highlight-gap=before]{word-spacing:var(--legado-highlight-gap-left)!important;}" +
                    "[data-legado-highlight-gap=after]{word-spacing:var(--legado-highlight-gap-right)!important;}" +
                    "[data-legado-highlight-source-gap]{display:inline!important;word-spacing:calc(var(--legado-source-gap-left,0px) + " +
                    "var(--legado-source-gap-right,0px))!important;}" +
                    // Resolve em/rem against the parent font before the generated
                    // space inherits word-spacing with a zero-width font.
                    "[data-legado-highlight-gap]::before{content:' ';font-size:0!important;" +
                    "line-height:0!important;letter-spacing:0!important;word-spacing:inherit!important;white-space:normal!important;}")
        return document.outerHtml()
    }

    private fun breakableGap(text: String, offset: Int, breaks: BreakIterator): Boolean {
        // Older platform line-break tables do not all recognize the same joiners.
        // Generated whitespace must never cut an explicit glue/emoji sequence.
        val glue = "\u2060\uFEFF\u200D\u00A0\u202F\u2007\u2011"
        if (offset > 0 && text[offset - 1] in glue || offset < text.length && text[offset] in glue) return false
        // ICU's root locale glues curly opening quotes to the preceding text,
        // unlike Chromium's CJK wrapping. Do not retain an artwork margin at
        // that visual line start just because platform iterator data differs.
        if (offset > 0 && offset < text.length) {
            val next = text.codePointAt(offset)
            val previous = Character.getType(text.codePointBefore(offset))
            if (Character.getType(next) == Character.INITIAL_QUOTE_PUNCTUATION.toInt() &&
                previous != Character.START_PUNCTUATION.toInt() && previous != Character.INITIAL_QUOTE_PUNCTUATION.toInt()) return true
            // Older ICU tables also glue a closing quote to a following CJK
            // letter. WebView can wrap there; punctuation and combining marks
            // must still keep their normal nonbreaking behavior. Block names
            // also cover newer CJK extensions without newer Android API calls.
            if (previous == Character.FINAL_QUOTE_PUNCTUATION.toInt() && Character.isLetterOrDigit(next)) {
                val block = Character.UnicodeBlock.of(next)?.toString().orEmpty()
                if (block.startsWith("CJK_") || block.startsWith("HIRAGANA") ||
                    block.startsWith("KATAKANA") || block.startsWith("HANGUL_")) return true
            }
        }
        var boundary = offset
        // Line breaks are recorded after collapsible whitespace. Do not skip
        // nonbreaking spaces, which Character.isWhitespace deliberately excludes.
        while (boundary < text.length && Character.isWhitespace(text[boundary])) boundary++
        return breaks.isBoundary(boundary)
    }

    private fun collapsibleSpace(char: Char): Boolean = char in " \t\r\n\u000C"

    private fun visibleText(node: Node, depth: Int = 0): String {
        if (depth > 128) return ""
        return when (node) {
            is TextNode -> node.wholeText
            is Element -> when (node.normalName()) {
                in excluded -> ""
                "br" -> "\n"
                else -> node.childNodes().joinToString("") { visibleText(it, depth + 1) }
            }
            else -> ""
        }
    }

    /** Split inline ancestors at match edges, retaining images and unique fragment anchors. */
    private fun slice(nodes: List<Node>, start: Int, end: Int, total: Int, seenIds: MutableSet<String>): List<Node> {
        var offset = 0
        val output = arrayListOf<Node>()
        for (node in nodes) {
            val length = visibleText(node).length
            val left = maxOf(start, offset)
            val right = minOf(end, offset + length)
            if (length == 0) {
                if (offset >= start && (offset < end || offset == total && end == total)) output.add(node.clone())
            } else if (left < right) {
                val copy = when (node) {
                    is TextNode -> TextNode(node.wholeText.substring(left - offset, right - offset))
                    is Element -> node.clone().also { element ->
                        if (element.normalName() != "br") {
                            element.empty()
                            slice(node.childNodes(), left - offset, right - offset, length, seenIds)
                                .forEach(element::appendChild)
                        }
                        if (element.id().isNotEmpty() && !seenIds.add(element.id())) element.removeAttr("id")
                    }
                    else -> node.clone()
                }
                output.add(copy)
            }
            offset += length
        }
        return output
    }
}
