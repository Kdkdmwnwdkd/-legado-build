package io.legado.app.model.localBook.epubcore.direct

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

/**
 * Owns the one Jsoup parse shared by Direct classification and document preparation.
 * The builder is allowed to mutate the document only after classification has finished.
 */
internal class EpubDirectParsedSource(
    val sourceHtml: String,
    private val parser: (String) -> Document? = { html ->
        if (html.isBlank()) null else {
            runCatching { Jsoup.parse(html, "", Parser.xmlParser()) }.getOrNull()
        }
    }
) {

    @Volatile
    private var attempted = false
    private var parsedDocument: Document? = null

    fun document(): Document? {
        if (attempted) return parsedDocument
        synchronized(this) {
            if (!attempted) {
                parsedDocument = parser(sourceHtml)
                attempted = true
            }
            return parsedDocument
        }
    }
}
