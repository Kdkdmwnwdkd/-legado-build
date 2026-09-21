package io.legado.app.model.localBook.epubcore.pkg

import io.legado.app.constant.AppLog
import io.legado.app.model.localBook.epubcore.EpubRegex
import io.legado.app.model.localBook.epubcore.archive.EpubArchive
import io.legado.app.model.localBook.epubcore.archive.EpubPath
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

class EpubPackageParser {

    fun parse(archive: EpubArchive): EpubPackage {
        val opfPath = findOpfPath(archive)
        val opf = XmlTools.parse(archive.readBytes(opfPath, MAX_PACKAGE_BYTES))
        val metadataElement = opf.elements("metadata").firstOrNull()
        val packageRendition = parsePackageRendition(metadataElement)

        val manifest = LinkedHashMap<String, EpubManifestItem>()
        opf.elements("manifest").firstOrNull()
            ?.children("item")
            ?.forEach { item ->
                val id = item.attr("id") ?: return@forEach
                val rawHref = item.attr("href") ?: return@forEach
                val href = canonicalReference(
                    archive,
                    resolveElementReference(opfPath, item, rawHref)
                )
                if (manifest.containsKey(id)) {
                    AppLog.putDebug("EPUB manifest duplicate id ignored: id=$id href=$href opf=$opfPath")
                    return@forEach
                }
                val properties = propertyTokens(item.attr("properties"))
                manifest[id] = EpubManifestItem(
                    id = id,
                    href = href,
                    mediaType = item.attr("media-type").orEmpty(),
                    properties = properties
                )
            }

        val spineElement = opf.elements("spine").firstOrNull()
        val spine = spineElement
            ?.children("itemref")
            ?.mapIndexedNotNull { index, itemref ->
                val idRef = itemref.attr("idref") ?: return@mapIndexedNotNull null
                val item = manifest[idRef] ?: return@mapIndexedNotNull null
                val properties = propertyTokens(itemref.attr("properties"))
                EpubSpineItem(
                    index = index,
                    idRef = idRef,
                    href = item.href,
                    linear = !itemref.attr("linear").equals("no", true),
                    properties = properties,
                    rendition = mergeRendition(packageRendition, properties)
                )
            }
            .orEmpty()

        return EpubPackage(
            opfPath = opfPath,
            metadata = EpubMetadata(
                title = metadataElement?.firstText("title"),
                creator = metadataElement?.firstText("creator"),
                language = metadataElement?.firstText("language"),
                identifier = publicationIdentifier(opf, metadataElement)
            ),
            manifest = manifest,
            spine = spine,
            navHref = manifest.values.firstOrNull { "nav" in it.properties }?.href,
            ncxHref = spineElement?.attr("toc")?.let { manifest[it]?.href },
            coverHref = findCoverHref(opf, manifest, opfPath, archive),
            rendition = packageRendition,
            pageProgressionDirection = spineElement?.attr("page-progression-direction")
                ?.trim()?.lowercase()?.takeIf { it in PAGE_DIRECTIONS }
        )
    }

    private fun parsePackageRendition(metadata: org.w3c.dom.Element?): EpubRendition {
        val meta = metadata?.elements("meta").orEmpty()
        fun property(name: String): String? = meta
            .firstOrNull { it.attr("property").equals(name, true) && it.attr("refines").isNullOrBlank() }
            ?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        val viewport = property("rendition:viewport")?.let(::parseViewport)
            ?: meta.firstOrNull { it.attr("name").equals("original-resolution", true) }
                ?.attr("content")?.let(::parseViewport)
        return EpubRendition(
            layout = property("rendition:layout")?.lowercase()?.takeIf { it in LAYOUT_VALUES },
            orientation = property("rendition:orientation")?.lowercase()
                ?.takeIf { it in ORIENTATION_VALUES } ?: "auto",
            spread = property("rendition:spread")?.lowercase()
                ?.takeIf { it in SPREAD_VALUES } ?: "auto",
            viewportWidth = viewport?.first,
            viewportHeight = viewport?.second
        )
    }

    private fun mergeRendition(packageRendition: EpubRendition, properties: Set<String>): EpubRendition {
        fun override(prefix: String): String? = properties.firstNotNullOfOrNull { property ->
            property.removePrefix(prefix).takeIf { property.startsWith(prefix) }
        }
        val layout = override("rendition:layout-")?.takeIf { it in LAYOUT_VALUES }
        val orientation = override("rendition:orientation-")?.takeIf { it in ORIENTATION_VALUES }
        val spread = override("rendition:spread-")?.takeIf { it in SPREAD_VALUES }
        return packageRendition.copy(
            layout = layout ?: packageRendition.layout,
            orientation = orientation ?: packageRendition.orientation,
            spread = spread ?: packageRendition.spread
        )
    }

