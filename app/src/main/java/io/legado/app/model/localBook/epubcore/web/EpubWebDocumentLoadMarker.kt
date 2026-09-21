package io.legado.app.model.localBook.epubcore.web

import io.legado.app.model.localBook.epubcore.EpubRegex

/**
 * Identifies the document belonging to a particular WebView load generation.
 *
 * `onPageFinished` is not reliable for hidden WebViews on every Chromium build,
 * and its callback URL can be normalized differently from loadDataWithBaseURL.
 * The marker lets callers verify the live DOM before proceeding without treating
 * an old document callback as the newly requested chapter.
 */
internal object EpubWebDocumentLoadMarker {

    fun inject(html: String, token: Long): String {
        val marker = tag(token)
        val headClose = HeadClose.find(html)
        if (headClose != null) {
            return html.substring(0, headClose.range.first) + marker + html.substring(headClose.range.first)
        }
        val headSelfClosing = HeadSelfClosing.find(html)
        if (headSelfClosing != null) {
            return html.replaceRange(headSelfClosing.range, "<head>$marker</head>")
        }
        val headOpen = HeadOpen.find(html)
        if (headOpen != null) {
            return html.replaceRange(headOpen.range, headOpen.value + marker)
        }
        val htmlOpen = HtmlOpen.find(html)
        if (htmlOpen != null) {
            return html.replaceRange(htmlOpen.range, htmlOpen.value + "<head>$marker</head>")
        }
        return "<!doctype html><html><head>$marker</head><body>$html</body></html>"
    }

    fun tag(token: Long): String {
        return "<meta id=\"$MarkerId\" data-token=\"$token\">"
    }

    fun readyScript(token: Long): String {
        return "(function(){var marker=document.getElementById('$MarkerId');" +
            "return !!(marker&&marker.getAttribute('data-token')==='$token'&&" +
            "document.readyState!=='loading');})()"
    }

    fun isReadyResult(raw: String?): Boolean {
        return raw?.trim() == "true"
    }

    private const val MarkerId = "legado-epub-document-load-marker"
    private val HeadClose = EpubRegex.compile("</head\\s*>", RegexOption.IGNORE_CASE)
    private val HeadSelfClosing = EpubRegex.compile(
        "<head\\b[^>]*?/\\s*>",
        RegexOption.IGNORE_CASE
    )
    private val HeadOpen = EpubRegex.compile("<head\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val HtmlOpen = EpubRegex.compile("<html\\b[^>]*>", RegexOption.IGNORE_CASE)
}
