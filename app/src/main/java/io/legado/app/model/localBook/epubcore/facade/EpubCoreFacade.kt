package io.legado.app.model.localBook.epubcore.facade

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookChapter
import io.legado.app.model.localBook.epubcore.EpubRegex
import io.legado.app.model.localBook.epubcore.archive.EpubArchive
import io.legado.app.model.localBook.epubcore.archive.EpubArchiveLeaseGate
import io.legado.app.model.localBook.epubcore.archive.EpubPath
import io.legado.app.model.localBook.epubcore.archive.AndroidZipEpubArchive
import io.legado.app.model.localBook.epubcore.archive.ZipEpubArchive
import io.legado.app.model.localBook.epubcore.cache.EpubCoreDiskCache
import io.legado.app.model.localBook.epubcore.font.EpubFontDeobfuscatingArchive
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import io.legado.app.model.localBook.epubcore.direct.EpubDirectChapter
import io.legado.app.model.localBook.epubcore.direct.EpubDirectContentClassifier
import io.legado.app.model.localBook.epubcore.direct.EpubDirectDocumentBuilder
import io.legado.app.model.localBook.epubcore.direct.EpubDirectDiskResourceCache
import io.legado.app.model.localBook.epubcore.direct.EpubDirectFragmentBoundary
import io.legado.app.model.localBook.epubcore.direct.EpubDirectFragmentIndex
import io.legado.app.model.localBook.epubcore.direct.EpubDirectResource
import io.legado.app.model.localBook.epubcore.direct.EpubDirectResourceCache
import io.legado.app.model.localBook.epubcore.direct.EpubDirectResourceFactory
import io.legado.app.model.localBook.epubcore.direct.EpubDirectRangePolicy
import io.legado.app.model.localBook.epubcore.direct.EpubDirectSourceCacheKey
import io.legado.app.model.localBook.epubcore.direct.EpubDirectLinkTarget
import io.legado.app.model.localBook.epubcore.direct.EpubDirectMediaDocument
import io.legado.app.model.localBook.epubcore.direct.EpubDirectParsedSource
import io.legado.app.model.localBook.epubcore.direct.EpubDirectPublisherCss
import io.legado.app.model.localBook.epubcore.direct.EpubDirectSession
import io.legado.app.model.localBook.epubcore.direct.EpubDirectTextCache
import io.legado.app.model.localBook.epubcore.pkg.EpubPackage
import io.legado.app.model.localBook.epubcore.pkg.EpubPackageParser
import io.legado.app.model.localBook.epubcore.toc.EpubTocParser
import io.legado.app.model.localBook.epubcore.toc.TocItem
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.decodeBase64DataUrlBytes
import org.jsoup.Jsoup
import splitties.init.appCtx
import java.io.Closeable
import java.io.File
import java.util.LinkedHashSet
import java.util.zip.ZipException
import android.net.Uri

