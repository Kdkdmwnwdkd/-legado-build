package io.legado.app.model.localBook.epubcore.cache

import android.util.AtomicFile
import androidx.documentfile.provider.DocumentFile
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getLocalUri
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.readBytesLimited
import io.legado.app.utils.fromJsonArray
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object EpubCoreDiskCache {

    private const val CoreSchemaVersion = 8
    private const val RootDirName = "epub_core"
    private const val StructureDirName = "structure"
    private const val MaxInflatedCacheBytes = 64L * 1024L * 1024L

    fun sourceFingerprint(book: Book): String {
        val uri = book.getLocalUri()
        return if (uri.isContentScheme()) {
            val doc = runCatching { DocumentFile.fromSingleUri(appCtx, uri) }.getOrNull()
            buildString {
                append(book.bookUrl)
                append('|').append(book.originName)
                append('|').append(uri)
                append('|').append(doc?.length() ?: 0L)
                append('|').append(doc?.lastModified() ?: 0L)
            }
        } else {
            val file = File(uri.path.orEmpty())
            buildString {
                append(book.bookUrl)
                append('|').append(book.originName)
                append('|').append(file.absolutePath)
                append('|').append(file.length())
                append('|').append(file.lastModified())
            }
        }
    }

    fun bookSignature(book: Book): String = bookSignature(sourceFingerprint(book))

    fun bookSignature(sourceFingerprint: String): String {
        return "$sourceFingerprint|epubCoreSchema=$CoreSchemaVersion"
    }

    fun readChapterList(bookCacheDir: File, bookSignature: String): List<BookChapter>? {
        val file = structureFile(bookCacheDir, bookSignature)
        if (!hasAtomicFile(file)) return null
        return readGzipText(file)
            ?.let { GSON.fromJsonArray<BookChapter>(it).getOrNull() }
            ?.takeIf { it.isNotEmpty() }
    }

    fun writeChapterList(bookCacheDir: File, bookSignature: String, chapters: List<BookChapter>) {
        if (chapters.isEmpty()) return
        writeGzipText(structureFile(bookCacheDir, bookSignature), GSON.toJson(chapters))
    }

    fun clear(bookCacheDir: File) {
        FileUtils.delete(File(bookCacheDir, RootDirName))
    }

    private fun structureFile(bookCacheDir: File, bookSignature: String): File {
        return File(baseDir(bookCacheDir), "$StructureDirName/${fileHash(bookSignature)}.json.gz")
    }

    private fun baseDir(bookCacheDir: File): File {
        return File(bookCacheDir, RootDirName).apply { mkdirs() }
    }

    private fun fileHash(value: String): String {
        return MD5Utils.md5Encode16(value)
    }

    private fun readGzipText(file: File): String? {
        val atomicFile = AtomicFile(file)
        return runCatching {
            GZIPInputStream(atomicFile.openRead()).use { input ->
                input.readBytesLimited(MaxInflatedCacheBytes).toString(Charsets.UTF_8)
            }
        }.getOrElse {
            runCatching { atomicFile.delete() }
            null
        }
    }

    @Synchronized
    private fun writeGzipText(file: File, content: String) {
        file.parentFile?.mkdirs()
        val atomicFile = AtomicFile(file)
        var output: FileOutputStream? = null
        try {
            val stream = atomicFile.startWrite()
            output = stream
            GZIPOutputStream(stream).apply {
                write(content.toByteArray(Charsets.UTF_8))
                finish()
                flush()
            }
            atomicFile.finishWrite(stream)
            output = null
        } catch (_: Throwable) {
            runCatching { output?.let(atomicFile::failWrite) }
        }
    }

    private fun hasAtomicFile(file: File): Boolean {
        return file.isFile || File("${file.path}.bak").isFile
    }
}
