package io.legado.app.model

import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isOnLineTxt
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.utils.NetworkUtils
import kotlin.coroutines.CoroutineContext

/**
 * 批量正文下载上下文。
 *
 * 书源在 contentBatch 规则里拿到本批章节数组后，每处理完一章调用
 * `java.cacheContent(chapter, content)` 主动回存，不必等整批结束。
 *
 * 章节身份用 [BookChapter.index] 判定，不用 url：目录允许多章共用同一 url
 * （靠序号/标题/tag 区分），按 url 认章会把正文写到错误章节。
 *
 * JS 侧可能在多个线程里并发回调，所有可变状态都在 lock 下访问。
 */
class BatchContentContext(
    val bookSource: BookSource,
    val book: Book,
    val chapters: List<BookChapter>,
    private val coroutineContext: CoroutineContext
) {

    private val lock = Any()
    private var closed = false

    /** 已经通过 cacheContent 回存的章节序号 */
    private val savedIndexes = linkedSetOf<Int>()

    private val baseUrl: String
        get() = book.tocUrl.ifBlank { bookSource.bookSourceUrl }

    /**
     * url -> 候选章节，同一 url 可能对应多章，值一律是列表。
     * 同时登记原始 url、绝对 url 和规范化 url，书源回传哪种形式都能匹配。
     */
    private val chaptersByUrl: Map<String, List<BookChapter>> =
        buildMap<String, MutableList<BookChapter>> {
            chapters.forEach { chapter ->
                sequenceOf(
                    chapter.url,
                    runCatching { chapter.getAbsoluteURL() }.getOrNull(),
                    normalize(chapter.url)
                ).filterNot { it.isNullOrBlank() }
                    .distinct()
                    .forEach { key ->
                        val candidates = getOrPut(key!!) { mutableListOf() }
                        if (candidates.none { it.index == chapter.index }) {
                            candidates.add(chapter)
                        }
                    }
            }
        }

    /**
     * 解析书源回传的章节标识。
     *
     * 支持两种形式：
     * - 章节对象：直接取本批数组里的元素，重复 url 也不会认错；
     * - url 字符串：仅接受唯一命中，多章共用该 url 时拒绝，必须传章节对象。
     *
     * 刻意不接受纯数字：书源循环里传数组下标和传 chapter.index 无法区分，认错即静默写错章节。
     */
    fun resolveChapter(identifier: Any?): BookChapter? {
        return when (identifier) {
            is BookChapter -> chapters.firstOrNull { it.index == identifier.index }
            is CharSequence -> resolveByUrl(identifier.toString())
            else -> null
        }
    }

    private fun resolveByUrl(url: String): BookChapter? {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) return null
        val candidates = chaptersByUrl[trimmedUrl].orEmpty() +
                normalize(trimmedUrl)?.let { chaptersByUrl[it] }.orEmpty()
        return candidates.distinctBy { it.index }.singleOrNull()
    }

    private fun normalize(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching { NetworkUtils.getAbsoluteURL(baseUrl, url) }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * 对批量回传的正文套用书源的正文替换规则，与单章流程保持一致
     */
    fun applyContentReplace(chapter: BookChapter, content: String): String {
        val replaceRegex = bookSource.getContentRule().replaceRegex
        if (replaceRegex.isNullOrEmpty()) return content
        val analyzeRule = AnalyzeRule(book, bookSource)
        analyzeRule.setCoroutineContext(coroutineContext)
        analyzeRule.setChapter(chapter)
        analyzeRule.setBaseUrl(chapter.getAbsoluteURL())
        var contentStr = content.split(AppPattern.LFRegex).joinToString("\n") { it.trim() }
        contentStr = analyzeRule.getString(replaceRegex, contentStr)
        if (book.isOnLineTxt) {
            contentStr = contentStr.split(AppPattern.LFRegex).joinToString("\n") { "　　$it" }
        }
        return contentStr
    }

    /**
     * 回存单章正文，重复 url 的回调也走同一把锁
     */
    fun saveContent(identifier: Any?, content: String): Boolean = synchronized(lock) {
        if (closed) return false
        val chapter = resolveChapter(identifier)
            ?: throw NoStackTraceException(
                "java.cacheContent 未唯一匹配到本批次章节，重复 URL 请传章节对象: $identifier"
            )
        val replaced = applyContentReplace(chapter, content)
        if (replaced.isBlank()) return false
        BookHelp.saveContentBlocking(bookSource, book, chapter, replaced)
        savedIndexes.add(chapter.index)
        true
    }

    fun close() = synchronized(lock) { closed = true }

    fun markSaved(chapter: BookChapter) {
        synchronized(lock) {
            savedIndexes.add(chapter.index)
        }
    }

    fun isSaved(chapter: BookChapter): Boolean = synchronized(lock) {
        savedIndexes.contains(chapter.index)
    }

    fun savedCount(): Int = synchronized(lock) { savedIndexes.size }

    /** 本批次中书源没有回存的章节，交由调用方按普通单章流程重试 */
    fun missingChapters(): List<BookChapter> = synchronized(lock) {
        chapters.filterNot { savedIndexes.contains(it.index) }
    }
}
