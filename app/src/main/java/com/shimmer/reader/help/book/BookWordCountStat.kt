package com.shimmer.reader.help.book

import com.shimmer.reader.constant.AppLog
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.Book
import com.shimmer.reader.data.entities.BookChapter
import com.shimmer.reader.utils.StringUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext

/**
 * 用本地已缓存的正文，倒推一本书的总字数并写回书籍信息。
 *
 * 书源给的「字数」经常缺、或者是站点自己算的虚数；缓存下来的正文才是真实内容。
 * 这里只统计已缓存的章节，不联网抓正文，所以：
 * - 缓存完整度达标的书才写回，避免把「只下了三章」的书标成三章字数；
 * - 字数按去标签、去空白后的字符数算，中文场景下与常见的「字数」口径一致。
 */
object BookWordCountStat {

    /** 低于该缓存完整度不写回，防止把残缺数据当成全书字数。 */
    const val MIN_COVERAGE = 0.9f

    data class BookResult(
        val bookName: String,
        val chars: Long,
        val cachedChapters: Int,
        val totalChapters: Int,
        val written: Boolean
    )

    data class Summary(
        val covered: MutableList<BookResult> = mutableListOf(),
        val incomplete: MutableList<BookResult> = mutableListOf(),
        val failed: MutableList<String> = mutableListOf()
    ) {
        val writtenCount: Int get() = covered.size
        val incompleteCount: Int get() = incomplete.size
        val failedCount: Int get() = failed.size
        val scannedCount: Int get() = writtenCount + incompleteCount + failedCount
    }

    /**
     * @param books 待统计的书籍（调用方负责过滤成在线文本类）
     * @param minCoverage 缓存完整度阈值，见 [MIN_COVERAGE]
     * @param onProgress 每本书开始时回调，参数为「已处理数量、总数、书名」
     */
    suspend fun fill(
        books: List<Book>,
        minCoverage: Float = MIN_COVERAGE,
        onProgress: suspend (done: Int, total: Int, bookName: String) -> Unit = { _, _, _ -> }
    ): Summary {
        val summary = Summary()
        books.forEachIndexed { index, book ->
            onProgress(index, books.size, book.name)
            currentCoroutineContext().ensureActive()
            val stat = runCatching { statBook(book) }
                .getOrElse { e ->
                    if (e is CancellationException) throw e
                    AppLog.put("统计字数失败: ${book.name}", e)
                    summary.failed.add(book.name)
                    null
                } ?: return@forEachIndexed
            if (stat.totalChapters <= 0 || stat.cachedChapters <= 0) {
                summary.failed.add(book.name)
                return@forEachIndexed
            }
            val coverage = stat.cachedChapters.toFloat() / stat.totalChapters
            if (coverage < minCoverage || stat.chars <= 0L) {
                summary.incomplete.add(
                    BookResult(
                        bookName = book.name,
                        chars = stat.chars,
                        cachedChapters = stat.cachedChapters,
                        totalChapters = stat.totalChapters,
                        written = false
                    )
                )
                return@forEachIndexed
            }
            val text = StringUtils.wordCountFormat(stat.chars.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            if (text.isBlank()) {
                summary.incomplete.add(
                    BookResult(
                        bookName = book.name,
                        chars = stat.chars,
                        cachedChapters = stat.cachedChapters,
                        totalChapters = stat.totalChapters,
                        written = false
                    )
                )
                return@forEachIndexed
            }
            val saved = runCatching {
                book.wordCount = text
                appDb.bookDao.update(book)
            }.onFailure { AppLog.put("写回字数失败: ${book.name}", it) }.isSuccess
            if (!saved) {
                summary.failed.add(book.name)
                return@forEachIndexed
            }
            summary.covered.add(
                BookResult(
                    bookName = book.name,
                    chars = stat.chars,
                    cachedChapters = stat.cachedChapters,
                    totalChapters = stat.totalChapters,
                    written = true
                )
            )
        }
        return summary
    }

    private data class BookStat(
        val chars: Long,
        val cachedChapters: Int,
        val totalChapters: Int
    )

    private suspend fun statBook(book: Book): BookStat {
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
        val readable = chapters.filterNot { it.isVolume }
        var chars = 0L
        var cached = 0
        readable.forEach { chapter ->
            currentCoroutineContext().ensureActive()
            if (!BookHelp.hasContent(book, chapter)) return@forEach
            val content = runCatching { BookHelp.getContent(book, chapter) }
                .getOrElse {
                    if (it is CancellationException) throw it
                    null
                } ?: return@forEach
            cached++
            chars += countChars(content)
        }
        return BookStat(chars, cached, readable.size)
    }

    private val tagPattern = Regex("<[^>]*>")
    private val whitespacePattern = Regex("\\s+")

    /** 去标签、去空白后统计字符数；标签本身不算正文。 */
    fun countChars(content: String): Int {
        if (content.isEmpty()) return 0
        val plain = content.replace(tagPattern, "").replace(whitespacePattern, "")
        return plain.length
    }
}
