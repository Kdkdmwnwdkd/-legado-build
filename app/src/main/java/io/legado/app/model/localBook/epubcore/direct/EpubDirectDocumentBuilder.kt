package io.legado.app.model.localBook.epubcore.direct

import android.text.Layout
import io.legado.app.model.localBook.epubcore.EpubRegex
import io.legado.app.model.localBook.epubcore.archive.EpubPath
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.util.Locale

object EpubDirectDocumentBuilder {

    fun build(
        chapterIndex: Int,
        href: String,
        title: String,
        sourceHtml: String,
        config: EpubCoreLayoutConfig,
        density: Float,
        startFragmentId: String? = null,
        endFragmentId: String? = null,
        layoutMode: EpubDirectLayoutMode = EpubDirectLayoutMode.REFLOWABLE,
        publisherViewportWidth: Float? = null,
        publisherViewportHeight: Float? = null,
        publisherOrientation: String = "auto",
        publisherSpread: String = "auto",
        publisherFullscreen: Boolean = false,
        fullPageArtwork: Boolean = false,
        implicitSinglePage: Boolean = false,
        duokanGallery: Boolean = false,
        scripted: Boolean = false,
        publisherPageBackground: Boolean = false,
        resourceHost: String = EpubDirectSession.HOST,
        pageProgressionDirection: String? = null
    ): EpubDirectChapter {
        return build(
            chapterIndex = chapterIndex,
            href = href,
            title = title,
            parsedSource = EpubDirectParsedSource(sourceHtml),
            config = config,
            density = density,
            startFragmentId = startFragmentId,
            endFragmentId = endFragmentId,
            layoutMode = layoutMode,
            publisherViewportWidth = publisherViewportWidth,
            publisherViewportHeight = publisherViewportHeight,
            publisherOrientation = publisherOrientation,
            publisherSpread = publisherSpread,
            publisherFullscreen = publisherFullscreen,
            fullPageArtwork = fullPageArtwork,
            implicitSinglePage = implicitSinglePage,
            duokanGallery = duokanGallery,
            scripted = scripted,
            publisherPageBackground = publisherPageBackground,
            resourceHost = resourceHost,
            pageProgressionDirection = pageProgressionDirection
        )
    }

    internal fun build(
        chapterIndex: Int,
        href: String,
        title: String,
        parsedSource: EpubDirectParsedSource,
        config: EpubCoreLayoutConfig,
        density: Float,
        startFragmentId: String? = null,
        endFragmentId: String? = null,
        layoutMode: EpubDirectLayoutMode = EpubDirectLayoutMode.REFLOWABLE,
        publisherViewportWidth: Float? = null,
        publisherViewportHeight: Float? = null,
        publisherOrientation: String = "auto",
        publisherSpread: String = "auto",
        publisherFullscreen: Boolean = false,
        fullPageArtwork: Boolean = false,
        implicitSinglePage: Boolean = false,
        duokanGallery: Boolean = false,
        scripted: Boolean = false,
        publisherPageBackground: Boolean = false,
        resourceHost: String = EpubDirectSession.HOST,
        pageProgressionDirection: String? = null
    ): EpubDirectChapter {
        val sourceDocument = parsedSource.document()
        val writingMode = sourceDocument?.let(::resolveRootWritingMode)
        val rootDirection = sourceDocument?.let(::resolveRootDirection)
        val viewport = if (
            publisherViewportWidth != null && publisherViewportHeight != null &&
            publisherViewportWidth > 0f && publisherViewportHeight > 0f
        ) {
            publisherViewportWidth to publisherViewportHeight
        } else {
            sourceDocument?.let(::parseViewport)
        }
        // A numeric viewport is common in reflowable EPUB 2 content too. The package and
        // spine metadata are the authoritative fixed-layout signal.
        val effectiveLayoutMode = layoutMode
        val prepared = prepareDocument(
            sourceHtml = parsedSource.sourceHtml,
            chapterHref = href,
            css = readerCss(
                config = config,
                density = density.coerceAtLeast(1f),
                layoutMode = effectiveLayoutMode,
                viewport = viewport,
                fullPageArtwork = fullPageArtwork,
                implicitSinglePage = implicitSinglePage,
                duokanGallery = duokanGallery,
                scripted = scripted,
                readerFontUrl = readerFontResourceUrl(
                    resourceHost = resourceHost,
                    readerFontUrl = config.readerFontUrl,
                    readerFontRevision = config.readerFontRevision
                )
            ),
            resourceHost = resourceHost,
            sourceDocument = sourceDocument
        )
        return EpubDirectChapter(
            chapterIndex = chapterIndex,
            href = href,
            title = title,
            baseUrl = EpubDirectSession.baseUrl(href, resourceHost),
            html = prepared.html,
            plainText = prepared.plainText,
            startFragmentId = startFragmentId?.takeIf { it.isNotBlank() },
            endFragmentId = endFragmentId?.takeIf { it.isNotBlank() && it != startFragmentId },
            layoutMode = effectiveLayoutMode,
            viewportWidth = viewport?.first,
            viewportHeight = viewport?.second,
            publisherOrientation = publisherOrientation,
            publisherSpread = publisherSpread,
            publisherFullscreen = publisherFullscreen,
            fullPageArtwork = fullPageArtwork,
            implicitSinglePage = implicitSinglePage,
            duokanGallery = duokanGallery,
            scripted = scripted,
            pageProgressionDirection = normalizeDirection(pageProgressionDirection) ?: rootDirection,
            pageLayoutDirection = when (writingMode) {
                "vertical-rl", "sideways-rl" -> "rtl"
                "vertical-lr", "sideways-lr" -> "ltr"
                else -> rootDirection ?: normalizeDirection(pageProgressionDirection)
            },
            publisherPageBackground = publisherPageBackground
        )
    }

