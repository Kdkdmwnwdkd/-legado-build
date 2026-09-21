package io.legado.app.model.localBook.epubcore.facade

import io.legado.app.help.book.highlight.HighlightRules

import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getLocalUri
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.localBook.epubcore.cache.EpubCoreDiskCache
import io.legado.app.model.localBook.epubcore.direct.EpubDirectSession
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.isContentScheme
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream

object EpubCoreProvider {

    private const val MaxOpenSessions = 2
    private val holders = LinkedHashMap<String, Holder>(4, 0.75f, true)
    private val retiredHolders = linkedSetOf<Holder>()

    fun getChapterList(book: Book): ArrayList<BookChapter> {
        val cacheDir = BookHelp.getCacheDir(book)
        val sourceFingerprint = EpubCoreDiskCache.sourceFingerprint(book)
        val signature = EpubCoreDiskCache.bookSignature(sourceFingerprint)
        synchronized(this) {
            holders.values
                .filter { it.bookUrl == book.bookUrl && it.sourceFingerprint != sourceFingerprint }
                .toList()
                .forEach(::retire)
        }
        EpubCoreDiskCache.readChapterList(cacheDir, signature)?.let { cached ->
            return ArrayList(cached.mapIndexed { index, chapter ->
                chapter.copy(bookUrl = book.bookUrl, index = index)
            })
        }
        val holder = synchronized(this) { acquire(book, sourceFingerprint) }
        return try {
            val chapters = ArrayList(holder.facade.chapters().mapIndexed { index, chapter ->
                chapter.copy(bookUrl = book.bookUrl, index = index)
            })
            EpubCoreDiskCache.writeChapterList(cacheDir, signature, chapters)
            chapters
        } finally {
            synchronized(this) { release(holder) }
        }
    }

    /**
     * Read the package-declared cover using the same archive/parser as Direct
     * EPUB. The returned bytes are detached from the archive lease.
     */
    fun getCoverResource(book: Book): EpubCoreCoverResource? {
        val sourceFingerprint = EpubCoreDiskCache.sourceFingerprint(book)
        val holder = synchronized(this) { acquire(book, sourceFingerprint) }
        return try {
            holder.facade.coverResource()
        } finally {
            synchronized(this) { release(holder) }
        }
    }

    fun openDirectSession(book: Book): EpubDirectSession {
        val holder = synchronized(this) { acquire(book) }
        val bookUrl = book.bookUrl
        // The facade list is the one index space used by direct chapters. Do not mix it with
        // a stale persisted TOC while a book is being rebuilt; that is what made a valid
        // boundary target resolve to a different document.
        val navigationChapters = holder.facade.chapters()
        return EpubDirectSession(
            bookUrl = bookUrl,
            resourceHost = holder.facade.directResourceHost(),
            chapterLoader = { chapterIndex, config ->
                val persistedChapter = navigationChapters.firstOrNull { it.index == chapterIndex }
                val chapter = if (persistedChapter != null) {
                    holder.facade.prepareDirectChapter(persistedChapter, config)
                } else {
                    holder.facade.prepareDirectChapter(chapterIndex, config)
                }
                chapter.copy(html = HighlightRules.decorate(chapter.html, bookUrl, true,
                    holder.facade.directResourceHost(), config))
            },
            resourceLoader = { path, range ->
                HighlightRules.resource(path) ?: holder.facade.openDirectResource(path, range)
            },
            headResourceLoader = { path, range ->
                HighlightRules.resource(path, true) ?: holder.facade.openDirectResourceHead(path, range)
            },
            linkResolver = { url, currentChapterIndex ->
                holder.facade.resolveDirectLink(
                    url = url,
                    currentChapterIndex = currentChapterIndex,
                    navigationChapters = navigationChapters
                )
            },
            readableChapterResolver = { index, direction ->
                EpubReadableChapterPolicy.resolve(navigationChapters, index, direction)
            },
            adjacentChapterResolver = { index, direction ->
                EpubReadableChapterPolicy.adjacent(navigationChapters, index, direction)
            },
            adjacentLogicalChapterResolver = { index, direction ->
                EpubReadableChapterPolicy.adjacentLogical(navigationChapters, index, direction)
            },
            closeAction = { synchronized(this) { release(holder) } },
            chapterRevisionProvider = { HighlightRules.store.revision() +
                "|assets:" + io.legado.app.help.reader.ReaderAssets.store.revision() }
        )
    }

    @Synchronized
    fun clear() {
        holders.values.toList().forEach(::retire)
    }