    private fun propertyTokens(value: String?): Set<String> {
        return value.orEmpty()
            .split(WHITESPACE)
            .mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }
            .toSet()
    }

    private fun parseViewport(value: String): Pair<Float, Float>? {
        val width = VIEWPORT_WIDTH.find(value)?.groupValues?.getOrNull(1)?.toFloatOrNull()
        val height = VIEWPORT_HEIGHT.find(value)?.groupValues?.getOrNull(1)?.toFloatOrNull()
        if (width != null && height != null && width > 0f && height > 0f) return width to height
        val dimensions = VIEWPORT_DIMENSIONS.find(value) ?: return null
        val dimensionWidth = dimensions.groupValues[1].toFloatOrNull()
        val dimensionHeight = dimensions.groupValues[2].toFloatOrNull()
        return if (dimensionWidth != null && dimensionHeight != null && dimensionWidth > 0f && dimensionHeight > 0f) {
            dimensionWidth to dimensionHeight
        } else {
            null
        }
    }

    private fun findOpfPath(archive: EpubArchive): String {
        if (archive.exists("META-INF/container.xml")) {
            val doc = XmlTools.parse(
                archive.readBytes("META-INF/container.xml", MAX_CONTAINER_BYTES)
            )
            val fullPath = doc.elements("rootfile").firstOrNull()?.attr("full-path")
            if (!fullPath.isNullOrBlank()) {
                archive.canonicalPath(fullPath)?.let { return it }
            }
        }
        return archive.list().firstOrNull { it.endsWith(".opf", ignoreCase = true) }
            ?: error("EPUB package document not found")
    }

    private fun findCoverHref(
        opf: Document,
        manifest: Map<String, EpubManifestItem>,
        opfPath: String,
        archive: EpubArchive
    ): String? {
        fun manifestItem(value: String?): EpubManifestItem? {
            val id = value
                ?.trim()
                ?.removePrefix("#")
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            return manifest.entries.firstOrNull { (key, _) -> key.equals(id, true) }?.value
        }

        fun existingCandidate(rawValue: String?, basePath: String = opfPath): String? {
            val value = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            manifestItem(value)?.href?.let { href ->
                if (archive.exists(EpubPath.stripFragment(href))) return href
            }
            if (value.startsWith("#")) return null
            // Values coming from a manifest item are already canonical archive
            // paths. Check them before resolving relative to the OPF; otherwise
            // `OEBPS/Images/cover.jpg` would incorrectly become
            // `OEBPS/OEBPS/Images/cover.jpg`.
            archive.canonicalPath(EpubPath.stripFragment(value))?.let { canonical ->
                return EpubPath.fragment(value)?.let { "$canonical#$it" } ?: canonical
            }
            val resolved = canonicalReference(archive, EpubPath.resolve(basePath, value))
            if (resolved.startsWith("//") || SCHEME.containsMatchIn(resolved)) return null
            return resolved.takeIf { archive.exists(EpubPath.stripFragment(it)) }
        }

        fun declaredManifestCover(): String? {
            return manifest.values
                .asSequence()
                .filter { "cover-image" in it.properties }
                .mapNotNull { existingCandidate(it.href) }
                .firstOrNull()
        }

        // EPUB 3 normally marks the manifest item itself. Some producers put
        // the declaration in metadata, optionally refining a manifest id.
        declaredManifestCover()?.let { return it }
        opf.elements("meta")
            .filter { it.attr("property").equals("cover-image", true) }
            .forEach { meta ->
                val refinedItem = manifestItem(meta.attr("refines"))
                val value = meta.textContent.trim()
                    .takeIf { it.isNotEmpty() }
                    ?: meta.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
                val candidate = refinedItem?.href?.let { existingCandidate(it) }
                    ?: existingCandidate(value)
                if (candidate != null) return candidate
            }

        // EPUB 2 uses <meta name="cover" content="manifest-id">. A number
        // of files incorrectly put a relative resource path in content, so
        // accept both forms and resolve it relative to the package document.
        opf.elements("meta")
            .firstOrNull { it.attr("name").equals("cover", true) }
            ?.attr("content")
            ?.let { existingCandidate(it) }
            ?.let { return it }

        // EPUB 2 commonly points to a cover XHTML document through guide.
        // Keep this behind explicit declarations because guide may contain a
        // presentation-only title page unrelated to the actual artwork.
        opf.elements("reference")
            .firstOrNull { it.attr("type").equals("cover", true) }
            ?.attr("href")
            ?.let { existingCandidate(it) }
            ?.let { return it }

        // A number of EPUB 2 packages omit both the legacy meta entry and the
        // EPUB 3 cover-image property.  Limit the fallback to image resources
        // whose id or path explicitly carries a cover/title-page hint; never
        // guess from the first image in the manifest.
        return manifest.values.firstOrNull { item ->
            val isImage = item.mediaType.substringBefore(';').trim().startsWith("image/", true)
            isImage && (COVER_HINT.containsMatchIn(item.id) || COVER_HINT.containsMatchIn(item.href))
        }?.let { existingCandidate(it.href) } ?: manifest.values.firstOrNull { item ->
            val mediaType = item.mediaType.substringBefore(';').trim()
            val isCoverDocument = mediaType.equals("application/xhtml+xml", true) ||
                mediaType.equals("text/html", true) ||
                mediaType.equals("image/svg+xml", true)
            isCoverDocument && (COVER_HINT.containsMatchIn(item.id) || COVER_HINT.containsMatchIn(item.href))
        }?.let { existingCandidate(it.href) }
    }

    private fun publicationIdentifier(opf: Document, metadata: Element?): String? {
        val identifiers = metadata?.elements("identifier").orEmpty()
        val uniqueIdentifierId = opf.documentElement
            ?.attr("unique-identifier")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return uniqueIdentifierId
            ?.let { id -> identifiers.firstOrNull { it.attr("id") == id } }
            ?.textContent
            ?.trimXmlWhitespace()
            ?.takeIf { it.isNotEmpty() }
            ?: identifiers.firstOrNull()
                ?.textContent
                ?.trimXmlWhitespace()
                ?.takeIf { it.isNotEmpty() }
    }

    private fun String.trimXmlWhitespace(): String {
        return trim { it == ' ' || it == '\t' || it == '\r' || it == '\n' }
    }

    private fun resolveElementReference(documentPath: String, element: Element, href: String): String {
        var base = documentPath
        val ancestors = ArrayList<Element>()
        var current: Node? = element
        while (current != null) {
            if (current is Element) ancestors += current
            current = current.parentNode
        }
        ancestors.asReversed().forEach { ancestor ->
            ancestor.xmlBase()?.let { declaredBase ->
                base = resolveDirectoryBase(base, declaredBase)
            }
        }
        return EpubPath.resolve(base, href)
    }

    private fun Element.xmlBase(): String? {
        return getAttributeNS(XML_NAMESPACE, "base")
            .takeIf { it.isNotBlank() }
            ?: attr("xml:base")?.takeIf { it.isNotBlank() }
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

    companion object {
        private val WHITESPACE = EpubRegex.compile("\\s+")
        private val LAYOUT_VALUES = setOf("reflowable", "pre-paginated")
        private val ORIENTATION_VALUES = setOf("auto", "landscape", "portrait")
        private val SPREAD_VALUES = setOf("auto", "none", "landscape", "portrait", "both")
        private val PAGE_DIRECTIONS = setOf("ltr", "rtl")
        private val VIEWPORT_WIDTH = EpubRegex.compile(
            "(?:^|[,;\\s])width\\s*=\\s*([0-9.]+)",
            RegexOption.IGNORE_CASE
        )
        private val VIEWPORT_HEIGHT = EpubRegex.compile(
            "(?:^|[,;\\s])height\\s*=\\s*([0-9.]+)",
            RegexOption.IGNORE_CASE
        )
        private val VIEWPORT_DIMENSIONS = EpubRegex.compile("([0-9.]+)\\s*[xX]\\s*([0-9.]+)")
        private val SCHEME = EpubRegex.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:")
        private val COVER_HINT = EpubRegex.compile(
            "(?:^|[/_.~\\-])(cover|frontcover|titlepage|title-page)(?:$|[/_.~\\-])",
            RegexOption.IGNORE_CASE
        )
        private const val XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace"
        private const val MAX_CONTAINER_BYTES = 1L * 1024L * 1024L
        private const val MAX_PACKAGE_BYTES = 8L * 1024L * 1024L
    }
}
