package com.shimmer.reader.help.book

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一条摘录的导出视图。
 *
 * 不直接用 [com.shimmer.reader.data.entities.Bookmark]，是为了让排版逻辑能脱离 Android 单测。
 */
data class NoteEntry(
    val bookName: String,
    val bookAuthor: String,
    val time: Long,
    val chapterIndex: Int,
    val chapterName: String,
    val text: String,
    val content: String,
)

data class ChapterGroup(
    val name: String,
    val notes: List<NoteEntry>,
)

data class BookGroup(
    val name: String,
    val author: String,
    val chapters: List<ChapterGroup>,
) {
    val count: Int get() = chapters.sumOf { it.notes.size }

    /** 「书名（作者）」，作者为空时只有书名 */
    val title: String get() = if (author.isBlank()) name else "$name（$author）"
}

/**
 * 把书签导出成 Obsidian 能直接收下的 Markdown。
 *
 * 合并导出和分书导出共用同一套条目排版，只是层级不同：
 * 分书是一本书一个文件，合并是一个文件里按书分节。
 */
object BookNoteExport {

    const val FALLBACK_CHAPTER = "未分章"
    const val FALLBACK_NAME = "未命名"

    /** 文件名太长在部分文件管理器上会创建失败 */
    private const val MAX_FILE_NAME = 80

    /** Obsidian 不允许出现在文件名里的字符，顺带处理掉会干扰 wiki 链接的 # ^ [ ] */
    private val illegalChars = Regex("[\\\\/:*?\"<>|#^\\[\\]]")

    private val spaces = Regex("\\s+")

    /**
     * 按书分组，书内再按章节分组。
     *
     * 输入已经按书名、章节序号排好，这里只做相邻归并，不重新排序——
     * 重新排序会把同一章的条目打散，也会让用户熟悉的顺序变掉。
     */
    fun group(notes: List<NoteEntry>): List<BookGroup> {
        val byBook = LinkedHashMap<Pair<String, String>, MutableList<NoteEntry>>()
        notes.forEach { note ->
            byBook.getOrPut(note.bookName to note.bookAuthor) { mutableListOf() }.add(note)
        }
        return byBook.map { (key, list) ->
            val chapters = ArrayList<ChapterGroup>()
            var currentKey: String? = null
            var buffer: MutableList<NoteEntry>? = null
            list.forEach { note ->
                val name = note.chapterName.trim().ifBlank { FALLBACK_CHAPTER }
                val chapterKey = "${note.chapterIndex}|$name"
                if (chapterKey != currentKey) {
                    val one = ArrayList<NoteEntry>()
                    buffer = one
                    chapters.add(ChapterGroup(name, one))
                    currentKey = chapterKey
                }
                buffer?.add(note)
            }
            BookGroup(key.first.trim().ifBlank { FALLBACK_NAME }, key.second, chapters)
        }
    }

    /** 一本书对应的文件名，含 .md 后缀 */
    fun fileNameOf(book: BookGroup): String = sanitizeFileName(book.title) + ".md"

    fun sanitizeFileName(raw: String): String {
        val cleaned = raw
            .replace(illegalChars, "_")
            .replace(spaces, " ")
            .trim()
            .trimEnd('.')
        val cut = if (cleaned.length > MAX_FILE_NAME) cleaned.take(MAX_FILE_NAME).trim() else cleaned
        // 整串都是被替换掉的字符（比如书名只有一个 /）时，别留下一个全是下划线的文件名
        return if (cut.none { it != '_' && it != ' ' && it != '.' }) FALLBACK_NAME else cut
    }

    /**
     * 单本书的 Markdown 全文：frontmatter + 书名 + 每章一节。
     *
     * 时间格式化通过参数传入，方便单测固定日期。
     */
    fun renderBook(
        book: BookGroup,
        exportedAt: Long,
        dateOf: (Long) -> String = { defaultDate(it) },
        dateTimeOf: (Long) -> String = { defaultDateTime(it) },
    ): String = buildString {
        val name = book.name.ifBlank { FALLBACK_NAME }
        append("---\n")
        append("title: ").append(yaml(name)).append('\n')
        if (book.author.isNotBlank()) {
            append("author: ").append(yaml(book.author)).append('\n')
        }
        append("tags:\n  - 书摘\n")
        append("created: ").append(dateOf(exportedAt)).append('\n')
        append("notes: ").append(book.count).append('\n')
        append("---\n\n")

        append("# ").append(name).append("\n\n")

        val meta = buildList {
            if (book.author.isNotBlank()) add("作者：${book.author}")
            add("摘录：${book.count} 条")
            add("导出：${dateOf(exportedAt)}")
        }
        append(meta.joinToString("　·　")).append("\n\n")

        book.chapters.forEach { chapter ->
            append("## ").append(chapter.name).append("\n\n")
            chapter.notes.forEach { note -> appendNote(this, note, dateTimeOf) }
        }
    }

    /**
     * 全部书签合并成一个文件。
     *
     * 同一章的多条摘录只写一次章节标题，不再像以前那样每一条都重复一遍。
     */
    fun renderMerged(
        notes: List<NoteEntry>,
        exportedAt: Long,
        dateOf: (Long) -> String = { defaultDate(it) },
        dateTimeOf: (Long) -> String = { defaultDateTime(it) },
    ): String {
        if (notes.isEmpty()) return ""
        val books = group(notes)
        return buildString {
            append("# 书摘\n\n")
            append("共 ${notes.size} 条　·　${books.size} 本书　·　导出：${dateOf(exportedAt)}\n\n")
            books.forEach { book ->
                append("## ").append(book.title).append("\n\n")
                book.chapters.forEach { chapter ->
                    append("### ").append(chapter.name).append("\n\n")
                    chapter.notes.forEach { note -> appendNote(this, note, dateTimeOf) }
                }
            }
        }
    }

    private fun appendNote(sb: StringBuilder, note: NoteEntry, dateTimeOf: (Long) -> String) {
        val text = note.text.trim()
        if (text.isNotEmpty()) {
            // 摘录写成引用块，导入 Obsidian 后一眼能看出这是原文
            sb.append("> ").append(text.replace("\n", "\n> ")).append("\n\n")
        }
        val content = note.content.trim()
        if (content.isNotEmpty()) {
            // 两格空格加换行是 Markdown 的硬换行，想法里的分行不会塌成一段
            sb.append("**想法**：").append(content.replace("\n", "  \n")).append("\n\n")
        }
        sb.append('*').append(dateTimeOf(note.time)).append("*\n\n")
    }

    /** YAML 双引号字符串，书名里的引号和反斜杠要转义 */
    private fun yaml(raw: String): String {
        val escaped = raw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
        return "\"$escaped\""
    }

    private fun defaultDate(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(time))

    private fun defaultDateTime(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))
}
