package com.shimmer.reader.ui.book.bookmark

import android.app.Application
import android.net.Uri
import com.shimmer.reader.R
import com.shimmer.reader.base.BaseViewModel
import com.shimmer.reader.constant.AppLog
import com.shimmer.reader.data.entities.Bookmark
import com.shimmer.reader.help.book.BookNoteExport
import com.shimmer.reader.help.book.NoteEntry
import com.shimmer.reader.utils.FileDoc
import com.shimmer.reader.utils.GSON
import com.shimmer.reader.utils.createFileIfNotExist
import com.shimmer.reader.utils.openOutputStream
import com.shimmer.reader.utils.toastOnUi
import com.shimmer.reader.utils.writeToOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AllBookmarkViewModel(application: Application) : BaseViewModel(application) {


    /**
     * 导出书签。导出范围由调用方传入，跟随列表当前的搜索结果。
     */
    fun exportBookmark(treeUri: Uri, bookmarks: List<Bookmark>) {
        execute {
            val dateFormat = SimpleDateFormat("yyMMddHHmmss", Locale.getDefault())
            val fileName = "bookmark-${dateFormat.format(Date())}.json"
            val dirDoc = FileDoc.fromUri(treeUri, true)
            dirDoc.createFileIfNotExist(fileName).openOutputStream().getOrThrow().use {
                GSON.writeToOutputStream(it, bookmarks)
            }
        }.onError {
            AppLog.put("导出失败\n${it.localizedMessage}", it, true)
        }.onSuccess {
            context.toastOnUi(R.string.toast_export_success)
        }
    }


    /**
     * 合并导出成一个 Markdown。同一章的多条摘录只写一次章节标题。
     */
    fun exportBookmarkMd(treeUri: Uri, bookmarks: List<Bookmark>) {
        execute {
            val now = System.currentTimeMillis()
            val content = BookNoteExport.renderMerged(bookmarks.map { it.toNoteEntry() }, now)
            if (content.isBlank()) return@execute
            val dateFormat = SimpleDateFormat("yyMMddHHmmss", Locale.getDefault())
            val fileName = "bookmark-${dateFormat.format(Date(now))}.md"
            FileDoc.fromUri(treeUri, true)
                .createFileIfNotExist(fileName)
                .openOutputStream().getOrThrow()
                .use { it.write(content.toByteArray()) }
        }.onError {
            AppLog.put("导出失败\n${it.localizedMessage}", it, true)
        }.onSuccess {
            context.toastOnUi(R.string.toast_export_success)
        }
    }

    /**
     * 按书导出，一本书一个 Markdown，带 frontmatter，放进 Obsidian 就能当笔记用。
     *
     * @return 导出的书本数
     */
    fun exportBookmarkObsidian(treeUri: Uri, bookmarks: List<Bookmark>) {
        execute {
            val now = System.currentTimeMillis()
            val books = BookNoteExport.group(bookmarks.map { it.toNoteEntry() })
            if (books.isEmpty()) return@execute 0
            val dirDoc = FileDoc.fromUri(treeUri, true)
            // 两本书清洗后可能撞名（比如书名里的 / 和 : 都被替换成 _），撞了加序号
            val used = HashSet<String>()
            books.forEach { book ->
                var fileName = BookNoteExport.fileNameOf(book)
                if (!used.add(fileName)) {
                    val base = fileName.removeSuffix(".md")
                    var index = 2
                    while (!used.add("$base ($index).md")) index++
                    fileName = "$base ($index).md"
                }
                dirDoc.createFileIfNotExist(fileName)
                    .openOutputStream().getOrThrow()
                    .use { it.write(BookNoteExport.renderBook(book, now).toByteArray()) }
            }
            books.size
        }.onError {
            AppLog.put("导出到 Obsidian 失败\n${it.localizedMessage}", it, true)
        }.onSuccess { count ->
            if (count > 0) {
                context.toastOnUi("已导出 $count 本书的摘录")
            } else {
                context.toastOnUi("没有可导出的摘录")
            }
        }
    }

    private fun Bookmark.toNoteEntry() = NoteEntry(
        bookName = bookName,
        bookAuthor = bookAuthor,
        time = this.time,
        chapterIndex = chapterIndex,
        chapterName = chapterName,
        text = bookText,
        content = content,
    )

}