    internal fun prepareDocument(
        sourceHtml: String,
        chapterHref: String,
        css: String,
        resourceHost: String,
        sourceDocument: Document?
    ): PreparedDocument {
        val hasScopedXmlBase = sourceDocument?.allElements?.any { it.hasAttr("xml:base") } == true
        if (!sourceHtml.contains(CONTINUATION_MARKER, ignoreCase = true) && !hasScopedXmlBase) {
            return prepareRawDocument(
                sourceHtml = sourceHtml,
                css = css,
                plainText = extractReadableText(sourceDocument)
            )
        }
        return runCatching {
            val document = checkNotNull(sourceDocument)
            document.setBaseUri(EpubDirectSession.baseUrl(chapterHref, resourceHost))
            document.outputSettings()
                .prettyPrint(false)
                .syntax(Document.OutputSettings.Syntax.xml)

            val html = document.getElementsByTag("html").firstOrNull() ?: document.appendElement("html")
            html.attr("xmlns", XHTML_NAMESPACE)
            val head = html.directChild("head") ?: Element("head").also(html::prependChild)
            val body = html.directChild("body") ?: Element("body").also(html::appendChild)

            head.select("meta[name=viewport]").remove()
            head.select("#legado-epub-reader-style").remove()
            head.appendElement("meta")
                .attr("name", "viewport")
                .attr("content", READER_VIEWPORT)
            head.appendElement("style")
                .attr("id", "legado-epub-reader-style")
                .appendText(css)

            rewriteResourceUrls(document, chapterHref, resourceHost)
            PreparedDocument(document.outerHtml(), extractReadableText(document))
        }.getOrElse {
            prepareRawDocument(
                sourceHtml = sourceHtml,
                css = css,
                plainText = runCatching { extractReadableText(Jsoup.parse(sourceHtml)) }.getOrDefault("")
            )
        }
    }

    internal fun prepareRawDocument(
        sourceHtml: String,
        css: String,
        plainText: String = ""
    ): PreparedDocument {
        val cleaned = READER_STYLE_TAG.replace(
            VIEWPORT_META.replace(sourceHtml, ""),
            ""
        )
        val injection = buildString(css.length + 128) {
            append("<meta name=\"viewport\" content=\"")
            append(READER_VIEWPORT)
            append("\">")
            append("<style id=\"legado-epub-reader-style\">")
            append(css)
            append("</style>")
        }
        val headClose = HEAD_CLOSE.find(cleaned)
        val htmlOpen = HTML_OPEN.find(cleaned)
        val html = when {
            headClose != null -> cleaned.substring(0, headClose.range.first) +
                injection + cleaned.substring(headClose.range.first)
            htmlOpen != null -> cleaned.replaceRange(
                htmlOpen.range,
                htmlOpen.value + "<head>$injection</head>"
            )
            else -> "<!doctype html><html><head>$injection</head><body>" +
                cleaned + "</body></html>"
        }
        val extractedText = plainText.ifBlank {
            runCatching { extractReadableText(Jsoup.parse(sourceHtml)) }.getOrDefault("")
        }
        return PreparedDocument(html, extractedText)
    }

