package com.shimmer.reader.ui.book.bookmark

import android.app.Application
import android.net.Uri
import com.shimmer.reader.R
import com.shimmer.reader.base.BaseViewModel
import com.shimmer.reader.constant.AppLog
import com.shimmer.reader.data.entities.Bookmark
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


    fun exportBookmarkMd(treeUri: Uri, bookmarks: List<Bookmark>) {
        execute {
            val dateFormat = SimpleDateFormat("yyMMddHHmmss", Locale.getDefault())
            val fileName = "bookmark-${dateFormat.format(Date())}.md"
            val dirDoc = FileDoc.fromUri(treeUri, true)
            val fileDoc = dirDoc.createFileIfNotExist(fileName).openOutputStream().getOrThrow()
            fileDoc.use { outputStream ->
                var name = ""
                var author = ""
                bookmarks.forEach {
                    if (it.bookName != name || it.bookAuthor != author) {
                        name = it.bookName
                        author = it.bookAuthor
                        val title = if (author.isBlank()) name else "$name（$author）"
                        outputStream.write("## $title\n\n".toByteArray())
                    }
                    outputStream.write("### ${it.chapterName}\n\n".toByteArray())
                    if (it.bookText.isNotBlank()) {
                        // 摘录按引用块写，导入其他 Markdown 工具时能看出这是原文
                        val quoted = it.bookText.trim().replace("\n", "\n> ")
                        outputStream.write("> $quoted\n\n".toByteArray())
                    }
                    if (it.content.isNotBlank()) {
                        outputStream.write("${it.content.trim()}\n\n".toByteArray())
                    }
                }
            }
        }.onError {
            AppLog.put("导出失败\n${it.localizedMessage}", it, true)
        }.onSuccess {
            context.toastOnUi(R.string.toast_export_success)
        }
    }

}