class EpubCoreFacade private constructor(
    private val archive: EpubArchiveLeaseGate,
    private val bookUrl: String,
    private val bookSignature: String,
    private val bookCacheDir: File,
    private val pkg: EpubPackage,
    private val toc: List<TocItem>
) : Closeable {

    private val directResourceCache = EpubDirectResourceCache()
    private val directDiskResourceCache = EpubDirectDiskResourceCache(
        File(
            bookCacheDir,
            "epub_core/direct/${MD5Utils.md5Encode16(bookSignature)}"
        )
    )
    private val chapters: List<BookChapter> by lazy { buildChapters() }
    private val directSpineByHref by lazy {
        pkg.spine.groupBy { EpubPath.stripFragment(it.href) }
    }
    private val directManifestByHref by lazy {
        pkg.manifest.values.groupBy { EpubPath.stripFragment(it.href) }
    }
    private val directChaptersByHref by lazy {
        val result = LinkedHashMap<String, MutableList<BookChapter>>()
        chapters.filterNot { it.url.startsWith("skip:") }.forEach { chapter ->
            (listOf(chapter.url) + continuationHrefs(chapter)).forEach { href ->
                val path = EpubPath.stripFragment(href)
                if (path.isNotBlank()) result.getOrPut(path) { arrayListOf() }.add(chapter)
            }
        }
        result.mapValues { (_, candidates) -> candidates.distinctBy(BookChapter::index) }
    }
    private val directResourceHost = EpubDirectSession.HOST
    private val directSourceCache = EpubDirectTextCache(
        maxEntries = DIRECT_SOURCE_CACHE_ENTRIES,
        maxEntryChars = DIRECT_SOURCE_CACHE_MAX_CHARS,
        maxTotalChars = DIRECT_SOURCE_CACHE_TOTAL_CHARS
    )
    private val directFragmentIndexes = object : LinkedHashMap<String, EpubDirectFragmentIndex>(
        DIRECT_FRAGMENT_INDEX_ENTRIES,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, EpubDirectFragmentIndex>?
        ): Boolean = size > DIRECT_FRAGMENT_INDEX_ENTRIES
    }

    fun chapters(): List<BookChapter> = chapters

    fun resolveReadableChapterIndex(index: Int, preferredDirection: Int = 1): Int? {
        return EpubReadableChapterPolicy.resolve(chapters, index, preferredDirection)
    }

    fun adjacentReadableChapterIndex(index: Int, direction: Int): Int? {
        return EpubReadableChapterPolicy.adjacent(chapters, index, direction)
    }

    fun adjacentLogicalChapterIndex(index: Int, direction: Int): Int? {
        return EpubReadableChapterPolicy.adjacentLogical(chapters, index, direction)
    }

    fun directResourceHost(): String = directResourceHost

    /**
     * Resolve the package-declared cover through the Direct archive.  Keeping
     * this lookup here makes the cover and reader use the same path/case and
     * duplicate-entry handling.  A legacy guide may point at an XHTML/SVG
     * cover document, so follow explicit image references in that document.
     */
    fun coverResource(): EpubCoreCoverResource? {
        val candidates = LinkedHashSet<String>()
        fun addCandidate(raw: String?) {
            val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return
            if (value.startsWith("data:", true)) {
                candidates += value
                return
            }
            if (value.startsWith("//") || COVER_SCHEME.containsMatchIn(value)) return
            val path = canonicalArchivePath(value)
            if (path.isNotBlank()) candidates += path
        }

        addCandidate(pkg.coverHref)
        pkg.manifest.values
            .filter { "cover-image" in it.properties }
            .forEach { addCandidate(it.href) }
        // Keep a conservative fallback for packages that omit all formal
        // metadata. Only resources carrying an unambiguous cover hint qualify.
        pkg.manifest.values
            .filter { COVER_HINT.containsMatchIn(it.id) || COVER_HINT.containsMatchIn(it.href) }
            .forEach { addCandidate(it.href) }

        candidates.forEach { candidate ->
            coverResourceAt(candidate, linkedSetOf(), depth = 0)?.let { return it }
        }
        return null
    }

    private fun coverResourceAt(
        path: String,
        visited: MutableSet<String>,
        depth: Int
    ): EpubCoreCoverResource? {
        if (depth > COVER_REFERENCE_DEPTH || !visited.add(path)) return null
        if (path.startsWith("data:", true)) {
            val bytes = path.decodeBase64DataUrlBytes(MAX_COVER_RESOURCE_BYTES) ?: return null
            return EpubCoreCoverResource(
                path = path,
                mediaType = path.substring(5)
                    .substringBefore(',')
                    .substringBefore(';')
                    .trim()
                    .takeIf { it.isNotEmpty() },
                bytes = bytes
            )
        }
        if (path.startsWith("//") || COVER_SCHEME.containsMatchIn(path)) return null
        val canonicalPath = canonicalArchivePath(path)
        if (canonicalPath.isBlank()) return null
        val manifestItem = directManifestByHref[canonicalPath]?.firstOrNull()
        val mediaType = manifestItem?.mediaType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
        val extension = canonicalPath.substringAfterLast('.', "").lowercase()
        val isSvg = mediaType.equals("image/svg+xml", true) || extension == "svg" || extension == "svgz"
        val isImage = mediaType?.startsWith("image/") == true || extension in COVER_IMAGE_EXTENSIONS
        val bytes = readCoverBytes(canonicalPath, if (isImage) MAX_COVER_RESOURCE_BYTES else MAX_COVER_DOCUMENT_BYTES)
            ?: return null
        val resource = EpubCoreCoverResource(canonicalPath, mediaType, bytes)

        // Raster images are already terminal resources. SVG and XHTML cover
        // documents may wrap the actual artwork in a local image reference.
        // Follow those references first so external SVG links do not render as
        // a blank bitmap on the bookshelf.
        if (isSvg || !isImage || looksLikeSvg(bytes)) {
            val source = bytes.toString(Charsets.UTF_8)
            extractCoverReferences(source).forEach { raw ->
                val resolved = resolveCoverReference(canonicalPath, raw) ?: return@forEach
                coverResourceAt(resolved, visited, depth + 1)?.let { return it }
            }
        }
        return resource.takeIf { isImage || looksLikeImage(bytes) }
    }

    private fun readCoverBytes(path: String, maxBytes: Long): ByteArray? {
        val size = archive.entrySize(path)
        if (size != null && size >= 0L && size > maxBytes) return null
        return runCatching { archive.readBytes(path, maxBytes) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun resolveCoverReference(basePath: String, rawReference: String): String? {
        val value = rawReference.trim().trim('"', '\'')
        if (value.isBlank() || value.startsWith("//") || COVER_SCHEME.containsMatchIn(value)) {
            return value.takeIf { it.startsWith("data:", true) }
        }
        return if (value.startsWith("data:", true)) {
            value
        } else {
            canonicalArchivePath(EpubPath.resolve(basePath, value))
        }
    }

    private fun extractCoverReferences(source: String): List<String> {
        val references = LinkedHashSet<String>()
        fun add(value: String?) {
            value?.trim()?.takeIf { it.isNotEmpty() }?.let { references += it }
        }
        fun addSrcSet(value: String?) {
            value.orEmpty().split(',').forEach { candidate ->
                add(candidate.trim().split(Regex("\\s+"), limit = 2).firstOrNull())
            }
        }
        fun addElementAttributes(element: org.jsoup.nodes.Element) {
            add(element.attr("src"))
            add(element.attr("href"))
            add(element.attr("xlink:href"))
            add(element.attr("data"))
            addSrcSet(element.attr("srcset"))
        }

        val document = runCatching { Jsoup.parse(source) }.getOrNull()
        if (document != null) {
            listOf("img", "image", "use", "source", "object").forEach { tag ->
                document.getElementsByTag(tag).forEach(::addElementAttributes)
            }
            document.select("link[href]").forEach { link ->
                val rel = link.attr("rel").lowercase()
                if (rel.isBlank() || rel.split(Regex("\\s+")).contains("stylesheet")) {
                    add(link.attr("href"))
                }
            }
            document.select("[style]").forEach { element ->
                addCssReferences(element.attr("style"), ::add)
            }
            document.getElementsByTag("style").forEach { style ->
                addCssReferences(style.data().ifBlank { style.text() }, ::add)
            }
        }
        // This also covers a standalone CSS resource and malformed markup
        // where Jsoup cannot expose the style element reliably.
        addCssReferences(source, ::add)
        return references.toList()
    }

    private fun addCssReferences(source: String, add: (String?) -> Unit) {
        COVER_CSS_URL.findAll(source).forEach { match ->
            add(match.groups[1]?.value ?: match.groups[2]?.value ?: match.groups[3]?.value)
        }
    }

    private fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        return (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) ||
            (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) ||
            (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()) ||
            (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()) ||
            bytes.toString(Charsets.UTF_8).trimStart().startsWith("<svg", true)
    }

    private fun looksLikeSvg(bytes: ByteArray): Boolean {
        return bytes.toString(Charsets.UTF_8).trimStart().let {
            it.startsWith("<?xml", true) && it.contains("<svg", true) ||
                it.startsWith("<svg", true)
        }
    }

    fun prepareDirectChapter(
        chapterIndex: Int,
        config: EpubCoreLayoutConfig
    ): EpubDirectChapter {
        val chapter = chapters.getOrNull(chapterIndex) ?: error("Chapter index out of range: $chapterIndex")
        return prepareDirectChapter(chapter, config)
    }

    fun prepareDirectChapter(
        chapter: BookChapter,
        config: EpubCoreLayoutConfig
    ): EpubDirectChapter {
        val canonical = canonicalChapter(chapter)
            ?: error("Chapter cannot be resolved: index=${chapter.index}, url=${chapter.url}")
        val resolved = resolveChapter(canonical)
        val href = canonicalArchivePath(resolved.url)
        val spineItem = directSpineByHref[href]?.firstOrNull()
        val manifestItem = pkg.manifest[spineItem?.idRef]
        val requestedHref = canonicalArchivePath(chapter.url)
        val requestedFragment = EpubChapterIdentityPolicy.fragment(chapter)
        // Resolve the logical window before parsing/classifying the document. A
        // shared XHTML resource can contain hundreds of TOC fragments; feeding
        // the complete resource to WebView for every fragment is both wasteful
        // and a common cause of slow opens and unstable pagination.
        val startFragmentId = EpubDirectChapterStartFragmentPolicy.resolve(
            requestedUrl = chapter.url,
            resolvedUrl = resolved.url,
            requestedHref = requestedHref,
            resolvedHref = href,
            requestedFragment = requestedFragment,
            normalizedStartFragmentId = resolved.startFragmentId
        )
        val endFragmentId = resolved.endFragmentId
        val sourceHtml = preparedDirectChapterHtml(
            chapter = resolved,
            href = href,
            mediaType = manifestItem?.mediaType,
            startFragmentId = startFragmentId,
            endFragmentId = endFragmentId
        )
        val parsedSource = EpubDirectParsedSource(sourceHtml)
        val publisherCss = parsedSource.document()?.let { document ->
            EpubDirectPublisherCss.collectForClassification(
                document = document,
                chapterHref = href,
                resourceHost = directResourceHost,
                load = ::loadDirectStylesheet
            )
        }.orEmpty()
        val rendition = spineItem?.rendition ?: pkg.rendition
        val profile = EpubDirectContentClassifier.analyze(
            renditionLayout = rendition.layout,
            spineProperties = spineItem?.properties.orEmpty(),
            manifestProperties = manifestItem?.properties.orEmpty(),
            mediaType = manifestItem?.mediaType,
            parsedSource = parsedSource,
            packageViewportWidth = rendition.viewportWidth,
            packageViewportHeight = rendition.viewportHeight,
            publisherCss = publisherCss
        )
        return EpubDirectDocumentBuilder.build(
            // DirectChapter.chapterIndex is navigation identity, not the internal content
            // owner selected while resolving a shared XHTML/fragment window.
            chapterIndex = chapter.index,
            href = href,
            title = chapter.title.ifBlank { resolved.title },
            parsedSource = parsedSource,
            config = config,
            density = appCtx.resources.displayMetrics.density,
            startFragmentId = startFragmentId,
            endFragmentId = resolved.endFragmentId,
            layoutMode = profile.layoutMode,
            publisherViewportWidth = profile.viewportWidth,
            publisherViewportHeight = profile.viewportHeight,
            publisherOrientation = rendition.orientation,
            publisherSpread = rendition.spread,
            publisherFullscreen = spineItem?.properties.orEmpty().any {
                it.lowercase() in DIRECT_FULLSCREEN_PROPERTIES
            },
            fullPageArtwork = profile.fullPageArtwork,
            implicitSinglePage = profile.implicitSinglePage,
            duokanGallery = profile.duokanGallery,
            scripted = profile.scripted,
            publisherPageBackground = profile.publisherPageBackground,
            resourceHost = directResourceHost,
            pageProgressionDirection = pkg.pageProgressionDirection
        )
    }

    private fun readDirectChapterHtml(chapter: BookChapter, href: String, mediaType: String?): String {
        return EpubDirectMediaDocument.read(href, chapter.title, mediaType) {
            readChapterHtml(chapter, href)
        }
    }

    private fun preparedDirectChapterHtml(
        chapter: BookChapter,
        href: String,
        mediaType: String?,
        startFragmentId: String? = null,
        endFragmentId: String? = null
    ): String {
        val continuationHrefs = continuationHrefs(chapter)
        val key = EpubDirectSourceCacheKey.create(
            href = href,
            mediaType = mediaType,
            title = chapter.title,
            continuationHrefs = continuationHrefs
        )
        val prepared = directSourceCache[key] ?: run {
            val source = readDirectChapterHtml(chapter, href, mediaType)
            val inline = if (continuationHrefs.isEmpty()) {
                source
            } else {
                EpubDirectPublisherCss.inline(
                    sourceHtml = source,
                    chapterHref = href,
                    resourceHost = directResourceHost,
                    load = ::loadDirectStylesheet
                )
            }
            directSourceCache.put(key, inline)
            inline
        }
        return EpubDirectFragmentWindowPolicy.apply(
            sourceHtml = prepared,
            startFragmentId = startFragmentId,
            endFragmentId = endFragmentId
        ).html
    }

    private fun loadDirectStylesheet(path: String, maxBytes: Long): ByteArray? {
        val canonicalPath = canonicalArchivePath(path)
        val size = archive.entrySize(canonicalPath)
        if (size == null || size < 0L || size > maxBytes) return null
        return runCatching {
            directResourceCache.getOrLoad(canonicalPath, size) {
                archive.readBytes(canonicalPath, maxBytes)
            }
        }.getOrNull()
    }

    fun openDirectResource(path: String, rangeHeader: String?): EpubDirectResource? {
        return openDirectResource(path, rangeHeader, withBody = true)
    }

    fun openDirectResourceHead(path: String, rangeHeader: String?): EpubDirectResource? {
        return openDirectResource(path, rangeHeader, withBody = false)
    }

    private fun openDirectResource(
        path: String,
        rangeHeader: String?,
        withBody: Boolean
    ): EpubDirectResource? {
        val cleanPath = canonicalArchivePath(path)
        val declaredType = directManifestByHref[cleanPath]?.firstOrNull()?.mediaType
        val size = archive.entrySize(cleanPath)
        val cachedBytes = if (withBody) {
            directResourceCache.getOrLoad(cleanPath, size) {
                archive.readBytes(cleanPath, DIRECT_RESOURCE_CACHE_ENTRY_BYTES)
            }
        } else {
            null
        }
        val cachedFile = if (withBody && cachedBytes == null && !rangeHeader.isNullOrBlank()) {
            directDiskResourceCache.get(cleanPath, size).also { file ->
                if (file == null && EpubDirectRangePolicy.shouldPrepareDiskCache(rangeHeader, size)) {
                    directDiskResourceCache.prepare(cleanPath, size) {
                        archive.openStream(cleanPath)
                    }
                }
            }
        } else {
            null
        }
        return EpubDirectResourceFactory.open(
            archive = archive,
            path = cleanPath,
            declaredMimeType = declaredType,
            rangeHeader = rangeHeader,
            cachedBytes = cachedBytes,
            cachedFile = cachedFile,
            openBody = withBody
        )
    }

    fun resolveDirectLink(url: String, currentChapterIndex: Int): EpubDirectLinkTarget? {
        return resolveDirectLink(url, currentChapterIndex, chapters)
    }

    fun resolveDirectLink(
        url: String,
        currentChapterIndex: Int,
        navigationChapters: List<BookChapter>
    ): EpubDirectLinkTarget? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) || !uri.host.equals(directResourceHost, true)) return null
        val path = canonicalArchivePath(
            EpubPath.normalize(Uri.decode(uri.encodedPath.orEmpty().removePrefix("/")))
        )
        if (path.isBlank()) return null
        val fragment = uri.encodedFragment
            ?.let(Uri::decode)
            ?.takeIf { it.isNotBlank() }
        val persistedCandidates = chapterCandidatesByHref(navigationChapters, path)
        val candidates = persistedCandidates.ifEmpty { directChaptersByHref[path].orEmpty() }
        if (candidates.isEmpty()) return null
        val ownerIndex = if (fragment != null && candidates.size > 1) {
            val boundaries = candidates.map {
                EpubDirectFragmentBoundary(it.index, it.startFragmentId, it.endFragmentId)
            }
            fragmentIndex(path).owner(fragment, boundaries, currentChapterIndex)
        } else {
            null
        }
        val target = EpubChapterIdentityPolicy.select(
            candidates = candidates,
            fragmentId = fragment,
            requestedIndex = currentChapterIndex,
            ownerIndex = ownerIndex
        ) ?: return null
        return EpubDirectLinkTarget(target.index, fragment)
    }

    private fun chapterCandidatesByHref(
        source: List<BookChapter>,
        href: String
    ): List<BookChapter> {
        if (source.isEmpty()) return emptyList()
        return source.filterNot { it.url.startsWith("skip:") }.filter { chapter ->
            (listOf(chapter.url) + continuationHrefs(chapter)).any {
                canonicalArchivePath(it) == href
            }
        }
    }

    private fun fragmentIndex(path: String): EpubDirectFragmentIndex {
        synchronized(directFragmentIndexes) {
            directFragmentIndexes[path]?.let { return it }
        }
        val source = runCatching {
            archive.readBytes(path, DIRECT_FRAGMENT_INDEX_MAX_BYTES).toString(Charsets.UTF_8)
        }.getOrElse {
            AppLog.putDebug("EPUB direct fragment index failed: path=$path", it)
            ""
        }
        val parsed = EpubDirectFragmentIndex.parse(source)
        return synchronized(directFragmentIndexes) {
            directFragmentIndexes[path] ?: parsed.also { directFragmentIndexes[path] = it }
        }
    }

    override fun close() {
        directSourceCache.clear()
        synchronized(directFragmentIndexes) { directFragmentIndexes.clear() }
        directResourceCache.clear()
        directDiskResourceCache.closeWhenDrained {
            archive.closeWhenDrained { error ->
                if (error != null) {
                    AppLog.putDebug("EPUB archive close failed: ${error.localizedMessage}", error)
                }
            }
        }
    }

    private fun buildChapters(): List<BookChapter> {
        val flatToc = flattenToc(toc)
        val navHref = pkg.navHref?.let { EpubPath.stripFragment(it) }
        val ncxHref = pkg.ncxHref?.let { EpubPath.stripFragment(it) }
        val coverHref = pkg.coverHref?.let { EpubPath.stripFragment(it) }
        fun isReadableHref(href: String): Boolean {
            val clean = EpubPath.stripFragment(href)
            if (clean.isBlank()) return false
            if (clean == navHref || clean == ncxHref) return false
            // The spine is the publication's authoritative reading order. Keep
            // cover/title-page documents (and the rare image spine item) instead
            // of treating the cover resource as navigation metadata.
            val fileName = clean.substringAfterLast('/').lowercase()
            if (fileName in setOf("nav.xhtml", "toc.xhtml", "toc.html", "toc.htm")) {
                return false
            }
            return true
        }
        val readableSpine = pkg.spine.filter { it.linear && isReadableHref(it.href) }
        val spineOrder = readableSpine
            .mapIndexed { order, spineItem -> EpubPath.stripFragment(spineItem.href) to order }
            .toMap()
        data class TocChapterEntry(
            val spineOrder: Int,
            val tocOrder: Int,
            val cleanHref: String?,
            val chapter: BookChapter
        )
        val tocEntries = arrayListOf<TocChapterEntry>()
        var tocOrder = 0
        fun visitToc(item: TocItem): Int? {
            val order = tocOrder++
            val childFirstOrder = item.children.mapNotNull(::visitToc).minOrNull()
            val href = item.href.trim()
            val cleanHref = EpubPath.stripFragment(href)
            val hrefOrder = if (href.isNotBlank() && isReadableHref(href)) {
                spineOrder[cleanHref]
            } else {
                null
            }
            val placement = hrefOrder ?: childFirstOrder
            if (placement != null && item.title.isNotBlank()) {
                val chapter = if (hrefOrder != null) {
                    BookChapter(
                        url = href,
                        title = item.title,
                        isVolume = item.children.isNotEmpty(),
                        baseUrl = pkg.opfPath,
                        bookUrl = bookUrl,
                        startFragmentId = item.fragment ?: EpubPath.decodedFragment(href)
                    )
                } else {
                    BookChapter(
                        url = "skip:$order:${item.title}",
                        title = item.title,
                        isVolume = true,
                        baseUrl = pkg.opfPath,
                        bookUrl = bookUrl
                    )
                }
                tocEntries += TocChapterEntry(
                    spineOrder = placement,
                    tocOrder = order,
                    cleanHref = hrefOrder?.let { cleanHref },
                    chapter = chapter
                )
            }
            return placement
        }
        toc.forEach(::visitToc)
        val entriesBySpineOrder = tocEntries
            .sortedWith(compareBy<TocChapterEntry> { it.spineOrder }.thenBy { it.tocOrder })
            .groupBy { it.spineOrder }
        val logicalOwners = tocEntries
            .asSequence()
            .filter { it.cleanHref != null && !it.chapter.url.startsWith("skip:") }
            .map { entry ->
                EpubSpineOwnershipPolicy.Owner(
                    spineOrder = entry.spineOrder,
                    tocOrder = entry.tocOrder,
                    url = entry.chapter.url,
                    title = entry.chapter.title
                )
            }
            .distinctBy { "${it.spineOrder}|${it.url}" }
            .toList()
        val chapters = if (readableSpine.isNotEmpty()) {
            val result = arrayListOf<BookChapter>()
            val addedUrls = hashSetOf<String>()
            fun addChapter(chapter: BookChapter) {
                if (!addedUrls.add(chapter.url)) return
                result += chapter
            }
            readableSpine.forEachIndexed { order, spineItem ->
                val cleanHref = EpubPath.stripFragment(spineItem.href)
                val entries = entriesBySpineOrder[order].orEmpty()
                entries.forEach { addChapter(it.chapter) }
                val hasSpineContent = entries.any { it.cleanHref == cleanHref }
                if (!hasSpineContent) {
                    // The spine is the only authoritative reading order.  A missing TOC
                    // entry does not prove that this XHTML is a continuation of the previous
                    // chapter; merging it would silently swallow a real chapter and make the
                    // next boundary unreachable.  Keep every physical linear resource as a
                    // separate readable chapter.  Explicit same-document TOC fragments are
                    // normalized later and remain a single logical document window.
                    val physical = BookChapter(
                        url = spineItem.href,
                        title = "Chapter ${spineItem.index + 1}",
                        baseUrl = pkg.opfPath,
                        bookUrl = bookUrl
                    )
                    val owner = EpubSpineOwnershipPolicy.ownerAt(order, logicalOwners)
                    addChapter(
                        EpubChapterMetadata.markPhysicalContinuation(
                            chapter = physical,
                            ownerUrl = owner?.url,
                            ownerTitle = owner?.title
                        )
                    )
                }
            }
            result
        } else {
            flatToc.mapNotNull { item ->
                when {
                    item.href.isNotBlank() && isReadableHref(item.href) -> BookChapter(
                        url = item.href,
                        title = item.title.ifBlank { "Chapter" },
                        isVolume = item.children.isNotEmpty(),
                        baseUrl = pkg.opfPath,
                        bookUrl = bookUrl,
                        startFragmentId = item.fragment ?: EpubPath.decodedFragment(item.href)
                    )
                    item.title.isNotBlank() -> BookChapter(
                        url = "skip:0:${item.title}",
                        title = item.title,
                        isVolume = true,
                        baseUrl = pkg.opfPath,
                        bookUrl = bookUrl
                    )
                    else -> null
                }
            }
        }
        return normalizeChapters(chapters).also {
            // Diagnostics must never make a valid EPUB unopenable when the host
            // application context is still being initialized.
            runCatching {
                AppLog.putDebug(
                    "EPUB core chapters built: count=${it.size}, " +
                            "nav=$navHref ncx=$ncxHref cover=$coverHref"
                )
            }
        }
    }

    private fun normalizeChapters(chapters: List<BookChapter>): List<BookChapter> {
        val nextRawReadable = arrayOfNulls<BookChapter>(chapters.size)
        var nextRaw: BookChapter? = null
        for (index in chapters.indices.reversed()) {
            nextRawReadable[index] = nextRaw
            chapters[index].takeUnless { it.url.startsWith("skip:") }?.let { nextRaw = it }
        }
        val structural = BooleanArray(chapters.size) { index ->
            val chapter = chapters[index]
            EpubTocParentHrefPolicy.shouldBecomeStructural(
                isVolume = chapter.isVolume,
                parentUrl = chapter.url,
                nextChapterUrl = nextRawReadable[index]?.url
            )
        }
        val nextReadableIndex = IntArray(chapters.size) { -1 }
        var nextEffectiveIndex = -1
        for (index in chapters.indices.reversed()) {
            nextReadableIndex[index] = nextEffectiveIndex
            if (!chapters[index].url.startsWith("skip:") && !structural[index]) {
                nextEffectiveIndex = index
            }
        }
        // A structural TOC parent can still own the opening range before its first
        // child anchor. Give that range to the first readable child instead of losing it.
        val inheritedStartFragments = mutableMapOf<Int, String?>()
        chapters.indices.forEach { index ->
            if (!structural[index]) return@forEach
            val nextIndex = nextReadableIndex[index]
            val parent = chapters[index]
            val next = chapters.getOrNull(nextIndex)
            if (nextIndex >= 0 && EpubTocParentHrefPolicy.sharesResource(parent.url, next?.url)) {
                if (nextIndex !in inheritedStartFragments) {
                    inheritedStartFragments[nextIndex] = parent.startFragmentId
                }
            }
        }
        return chapters.mapIndexed { index, chapter ->
            val nextChapter = chapters.getOrNull(nextReadableIndex[index])
            val normalizedUrl = if (structural[index]) {
                "skip:$index:${chapter.url}"
            } else {
                chapter.url
            }
            val startFragmentId = when {
                normalizedUrl.startsWith("skip:") -> null
                inheritedStartFragments.containsKey(index) -> inheritedStartFragments[index]
                else -> chapter.startFragmentId
            }
            chapter.copy(
                index = index,
                bookUrl = bookUrl,
                url = normalizedUrl,
                startFragmentId = startFragmentId,
                endFragmentId = EpubChapterFragmentRangePolicy.endFragmentId(
                    chapterUrl = normalizedUrl,
                    nextChapterUrl = nextChapter?.url,
                    nextStartFragmentId = nextChapter?.startFragmentId
                ),
                variable = GSON.toJson(
                    chapter.variableMap.toMutableMap().apply {
                        val nextUrl = nextChapter?.url
                        if (nextUrl.isNullOrBlank()) remove("nextUrl") else put("nextUrl", nextUrl)
                    }
                )
            )
        }
    }

    private fun resolveChapter(chapter: BookChapter): BookChapter {
        if (chapter.url.startsWith("skip:")) {
            chapters.firstOrNull { it.index > chapter.index && !it.url.startsWith("skip:") }?.let {
                return it
            }
        }
        return chapter
    }

    private fun canonicalChapter(requested: BookChapter): BookChapter? {
        val canUseIdentity = requested.url.isNotBlank() &&
            !requested.url.startsWith("skip:") &&
            (requested.bookUrl.isBlank() || requested.bookUrl == bookUrl)
        if (canUseIdentity) {
            val path = canonicalArchivePath(requested.url)
            val candidates = directChaptersByHref[path].orEmpty()
            if (candidates.isNotEmpty()) {
                val fragment = EpubChapterIdentityPolicy.fragment(requested)
                val ownerIndex = if (fragment != null && candidates.size > 1) {
                    val boundaries = candidates.map {
                        EpubDirectFragmentBoundary(it.index, it.startFragmentId, it.endFragmentId)
                    }
                    fragmentIndex(path).owner(fragment, boundaries, requested.index)
                } else {
                    null
                }
                EpubChapterIdentityPolicy.select(
                    candidates = candidates,
                    fragmentId = fragment,
                    requestedIndex = requested.index,
                    ownerIndex = ownerIndex
                )?.let { return it }
            }
        }
        return chapters.getOrNull(requested.index)
    }

    private fun canonicalArchivePath(path: String): String {
        val normalized = EpubPath.normalize(EpubPath.stripFragment(path))
        return archive.canonicalPath(normalized) ?: normalized
    }

    private fun readChapterHtml(chapter: BookChapter, href: String): String {
        val primaryHtml = archive.readText(href)
        val continuationHrefs = continuationHrefs(chapter)
        if (continuationHrefs.isEmpty()) return primaryHtml
        val additions = continuationHrefs.joinToString("\n") { continuationHref ->
            val cleanHref = EpubPath.stripFragment(continuationHref)
            val html = archive.readText(cleanHref)
            val head = extractHeadContent(html)
            val body = extractBodyContent(html)
            """<section data-epub-continuation-href="${escapeHtmlAttr(cleanHref)}">$head$body</section>"""
        }
        val bodyClose = EpubRegex.compile("</body\\s*>", RegexOption.IGNORE_CASE)
        val bodyCloseMatch = bodyClose.find(primaryHtml)
        return if (bodyCloseMatch != null) {
            primaryHtml.substring(0, bodyCloseMatch.range.first) +
                additions +
                primaryHtml.substring(bodyCloseMatch.range.first)
        } else {
            "$primaryHtml\n$additions"
        }
    }

    private fun continuationHrefs(chapter: BookChapter): List<String> {
        return chapter.variableMap[ContinuationHrefsKey]
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.toList()
            .orEmpty()
    }

    private fun extractBodyContent(html: String): String {
        val match = EpubRegex.compile(
            "<body\\b[^>]*>([\\s\\S]*?)</body\\s*>",
            RegexOption.IGNORE_CASE
        ).find(html)
        return match?.groupValues?.getOrNull(1) ?: html
    }

    private fun extractHeadContent(html: String): String {
        val match = EpubRegex.compile(
            "<head\\b[^>]*>([\\s\\S]*?)</head\\s*>",
            RegexOption.IGNORE_CASE
        ).find(html)
        return match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }.orEmpty()
    }

    private fun escapeHtmlAttr(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun flattenToc(items: List<TocItem>): List<TocItem> {
        val result = ArrayList<TocItem>()
        fun visit(item: TocItem) {
            result.add(item)
            item.children.forEach(::visit)
        }
        items.forEach(::visit)
        return result
    }

    companion object {
        private const val DIRECT_SOURCE_CACHE_ENTRIES = 4
        private const val DIRECT_SOURCE_CACHE_MAX_CHARS = 4 * 1024 * 1024
        private const val DIRECT_SOURCE_CACHE_TOTAL_CHARS = 8 * 1024 * 1024
        private const val DIRECT_FRAGMENT_INDEX_ENTRIES = 8
        private const val DIRECT_FRAGMENT_INDEX_MAX_BYTES = 8L * 1024L * 1024L
        private const val ContinuationHrefsKey = "epubContinuationHrefs"
        private const val DIRECT_RESOURCE_CACHE_ENTRY_BYTES = 8L * 1024L * 1024L
        private const val MAX_COVER_RESOURCE_BYTES = 32L * 1024L * 1024L
        private const val MAX_COVER_DOCUMENT_BYTES = 2L * 1024L * 1024L
        private const val COVER_REFERENCE_DEPTH = 2
        private val COVER_IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "avif", "svg", "svgz"
        )
        private val COVER_HINT = EpubRegex.compile(
            "(?:^|[/_.~\\-])(cover|frontcover|titlepage|title-page)(?:$|[/_.~\\-])",
            RegexOption.IGNORE_CASE
        )
        private val COVER_SCHEME = EpubRegex.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:")
        private val COVER_CSS_URL = EpubRegex.compile(
            "url\\(\\s*(?:'([^']*)'|\"([^\"]*)\"|([^)]*))\\s*\\)",
            RegexOption.IGNORE_CASE
        )
        private val DIRECT_FULLSCREEN_PROPERTIES = setOf(
            "duokan-page-fullscreen",
            "duokan-page-fullscreen-spread",
            "duokan-page-fullscreen-spread-left",
            "duokan-page-fullscreen-spread-right"
        )

        fun open(
            file: File,
            bookUrl: String = file.absolutePath,
            bookCacheDir: File,
            bookSignature: String
        ): EpubCoreFacade {
            val rawArchive = openArchive(file)
            var archive: EpubArchiveLeaseGate? = null
            return try {
                val pkg = EpubPackageParser().parse(rawArchive)
                val leasedArchive = EpubArchiveLeaseGate(
                    EpubFontDeobfuscatingArchive.wrap(rawArchive, pkg.metadata.identifier)
                )
                archive = leasedArchive
                val toc = EpubTocParser().parse(leasedArchive, pkg)
                EpubCoreFacade(
                    archive = leasedArchive,
                    bookUrl = bookUrl,
                    bookSignature = bookSignature,
                    bookCacheDir = bookCacheDir,
                    pkg = pkg,
                    toc = toc
                )
            } catch (throwable: Throwable) {
                (archive ?: rawArchive).close()
                throw throwable
            }
        }

        private fun openArchive(file: File): EpubArchive {
            return try {
                ZipEpubArchive(file)
            } catch (error: ZipException) {
                if (!isDuplicateEntryFailure(error)) throw error
                AppLog.putDebug(
                    "EPUB duplicate ZIP entry detected; using tolerant archive: ${file.name}"
                )
                AndroidZipEpubArchive(file)
            }
        }

        private fun isDuplicateEntryFailure(error: ZipException): Boolean {
            val message = error.message.orEmpty()
            return message.contains("duplicate", ignoreCase = true) ||
                message.contains("entry name", ignoreCase = true) ||
                message.contains("entries in archive", ignoreCase = true) ||
                message.contains("hash table", ignoreCase = true)
        }
    }
}
