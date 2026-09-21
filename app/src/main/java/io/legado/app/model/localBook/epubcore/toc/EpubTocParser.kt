package io.legado.app.model.localBook.epubcore.toc

import io.legado.app.model.localBook.epubcore.EpubRegex
import io.legado.app.model.localBook.epubcore.archive.EpubArchive
import io.legado.app.model.localBook.epubcore.archive.EpubPath
import io.legado.app.model.localBook.epubcore.pkg.EpubPackage
import io.legado.app.model.localBook.epubcore.pkg.XmlTools
import io.legado.app.model.localBook.epubcore.pkg.attr
import io.legado.app.model.localBook.epubcore.pkg.children
import io.legado.app.model.localBook.epubcore.pkg.elements
import org.jsoup.Jsoup
import org.w3c.dom.Element
import org.w3c.dom.Node

class EpubTocParser {

    fun parse(archive: EpubArchive, pkg: EpubPackage): List<TocItem> {
        pkg.navHref?.let { href ->
            parseNav(archive, href).takeIf { it.isNotEmpty() }?.let { return it }
        }
        pkg.ncxHref?.let { href ->
            parseNcx(archive, href).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return pkg.spine.map { TocItem("Chapter ${it.index + 1}", it.href) }
    }

    private fun parseNav(archive: EpubArchive, navHref: String): List<TocItem> {
        val doc = Jsoup.parse(
            archive.readBytes(navHref, MAX_TOC_BYTES).toString(Charsets.UTF_8)
        )
        val nav = doc.select("nav").firstOrNull {
            val type = it.attr("epub:type").ifBlank { it.attr("type") }
            type == "toc" || type.split(WHITESPACE).contains("toc")
        } ?: doc.selectFirst("nav")
        val rootOl = nav?.children()?.firstOrNull { it.normalName() == "ol" } ?: return emptyList()
        val documentBase = doc.selectFirst("head base[href]")
            ?.attr("href")
            ?.takeIf { it.isNotBlank() }
            ?.let { resolveDirectoryBase(navHref, it) }
            ?: navHref
        return parseHtmlNavList(archive, documentBase, rootOl)
    }

    private fun parseHtmlNavList(
        archive: EpubArchive,
        documentBase: String,
        ol: org.jsoup.nodes.Element
    ): List<TocItem> {
        return ol.children().filter { it.normalName() == "li" }.mapNotNull { li ->
            val anchor = li.children().firstOrNull { it.normalName() == "a" || it.normalName() == "span" }
            val title = anchor?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val hrefRaw = anchor.attr("href")
            val href = if (hrefRaw.isBlank()) {
                ""
            } else {
                canonicalReference(
                    archive,
                    EpubPath.resolve(htmlElementBase(documentBase, anchor), hrefRaw)
                )
            }
            TocItem(
                title = title,
                href = href,
                fragment = EpubPath.decodedFragment(hrefRaw),
                children = li.children().firstOrNull { it.normalName() == "ol" }
                    ?.let { parseHtmlNavList(archive, documentBase, it) }
                    .orEmpty()
            )
        }
    }

    private fun parseNcx(archive: EpubArchive, ncxHref: String): List<TocItem> {
        val doc = XmlTools.parse(archive.readBytes(ncxHref, MAX_TOC_BYTES))
        return doc.elements("navMap").firstOrNull()
            ?.children("navPoint")
            ?.mapNotNull { parseNavPoint(archive, ncxHref, it) }
            .orEmpty()
    }

    private fun parseNavPoint(archive: EpubArchive, documentBase: String, navPoint: Element): TocItem? {
        val title = navPoint.elements("text").firstOrNull()
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val content = navPoint.elements("content").firstOrNull()
        val src = content?.attr("src").orEmpty()
        val href = if (src.isBlank()) {
            ""
        } else {
            canonicalReference(
                archive,
                EpubPath.resolve(content?.let { xmlElementBase(documentBase, it) } ?: documentBase, src)
            )
        }
        return TocItem(
            title = title,
            href = href,
            fragment = EpubPath.decodedFragment(src),
            children = navPoint.children("navPoint").mapNotNull {
                parseNavPoint(archive, documentBase, it)
            }
        )
    }

    private fun htmlElementBase(documentBase: String, element: org.jsoup.nodes.Element): String {
        var base = documentBase
        val chain = element.parents().toList().asReversed() + element
        chain.forEach { node ->
            node.attr("xml:base").takeIf { it.isNotBlank() }?.let { xmlBase ->
                base = resolveDirectoryBase(base, xmlBase)
            }
        }
        return base
    }

    private fun xmlElementBase(documentBase: String, element: Element): String {
        var base = documentBase
        val ancestors = ArrayList<Element>()
        var current: Node? = element
        while (current != null) {
            if (current is Element) ancestors += current
            current = current.parentNode
        }
        ancestors.asReversed().forEach { ancestor ->
            val xmlBase = ancestor.getAttributeNS(XML_NAMESPACE, "base")
                .takeIf { it.isNotBlank() }
                ?: ancestor.attr("xml:base")?.takeIf { it.isNotBlank() }
            xmlBase?.let { base = resolveDirectoryBase(base, it) }
        }
        return base
    }

    private fun resolveDirectoryBase(currentBase: String, declaredBase: String): String {
        val resolved = EpubPath.resolve(currentBase, declaredBase)
        return if (EpubPath.stripDecorations(declaredBase).replace('\\', '/').endsWith('/')) {
            EpubPath.stripFragment(resolved).trimEnd('/') + "/"
        } else {
            resolved
        }
    }

    private fun canonicalReference(archive: EpubArchive, href: String): String {
        if (href.startsWith("//") || SCHEME.containsMatchIn(href)) return href
        val path = EpubPath.stripFragment(href)
        val canonical = archive.canonicalPath(path) ?: path
        return EpubPath.fragment(href)?.let { "$canonical#$it" } ?: canonical
    }

    private companion object {
        val WHITESPACE = EpubRegex.compile("\\s+")
        val SCHEME = EpubRegex.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:")
        const val XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace"
        const val MAX_TOC_BYTES = 8L * 1024L * 1024L
    }
}