    /**
     * Keep block boundaries in the text handed to the reader/朗读 planner. Jsoup's
     * `body.text()` collapses a heading and its first paragraph into one space,
     * which makes them a single speech cue and shifts progress offsets. Nested
     * block elements are ignored once an outer block has been selected so text is
     * never duplicated.
     */
    private fun extractReadableText(document: Document?): String {
        val body = document?.body() ?: return ""
        val blockTags = setOf(
            "address", "article", "aside", "blockquote", "dd", "div", "dl", "dt",
            "figcaption", "figure", "footer", "h1", "h2", "h3", "h4", "h5", "h6",
            "header", "li", "main", "nav", "ol", "p", "pre", "section", "table", "ul"
        )
        val text = StringBuilder()
        fun separator() {
            while (text.isNotEmpty() && text.last() == ' ') text.setLength(text.length - 1)
            if (text.isNotEmpty() && text.last() != '\n') text.append('\n')
        }
        fun visit(node: Node) {
            when (node) {
                is TextNode -> {
                    val value = node.text().replace(TEXT_WHITESPACE, " ").trim()
                    if (value.isNotEmpty()) {
                        if (text.isNotEmpty() && text.last() != '\n' && text.last() != ' ') text.append(' ')
                        text.append(value)
                    }
                }
                is Element -> {
                    val tag = node.normalName()
                    if (tag in setOf("script", "style", "noscript", "template")) return
                    val block = tag in blockTags
                    if (block || tag == "br") separator()
                    node.childNodes().forEach(::visit)
                    if (block) separator()
                }
            }
        }
        body.childNodes().forEach(::visit)
        return text.toString()
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex(" +([,.;:!?%\\)\\]»、。！？；：])"), "$1")
            .trim()
    }

    private fun rewriteResourceUrls(document: Document, primaryHref: String, resourceHost: String) {
        val documentBase = document.selectFirst("head base[href]")
            ?.attr("href")
            ?.takeIf { it.isNotBlank() }
        document.allElements.forEach { element ->
            val continuation = element.parents()
                .firstOrNull { it.hasAttr("data-epub-continuation-href") }
            val scopeHref = continuation
                ?.attr("data-epub-continuation-href")
                ?.takeIf { it.isNotBlank() }
                ?: primaryHref
            val declaredBase = if (continuation != null) {
                continuation.selectFirst("base[href]")?.attr("href")
            } else {
                documentBase
            }?.takeIf { it.isNotBlank() }
            var baseHref = if (element.normalName() == "base") {
                scopeHref
            } else {
                resolveBaseHref(scopeHref, declaredBase)
            }
            val baseChain = ArrayList<Element>()
            var ancestor: Element? = element
            while (ancestor != null) {
                baseChain.add(ancestor)
                if (ancestor === continuation) break
                ancestor = ancestor.parent()
            }
            baseChain.asReversed().forEach { node ->
                node.attr("xml:base").takeIf { it.isNotBlank() }?.let {
                    baseHref = resolveBaseHref(baseHref, it)
                }
            }
            RESOURCE_ATTRIBUTES.forEach { attribute ->
                if (!element.hasAttr(attribute)) return@forEach
                val value = element.attr(attribute)
                rewriteUrl(baseHref, value, resourceHost)?.let { element.attr(attribute, it) }
            }
            if (element.normalName() == "audio") {
                DUOKAN_AUDIO_IMAGE_ATTRIBUTES.forEach { attribute ->
                    if (!element.hasAttr(attribute)) return@forEach
                    val value = element.attr(attribute)
                    rewriteUrl(baseHref, value, resourceHost)?.let { element.attr(attribute, it) }
                }
            }
            if (element.hasAttr("srcset")) {
                element.attr("srcset", rewriteSrcSet(baseHref, element.attr("srcset"), resourceHost))
            }
            if (element.hasAttr("style")) {
                element.attr(
                    "style",
                    rewriteCssUrls(
                        baseHref,
                        EpubDirectPublisherCss.normalize(element.attr("style")),
                        resourceHost
                    )
                )
            }
            SVG_URL_ATTRIBUTES.forEach { attribute ->
                if (!element.hasAttr(attribute)) return@forEach
                element.attr(
                    attribute,
                    rewriteCssUrls(baseHref, element.attr(attribute), resourceHost)
                )
            }
            if (element.normalName() == "style") {
                val source = element.data().ifBlank { element.html() }
                element.text(
                    rewriteCssUrls(
                        baseHref,
                        EpubDirectPublisherCss.normalize(source),
                        resourceHost
                    )
                )
            }
        }
    }

    private fun rewriteSrcSet(baseHref: String, srcSet: String, resourceHost: String): String {
        return rewriteSrcSet(srcSet) { rewriteUrl(baseHref, it, resourceHost) }
    }

    internal fun rewriteSrcSet(srcSet: String, rewrite: (String) -> String?): String {
        val candidates = ArrayList<String>()
        var index = 0
        while (index < srcSet.length) {
            while (index < srcSet.length && (srcSet[index].isWhitespace() || srcSet[index] == ',')) index++
            if (index >= srcSet.length) break

            val urlStart = index
            while (index < srcSet.length && !srcSet[index].isWhitespace()) index++
            var url = srcSet.substring(urlStart, index)
            val endedByComma = url.endsWith(',')
            url = url.trimEnd(',')
            if (url.isBlank()) continue

            var descriptor = ""
            if (!endedByComma) {
                while (index < srcSet.length && srcSet[index].isWhitespace()) index++
                val descriptorStart = index
                var parentheses = 0
                while (index < srcSet.length) {
                    when (srcSet[index]) {
                        '(' -> parentheses++
                        ')' -> if (parentheses > 0) parentheses--
                        ',' -> if (parentheses == 0) break
                    }
                    index++
                }
                descriptor = srcSet.substring(descriptorStart, index).trim()
            }
            while (index < srcSet.length && srcSet[index] != ',') index++
            if (index < srcSet.length) index++

            candidates += buildString {
                append(rewrite(url) ?: url)
                if (descriptor.isNotBlank()) append(' ').append(descriptor)
            }
        }
        return candidates.joinToString(", ")
    }

    internal fun rewriteCssUrls(baseHref: String, css: String, resourceHost: String): String {
        return rewriteCssUrls(css) { rewriteUrl(baseHref, it, resourceHost) }
    }

    internal fun rewriteCssUrls(css: String, rewrite: (String) -> String?): String {
        val output = StringBuilder(css.length)
        var copiedUntil = 0
        var index = 0
        while (index < css.length) {
            if (!css.regionMatches(index, "url", 0, 3, ignoreCase = true) ||
                (index > 0 && (css[index - 1].isLetterOrDigit() || css[index - 1] == '-' || css[index - 1] == '_'))
            ) {
                index++
                continue
            }
            var open = index + 3
            while (open < css.length && css[open].isWhitespace()) open++
            if (open >= css.length || css[open] != '(') {
                index++
                continue
            }
            var cursor = open + 1
            var quote: Char? = null
            var parentheses = 0
            var close = -1
            while (cursor < css.length) {
                val char = css[cursor]
                if (char == '\\') {
                    cursor += 2
                    continue
                }
                if (quote != null) {
                    if (char == quote) quote = null
                } else {
                    when (char) {
                        '\'', '"' -> quote = char
                        '(' -> parentheses++
                        ')' -> if (parentheses == 0) {
                            close = cursor
                            break
                        } else {
                            parentheses--
                        }
                    }
                }
                cursor++
            }
            if (close < 0) {
                index = open + 1
                continue
            }
            val rawValue = css.substring(open + 1, close).trim()
            val value = rawValue
                .takeIf { it.length >= 2 && it.first() == it.last() && (it.first() == '\'' || it.first() == '"') }
                ?.substring(1, rawValue.length - 1)
                ?: rawValue
            val rewritten = rewrite(decodeCssUrl(value))
            if (rewritten != null) {
                output.append(css, copiedUntil, index)
                output.append("url(\"")
                    .append(rewritten.replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\")")
                copiedUntil = close + 1
            }
            index = close + 1
        }
        val rewrittenUrls = if (copiedUntil == 0) {
            css
        } else {
            output.append(css, copiedUntil, css.length).toString()
        }
        return rewriteCssImports(rewrittenUrls, rewrite)
    }

    private fun rewriteCssImports(css: String, rewrite: (String) -> String?): String {
        val output = StringBuilder(css.length)
        var copiedUntil = 0
        var index = 0
        while (index < css.length) {
            if (!css.regionMatches(index, "@import", 0, 7, ignoreCase = true) ||
                (index + 7 < css.length && (css[index + 7].isLetterOrDigit() || css[index + 7] == '-' || css[index + 7] == '_'))
            ) {
                index++
                continue
            }
            var valueStart = index + 7
            while (valueStart < css.length && css[valueStart].isWhitespace()) valueStart++
            if (valueStart >= css.length || (css[valueStart] != '\'' && css[valueStart] != '"')) {
                index = valueStart.coerceAtMost(css.length)
                continue
            }
            val quote = css[valueStart]
            var valueEnd = valueStart + 1
            while (valueEnd < css.length) {
                if (css[valueEnd] == '\\') {
                    valueEnd += 2
                    continue
                }
                if (css[valueEnd] == quote) break
                valueEnd++
            }
            if (valueEnd >= css.length) {
                index = valueStart + 1
                continue
            }
            val value = decodeCssUrl(css.substring(valueStart + 1, valueEnd))
            val rewritten = rewrite(value)
            if (rewritten != null) {
                output.append(css, copiedUntil, valueStart + 1)
                output.append(
                    rewritten.replace("\\", "\\\\").replace(quote.toString(), "\\$quote")
                )
                copiedUntil = valueEnd
            }
            index = valueEnd + 1
        }
        if (copiedUntil == 0) return css
        output.append(css, copiedUntil, css.length)
        return output.toString()
    }

    private fun rewriteUrl(baseHref: String, value: String, resourceHost: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank() || trimmed.startsWith('#') || trimmed.startsWith("//")) return null
        if (SCHEME.containsMatchIn(trimmed)) return null
        if (isExternalBase(baseHref)) return null
        val resolved = EpubPath.resolve(baseHref, trimmed)
        val path = EpubPath.stripFragment(resolved)
        if (path.isBlank()) return null
        val fragment = EpubPath.fragment(resolved)
        val trailingSlash = EpubPath.stripDecorations(trimmed).endsWith('/')
        return EpubDirectSession.baseUrl(path.removeSuffix("/$BASE_SENTINEL"), resourceHost) +
            (if (trailingSlash) "/" else "") +
            fragment?.let { "#${EpubPath.encodeFragment(it)}" }.orEmpty()
    }

    internal fun resolveBaseHref(currentBase: String, declaredBase: String?): String {
        val declared = declaredBase?.trim().orEmpty()
        if (declared.isBlank() || declared.startsWith('#')) return currentBase
        if (isExternalBase(declared)) return declared
        if (isExternalBase(currentBase)) return currentBase
        val resolved = EpubPath.resolve(currentBase, declared)
        return if (EpubPath.stripDecorations(declared).endsWith('/')) {
            EpubPath.stripFragment(resolved).trimEnd('/') + "/$BASE_SENTINEL"
        } else {
            resolved
        }
    }

    private fun isExternalBase(value: String): Boolean {
        return value.startsWith("//") || SCHEME.containsMatchIn(value)
    }

    private fun decodeCssUrl(value: String): String {
        val output = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            if (value[index] != '\\' || index + 1 >= value.length) {
                output.append(value[index++])
                continue
            }
            index++
            if (value[index] == '\n' || value[index] == '\r') {
                if (value[index] == '\r' && index + 1 < value.length && value[index + 1] == '\n') index++
                index++
                continue
            }
            val hexStart = index
            while (index < value.length && index - hexStart < 6 && value[index].digitToIntOrNull(16) != null) index++
            if (index > hexStart) {
                value.substring(hexStart, index).toIntOrNull(16)?.let(output::appendCodePoint)
                if (index < value.length && value[index].isWhitespace()) index++
            } else {
                output.append(value[index++])
            }
        }
        return output.toString()
    }

    internal fun readerCss(
        config: EpubCoreLayoutConfig,
        density: Float,
        layoutMode: EpubDirectLayoutMode,
        viewport: Pair<Float, Float>?,
        fullPageArtwork: Boolean,
        implicitSinglePage: Boolean,
        duokanGallery: Boolean,
        scripted: Boolean = false,
        readerFontUrl: String?
    ): String {
        fun cssPx(value: Number): String = String.format(Locale.US, "%.3fpx", value.toFloat() / density)
        // Reader chrome is deliberately admitted only to ordinary horizontal
        // reflow.  Unsupported publisher-controlled modes retain their exact
        // previous padding contract even if a caller passes an invalid config.
        val chrome = EpubDirectReaderChromePolicy.resolve(
            requested = config.readerChrome,
            input = EpubDirectReaderChromePolicy.Input(
                layoutMode = layoutMode,
                fullPageArtwork = fullPageArtwork,
                implicitSinglePage = implicitSinglePage,
                duokanGallery = duokanGallery,
                scripted = scripted,
                scrollMode = config.scrollMode
            ),
            pageHeightPx = config.pageHeightPx
        )
        val readerContentPaddingTopPx = config.readerPaddingTopPx + chrome.reservedHeaderHeightPx
        val readerContentPaddingBottomPx = config.readerPaddingBottomPx + chrome.reservedFooterHeightPx
        val safeInsetsEnabled = layoutMode == EpubDirectLayoutMode.REFLOWABLE
        val readerContentPaddingLeftPx = config.readerPaddingLeftPx +
            if (safeInsetsEnabled) config.readerSafeInsetLeftPx else 0
        val readerContentPaddingRightPx = config.readerPaddingRightPx +
            if (safeInsetsEnabled) config.readerSafeInsetRightPx else 0
        val readerContentPaddingTopWithSafeInsetPx = readerContentPaddingTopPx +
            if (safeInsetsEnabled) config.readerSafeInsetTopPx else 0
        val readerContentPaddingBottomWithSafeInsetPx = readerContentPaddingBottomPx +
            if (safeInsetsEnabled) config.readerSafeInsetBottomPx else 0
        val foreground = color(config.textPaint.color)
        val background = color(config.backgroundColor)
        val selectionCss = "::selection{background-color:${color(config.selectionColor)}!important;}"
        val fontSize = cssPx(config.textPaint.textSize)
        val lineHeight = cssPx(config.lineHeightPx.coerceAtLeast(0f))
        val contentWidth = cssPx(
            (config.pageWidthPx - readerContentPaddingLeftPx - readerContentPaddingRightPx)
                .coerceAtLeast(1)
        )
        val contentHeight = cssPx(
            (config.pageHeightPx - readerContentPaddingTopWithSafeInsetPx - readerContentPaddingBottomWithSafeInsetPx)
                .coerceAtLeast(1)
        )
        val paragraphIndent = "text-indent:${cssPx(config.paragraphIndentPx.coerceAtLeast(0f))}!important;"
        val paragraphPagination = if (config.scrollMode) {
            ""
        } else {
            "orphans:1!important;widows:1!important;break-inside:auto!important;page-break-inside:auto!important;"
        }
        val fontStyle = if (config.textFontItalic) "italic" else "normal"
        val alignment = when (config.alignment) {
            Layout.Alignment.ALIGN_CENTER -> "center"
            Layout.Alignment.ALIGN_OPPOSITE -> "end"
            else -> if (config.textFullJustify) "justify" else "start"
        }
        val readerFontRules = readerFontRules(
            config.readerFontFamily,
            config.readerFontOverridePublisher
        )
        val fontFace = readerFontUrl?.takeIf { it.isNotBlank() }?.let {
            "@font-face{font-family:'legado-reader-font';src:url('${it.replace("'", "%27")}');font-display:block;}"
        }.orEmpty()
        val overlayCss = """
            #legado-epub-image-overlay{position:fixed!important;inset:0!important;z-index:2147483646!important;box-sizing:border-box!important;display:none!important;align-items:center!important;justify-content:center!important;width:100vw!important;height:100vh!important;margin:0!important;padding:16px!important;background:rgba(0,0,0,.96)!important;touch-action:none!important;}
            #legado-epub-image-overlay.legado-visible{display:flex!important;}
            #legado-epub-image-overlay>img{display:block!important;width:auto!important;height:auto!important;max-width:calc(100vw - 32px)!important;max-height:calc(100vh - 32px)!important;object-fit:contain!important;transform-origin:center center!important;user-select:none!important;-webkit-user-drag:none!important;}
        """.trimIndent()
        if (layoutMode == EpubDirectLayoutMode.MEDIA) {
            return """
                $fontFace
                :root{color-scheme:light dark;--legado-fg:$foreground;--legado-bg:$background;}
                ${readerFontRules.inherited}
                html,body{box-sizing:border-box!important;margin:0!important;width:100vw!important;height:100vh!important;overflow:hidden!important;}
                html{padding:0!important;overscroll-behavior:none;}
                body{padding:${cssPx(config.readerPaddingTopPx)} ${cssPx(config.readerPaddingRightPx)} ${cssPx(config.readerPaddingBottomPx)} ${cssPx(config.readerPaddingLeftPx)}!important;display:flex!important;flex-direction:column!important;align-items:center!important;justify-content:center!important;color:$foreground!important;font-size:$fontSize;line-height:$lineHeight;}
                body>h1,body>h2,body>h3,body>h4,body>h5,body>h6{flex:0 0 auto!important;margin:0 0 1em!important;}
                body>img,body>svg,body>video,body>audio,body>canvas{display:block!important;flex:0 1 auto!important;width:auto!important;height:auto!important;max-width:100%!important;max-height:calc(100% - 4em)!important;object-fit:contain!important;}
                body>video,body>audio{width:100%!important;}
                $selectionCss
                $overlayCss
            """.trimIndent()
        }
        if (layoutMode == EpubDirectLayoutMode.PUBLISHER_STYLED) {
            val pageWidth = cssPx(config.pageWidthPx)
            val pageHeight = cssPx(config.pageHeightPx)
            val bodyFlowCss = publisherStyledBodyFlowCss(pageWidth, pageHeight)
            return """
                $fontFace
                ${readerFontRules.publisherOverride}
                ${readerFontRules.inherited}
                html{box-sizing:border-box!important;margin:0!important;padding:0!important;width:$pageWidth!important;height:$pageHeight!important;overflow:hidden!important;overscroll-behavior:none;}
                body{box-sizing:border-box!important;margin:0!important;$bodyFlowCss}
                img,svg,video,canvas{max-width:100%;height:auto;}
                $selectionCss
                $overlayCss
            """.trimIndent()
        }
        if (layoutMode == EpubDirectLayoutMode.INTERACTIVE && duokanGallery) {
            return """
                $fontFace
                ${readerFontRules.publisherOverride}
                ${readerFontRules.inherited}
                :root{color-scheme:dark;--legado-bg:#000;}
                html,body{box-sizing:border-box!important;margin:0!important;padding:0!important;width:100vw!important;height:100vh!important;overflow:hidden!important;background:#000!important;color:#fff;overscroll-behavior:none;}
                body>:not(.duokan-image-gallery):not(#legado-epub-image-overlay){display:none!important;}
                .duokan-image-gallery{position:absolute!important;inset:0!important;box-sizing:border-box!important;display:flex!important;width:100%!important;height:100%!important;max-width:none!important;margin:0!important;padding:0!important;overflow-x:auto!important;overflow-y:hidden!important;gap:0!important;scroll-snap-type:x mandatory;scroll-behavior:smooth;overscroll-behavior-x:contain;touch-action:pan-x;-webkit-overflow-scrolling:touch;scrollbar-width:none;}
                .duokan-image-gallery::-webkit-scrollbar{display:none;}
                .duokan-image-gallery-cell{box-sizing:border-box!important;display:flex!important;flex:0 0 100%!important;flex-direction:column!important;align-items:center!important;justify-content:center!important;width:100%!important;height:100%!important;max-width:none!important;margin:0!important;padding:20px 0!important;border:0!important;box-shadow:none!important;overflow:hidden!important;scroll-snap-align:center;scroll-snap-stop:always;}
                .duokan-image-gallery-cell img{display:block!important;flex:0 1 auto!important;width:auto!important;height:auto!important;max-width:100%!important;max-height:72%!important;margin:auto 0 0!important;object-fit:contain!important;cursor:zoom-in;user-select:none!important;-webkit-user-drag:none!important;}
                .duokan-image-maintitle,.duokan-image-subtitle{box-sizing:border-box!important;flex:0 0 auto!important;width:100%!important;max-width:100%!important;padding:0 14px!important;color:#fff!important;text-align:center!important;text-indent:0!important;}
                .duokan-image-maintitle{margin:auto 0 0!important;font-size:1em!important;line-height:1.35!important;}
                .duokan-image-subtitle{margin:.65em 0 0!important;font-size:.78em!important;line-height:1.35!important;opacity:.82;}
                $overlayCss
            """.trimIndent()
        }
        if (layoutMode == EpubDirectLayoutMode.INTERACTIVE) {
            return """
                $fontFace
                ${readerFontRules.publisherOverride}
                ${readerFontRules.inherited}
                :root{color-scheme:light dark;--legado-bg:$background;}
                html{box-sizing:border-box!important;margin:0!important;padding:0!important;width:100vw!important;height:100vh!important;overflow:hidden!important;overscroll-behavior:none;}
                *,*::before,*::after{box-sizing:inherit;}
                body{box-sizing:border-box!important;min-width:100%!important;min-height:100%!important;margin:0!important;overflow:auto!important;-webkit-overflow-scrolling:touch;}
                iframe,object,embed{max-width:100%;max-height:100%;}
                $selectionCss
                $overlayCss
            """.trimIndent()
        }
        val common = """
            $fontFace
            ${readerFontRules.publisherOverride}
            :root{color-scheme:light dark;--legado-fg:$foreground;--legado-bg:$background;}
            ${readerFontRules.inherited}
            html{box-sizing:border-box;margin:0!important;padding:0!important;overscroll-behavior:none;}
            body{--legado-line-height-base:$lineHeight;--legado-highlight-room-left:${cssPx(readerContentPaddingLeftPx)};--legado-highlight-room-right:${cssPx(readerContentPaddingRightPx)};box-sizing:border-box;color:$foreground!important;font-size:$fontSize;line-height:$lineHeight;font-weight:${config.textFontWeight};font-style:$fontStyle;letter-spacing:${config.textPaint.letterSpacing}em;text-align:$alignment;overflow-wrap:break-word;-webkit-text-size-adjust:none;}
            :where(p,li,blockquote,div){overflow-wrap:break-word;}
            :where(p){margin-top:0;margin-bottom:${cssPx(config.paragraphSpacingPx)}}
            p[data-legado-reader-paragraph]{line-height:var(--legado-line-grid,var(--legado-line-height-base))!important;$paragraphIndent$paragraphPagination}
            p[data-legado-page-gap]{margin-top:var(--legado-page-gap)!important;}
            body[data-legado-text-reader] p.reader-paragraph{$paragraphIndent}
            :where(img:not(.emoji):not(.emoji1),video,canvas,object){max-width:100%;height:auto;}
            :where(svg){max-width:100%;}
            :where(table){max-width:100%;border-collapse:collapse;}
            :where(th,td){max-width:$contentWidth;overflow-wrap:anywhere;}
            :where(pre,code){white-space:pre-wrap;overflow-wrap:anywhere;}
            :where(iframe,object,embed){max-width:100%;max-height:$contentHeight;}
            :where(img:not(.emoji):not(.emoji1),svg,video,canvas){max-height:$contentHeight;object-fit:contain;break-inside:avoid;page-break-inside:avoid;}
            :where(figure,picture){max-width:100%;height:auto;break-inside:avoid;page-break-inside:avoid;}
            :where(figure){margin-left:auto;margin-right:auto;}
            .duokan-image-gallery{display:flex!important;max-width:100%!important;overflow-x:auto!important;overflow-y:hidden!important;gap:0!important;scroll-snap-type:x mandatory;scroll-behavior:smooth;overscroll-behavior-x:contain;touch-action:pan-x;-webkit-overflow-scrolling:touch;}
            .duokan-image-gallery-cell{box-sizing:border-box;flex:0 0 100%!important;width:100%!important;max-width:100%!important;margin-left:0!important;margin-right:0!important;scroll-snap-align:center;scroll-snap-stop:always;break-inside:avoid;page-break-inside:avoid;}
            .duokan-image-gallery-cell img{cursor:zoom-in;}
            ruby{ruby-position:over;}
            $selectionCss
            $overlayCss
        """.trimIndent()
        if (layoutMode.scalesPublisherViewport) {
            val sourceWidth = viewport?.first?.takeIf { it > 0f } ?: config.pageWidthPx / density
            val sourceHeight = viewport?.second?.takeIf { it > 0f } ?: config.pageHeightPx / density
            val artworkCss = if (fullPageArtwork) {
                "body>:only-child,body>:only-child>:only-child{box-sizing:border-box!important;width:100%!important;height:100%!important;margin:0!important;}body img,body svg,body video,body canvas{display:block!important;width:100%!important;height:100%!important;max-width:100%!important;max-height:100%!important;object-fit:contain!important;}"
            } else {
                ""
            }
            val centeredCanvasCss = if (implicitSinglePage) {
                "body{display:flex!important;flex-direction:column!important;align-items:center!important;justify-content:center!important;}body>:only-child{max-width:100%!important;margin:0 auto!important;transform-origin:50% 50%!important;}"
            } else {
                ""
            }
            return """
                $fontFace
                ${readerFontRules.publisherOverride}
                ${readerFontRules.inherited}
                :root{color-scheme:light dark;--legado-bg:$background;}
                html{width:100vw!important;height:100vh!important;overflow:hidden!important;}
                html{box-sizing:border-box;margin:0!important;padding:0!important;overscroll-behavior:none;}
                body{box-sizing:border-box;position:absolute!important;left:50%!important;top:50%!important;width:${sourceWidth}px!important;height:${sourceHeight}px!important;margin:${-sourceHeight / 2f}px 0 0 ${-sourceWidth / 2f}px!important;padding:0!important;overflow:hidden!important;transform:scale(var(--legado-fixed-scale,1));transform-origin:50% 50%;}
                $artworkCss
                $centeredCanvasCss
                $selectionCss
                $overlayCss
            """.trimIndent()
        }
        if (layoutMode.singlePage) {
            return common + """
                html{width:100%!important;height:100%!important;overflow:hidden!important;}
                body{width:100%!important;min-height:100%!important;height:100%!important;margin:0!important;padding:${cssPx(readerContentPaddingTopWithSafeInsetPx)} ${cssPx(readerContentPaddingRightPx)} ${cssPx(readerContentPaddingBottomWithSafeInsetPx)} ${cssPx(readerContentPaddingLeftPx)}!important;overflow:auto!important;}
                img,svg,video,canvas,object{max-width:100%!important;max-height:100%!important;object-fit:contain;}
            """.trimIndent()
        }
        if (config.scrollMode) {
            return common + """
                html,body{width:100%!important;min-height:100%!important;overflow-x:hidden!important;}
                body{margin:0!important;padding:${cssPx(readerContentPaddingTopWithSafeInsetPx)} ${cssPx(readerContentPaddingRightPx)} ${cssPx(readerContentPaddingBottomWithSafeInsetPx)} ${cssPx(readerContentPaddingLeftPx)}!important;}
            """.trimIndent()
        }
        val horizontalGap = cssPx(readerContentPaddingLeftPx + readerContentPaddingRightPx)
        return common + """
            html{width:100vw!important;height:100vh!important;overflow:hidden!important;}
            body{width:100vw!important;height:100vh!important;margin:0!important;padding:${cssPx(readerContentPaddingTopWithSafeInsetPx)} ${cssPx(readerContentPaddingRightPx)} ${cssPx(readerContentPaddingBottomWithSafeInsetPx)} ${cssPx(readerContentPaddingLeftPx)}!important;overflow:visible!important;column-width:$contentWidth!important;column-gap:$horizontalGap!important;column-fill:auto!important;transform-origin:left top!important;}
        """.trimIndent()
    }

    private fun parseViewport(document: Document): Pair<Float, Float>? {
        val content = document.selectFirst("meta[name=viewport]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() } ?: return null
        val width = VIEWPORT_WIDTH.find(content)?.groupValues?.getOrNull(1)?.toFloatOrNull()
        val height = VIEWPORT_HEIGHT.find(content)?.groupValues?.getOrNull(1)?.toFloatOrNull()
        return if (width != null && height != null && width > 0f && height > 0f) width to height else null
    }

    internal fun publisherStyledBodyFlowCss(pageWidth: String, pageHeight: String): String {
        return "width:$pageWidth!important;height:$pageHeight!important;overflow:visible!important;" +
            "-webkit-column-width:$pageWidth;column-width:$pageWidth;" +
            "-webkit-column-gap:0;column-gap:0;" +
            "-webkit-column-fill:auto;column-fill:auto;"
    }

    internal fun readerFontRules(
        readerFontFamily: String?,
        overridePublisher: Boolean
    ): ReaderFontRules {
        val escapedFamily = readerFontFamily
            ?.takeIf { it.isNotBlank() }
            ?.replace("\\", "\\\\")
            ?.replace("'", "\\'")
            ?.let { "'$it'" }
            ?: return ReaderFontRules("", "")
        return ReaderFontRules(
            inherited = ":where(html){font-family:$escapedFamily,sans-serif;}",
            publisherOverride = if (overridePublisher) {
                "body,body *{font-family:$escapedFamily,sans-serif!important;}"
            } else {
                ""
            }
        )
    }

    internal fun readerFontResourceUrl(
        resourceHost: String,
        readerFontUrl: String?,
        readerFontRevision: String?
    ): String? {
        if (readerFontUrl.isNullOrBlank() || readerFontRevision.isNullOrBlank()) return null
        val baseUrl = EpubDirectSession.baseUrl(READER_FONT_PATH, resourceHost)
        return "$baseUrl?v=$readerFontRevision"
    }

    internal data class ReaderFontRules(
        val inherited: String,
        val publisherOverride: String
    )

    internal fun resolveRootWritingMode(html: String): String? {
        val document = parseDocument(html) ?: return null
        return resolveRootWritingMode(document)
    }

    private fun resolveRootWritingMode(document: Document): String? {
        return resolveRootProperty(document, WRITING_MODE) { normalizeWritingMode(it) }
    }

    internal fun resolvePageLayoutDirection(
        html: String,
        packageDirection: String?,
        writingMode: String? = resolveRootWritingMode(html)
    ): String? {
        return when (writingMode) {
            "vertical-rl", "sideways-rl" -> "rtl"
            "vertical-lr", "sideways-lr" -> "ltr"
            else -> resolveRootDirection(html) ?: normalizeDirection(packageDirection)
        }
    }

    private fun resolveRootDirection(html: String): String? {
        val document = parseDocument(html) ?: return null
        return resolveRootDirection(document)
    }

    private fun resolveRootDirection(document: Document): String? {
        resolveRootProperty(document, DIRECTION) { normalizeDirection(it) }?.let { return it }
        listOfNotNull(document.body(), document.selectFirst("html")).forEach { element ->
            normalizeDirection(element.attr("dir"))?.let { return it }
        }
        return null
    }

    private fun parseDocument(html: String): Document? {
        return runCatching { Jsoup.parse(html, "", Parser.xmlParser()) }.getOrNull()
    }

    private fun <T> resolveRootProperty(
        document: Document,
        property: Regex,
        normalize: (String) -> T?
    ): T? {
        val html = document.selectFirst("html")
        val body = document.body()
        var htmlValue: T? = null
        var bodyValue: T? = null
        document.select("style").forEach { style ->
            CSS_RULE.findAll(style.data().ifBlank { style.html() }).forEach ruleLoop@{ rule ->
                val declaration = rule.groupValues[2]
                val value = property.findAll(declaration).lastOrNull()
                    ?.groupValues?.getOrNull(1)
                    ?.let(normalize)
                    ?: return@ruleLoop
                rule.groupValues[1].split(',').forEach selectorLoop@{ rawSelector ->
                    val selector = rawSelector.trim().takeIf { it.isNotEmpty() } ?: return@selectorLoop
                    if (body != null && runCatching { body.`is`(selector) }.getOrDefault(false)) bodyValue = value
                    if (html != null && runCatching { html.`is`(selector) }.getOrDefault(false)) htmlValue = value
                }
            }
        }
        body.attr("style").let { declaration ->
            property.findAll(declaration).lastOrNull()?.groupValues?.getOrNull(1)?.let(normalize)?.let {
                bodyValue = it
            }
        }
        html?.attr("style")?.let { declaration ->
            property.findAll(declaration).lastOrNull()?.groupValues?.getOrNull(1)?.let(normalize)?.let {
                htmlValue = it
            }
        }
        return bodyValue ?: htmlValue
    }

    private fun normalizeDirection(value: String?): String? {
        return value?.trim()?.lowercase(Locale.ROOT)?.takeIf { it == "ltr" || it == "rtl" }
    }

    private fun normalizeWritingMode(value: String): String? {
        return when (value.trim().lowercase(Locale.ROOT)) {
            "vertical-rl", "tb-rl" -> "vertical-rl"
            "vertical-lr", "tb-lr" -> "vertical-lr"
            "sideways-rl" -> "sideways-rl"
            "sideways-lr" -> "sideways-lr"
            "horizontal-tb", "lr-tb", "rl-tb" -> "horizontal-tb"
            else -> null
        }
    }

    private fun Element.directChild(name: String): Element? {
        return children().firstOrNull { it.normalName().equals(name, true) }
    }

    private fun color(value: Int): String = String.format(
        Locale.US,
        "rgba(%d,%d,%d,%.3f)",
        (value ushr 16) and 0xff,
        (value ushr 8) and 0xff,
        value and 0xff,
        ((value ushr 24) and 0xff) / 255f
    )

    internal data class PreparedDocument(val html: String, val plainText: String)

    private val RESOURCE_ATTRIBUTES = listOf("src", "href", "poster", "data", "background", "xlink:href")
    private val DUOKAN_AUDIO_IMAGE_ATTRIBUTES = listOf("placeholder", "activestate")
    private val TEXT_WHITESPACE = EpubRegex.compile("\\s+")
    private val SVG_URL_ATTRIBUTES = listOf(
        "clip-path",
        "cursor",
        "fill",
        "filter",
        "marker",
        "marker-start",
        "marker-mid",
        "marker-end",
        "mask",
        "stroke"
    )
    private val SCHEME = EpubRegex.compile("^[A-Za-z][A-Za-z0-9+.-]*:")
    private val VIEWPORT_WIDTH = EpubRegex.compile(
        "(?:^|[,;\\s])width\\s*=\\s*([0-9.]+)",
        RegexOption.IGNORE_CASE
    )
    private val VIEWPORT_HEIGHT = EpubRegex.compile(
        "(?:^|[,;\\s])height\\s*=\\s*([0-9.]+)",
        RegexOption.IGNORE_CASE
    )
    private val CSS_RULE = EpubRegex.compile(
        "([^{}]+)\\{([^{}]*)}",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val DIRECTION = EpubRegex.compile(
        "(?:^|[;\\s])direction\\s*:\\s*(ltr|rtl)",
        RegexOption.IGNORE_CASE
    )
    private val WRITING_MODE = EpubRegex.compile(
        "(?:^|[;\\s])(?:-(?:webkit|epub)-)?writing-mode\\s*:\\s*([a-z-]+)",
        RegexOption.IGNORE_CASE
    )
    private val VIEWPORT_META = EpubRegex.compile(
        """<meta\b(?=[^>]*\bname\s*=\s*(?:["']viewport["']|viewport\b))[^>]*?/?>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val READER_STYLE_TAG = EpubRegex.compile(
        """<style\b(?=[^>]*\bid\s*=\s*(?:["']legado-epub-reader-style["']|legado-epub-reader-style\b))[^>]*>.*?</style\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val HEAD_CLOSE = EpubRegex.compile("</head\\s*>", RegexOption.IGNORE_CASE)
    private val HTML_OPEN = EpubRegex.compile("<html\\b[^>]*>", RegexOption.IGNORE_CASE)
    private const val CONTINUATION_MARKER = "data-epub-continuation-href"
    private const val READER_VIEWPORT =
        "width=device-width,initial-scale=1,minimum-scale=1,maximum-scale=1,user-scalable=no"
    private const val READER_FONT_PATH = "__legado_reader_font__"
    internal const val BASE_SENTINEL = "__legado_base__"
    internal const val XHTML_NAMESPACE = "http://www.w3.org/1999/xhtml"
}