    @Synchronized
    fun clear(bookUrl: String) {
        holders.values.filter { it.bookUrl == bookUrl }.toList().forEach(::retire)
        retiredHolders.filter { it.bookUrl == bookUrl && it.activeUses == 0 }
            .toList()
            .forEach { holder ->
                retiredHolders.remove(holder)
                holder.close()
            }
    }

    @Synchronized
    fun clearBookCache(book: Book) {
        clear(book.bookUrl)
        EpubCoreDiskCache.clear(BookHelp.getCacheDir(book))
    }

    private fun acquire(book: Book): Holder {
        val sourceFingerprint = EpubCoreDiskCache.sourceFingerprint(book)
        return open(book, sourceFingerprint).also { it.activeUses++ }
    }

    private fun acquire(book: Book, sourceFingerprint: String): Holder {
        return open(book, sourceFingerprint).also { it.activeUses++ }
    }

    private fun release(holder: Holder) {
        check(holder.activeUses > 0) { "EPUB session lease underflow" }
        holder.activeUses--
        if (holder.activeUses == 0 && holder.closeWhenIdle) {
            retiredHolders.remove(holder)
            holder.close()
        }
    }

    private fun open(book: Book, sourceFingerprint: String): Holder {
        holders[sourceFingerprint]?.let { return it }
        holders.values
            .filter { it.bookUrl == book.bookUrl && it.sourceFingerprint != sourceFingerprint }
            .toList()
            .forEach(::retire)
        val file = resolveReadableFile(book, sourceFingerprint)
        val facade = EpubCoreFacade.open(
            file = file,
            bookUrl = book.bookUrl,
            bookCacheDir = BookHelp.getCacheDir(book),
            bookSignature = EpubCoreDiskCache.bookSignature(sourceFingerprint)
        )
        return Holder(sourceFingerprint, book.bookUrl, file, facade).also { holder ->
            holders[sourceFingerprint] = holder
            pruneSessions()
        }
    }

    private fun pruneSessions() {
        while (holders.size > MaxOpenSessions) {
            val eldest = holders.entries.firstOrNull()?.value ?: return
            retire(eldest)
        }
    }

    private fun retire(holder: Holder) {
        holders.remove(holder.sourceFingerprint)
        if (holder.activeUses == 0) {
            holder.close()
        } else {
            holder.closeWhenIdle = true
            retiredHolders += holder
        }
    }

    private fun resolveReadableFile(book: Book, sourceFingerprint: String): File {
        val uri = book.getLocalUri()
        if (!uri.isContentScheme()) {
            uri.path?.let { path ->
                val file = File(path)
                if (file.isFile) return file
            }
        }
        val cacheDir = File(appCtx.cacheDir, "epub-core").apply { mkdirs() }
        val suffix = book.originName.substringAfterLast('.', "epub").ifBlank { "epub" }
        val expectedLength = if (uri.isContentScheme()) {
            runCatching { DocumentFile.fromSingleUri(appCtx, uri)?.length() }
                .getOrNull()
                ?.takeIf { it > 0L }
        } else {
            File(uri.path.orEmpty()).length().takeIf { it > 0L }
        }
        val cacheFile = File(cacheDir, "${MD5Utils.md5Encode16(sourceFingerprint)}.$suffix")
        if (cacheFile.isFile && cacheFile.length() > 0L) {
            if (expectedLength == null || cacheFile.length() == expectedLength) return cacheFile
        }
        val tempFile = File(cacheDir, ".${cacheFile.name}.${System.nanoTime()}.tmp")
        try {
            LocalBook.getBookInputStream(book).use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            check(tempFile.length() > 0L) { "EPUB source copy is empty" }
            if (expectedLength != null) {
                check(tempFile.length() == expectedLength) { "EPUB source copy size mismatch" }
            }
            if (cacheFile.exists() && !cacheFile.delete()) {
                error("Unable to replace cached EPUB file")
            }
            if (!tempFile.renameTo(cacheFile)) {
                FileOutputStream(cacheFile).use { output ->
                    tempFile.inputStream().use { input -> input.copyTo(output) }
                    output.fd.sync()
                }
            }
            return cacheFile
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private class Holder(
        val sourceFingerprint: String,
        val bookUrl: String,
        val file: File,
        val facade: EpubCoreFacade
    ) {
        var activeUses: Int = 0
        var closeWhenIdle: Boolean = false
        private var closed: Boolean = false

        fun close() {
            if (closed) return
            closed = true
            runCatching { facade.close() }.onFailure {
                AppLog.putDebug("EPUB core close failed: ${it.localizedMessage}", it)
            }
        }
    }
}
