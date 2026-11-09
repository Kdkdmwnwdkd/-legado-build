package com.shimmer.reader.ui.association

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import com.shimmer.reader.constant.AppLog
import com.shimmer.reader.constant.AppPattern
import com.shimmer.reader.constant.AppPattern.bookFileRegex
import com.shimmer.reader.data.entities.Book
import com.shimmer.reader.help.book.highlight.HighlightImport
import com.shimmer.reader.model.localBook.LocalBook
import com.shimmer.reader.utils.*
import com.shimmer.reader.utils.compress.ZipUtils
import splitties.init.appCtx
import com.shimmer.reader.R

class FileAssociationViewModel(application: Application) : BaseAssociationViewModel(application) {
    val importBookLiveData = MutableLiveData<Uri>()
    val importRedThemeLiveData = MutableLiveData<Uri>()
    val importHighlightLiveData = MutableLiveData<Uri>()
    val importBubbleLiveData = MutableLiveData<Uri>()
    val onLineImportLive = MutableLiveData<Uri>()
    val openBookLiveData = MutableLiveData<Book>()
    val notSupportedLiveData = MutableLiveData<Pair<Uri, String>>()

    fun dispatchIntent(uri: Uri) {
        execute {
            //如果是普通的url，需要根据返回的内容判断是什么
            if (uri.isContentScheme() || uri.isFileScheme()) {
                val fileDoc = FileDoc.fromUri(uri, false)
                val fileName = fileDoc.name
                if (HighlightImport.isHighlightFile(fileDoc)) {
                    importHighlightLiveData.postValue(fileDoc.uri)
                    return@execute
                }
                if (fileName.endsWith(".red", ignoreCase = true)) {
                    importRedThemeLiveData.postValue(fileDoc.uri)
                    return@execute
                }
                if (fileName.matches(AppPattern.archiveFileRegex)) {
                    if (fileName.endsWith(".zip", ignoreCase = true) && isThemeZip(fileDoc)) {
                        importRedThemeLiveData.postValue(fileDoc.uri)
                        return@execute
                    }
                    if (fileName.endsWith(".zip", ignoreCase = true) && isBubbleZip(fileDoc)) {
                        importBubbleLiveData.postValue(fileDoc.uri)
                        return@execute
                    }
                    ArchiveUtils.deCompress(fileDoc, ArchiveUtils.TEMP_PATH) {
                        it.matches(bookFileRegex) || it.endsWith(".red", ignoreCase = true)
                    }.forEach {
                        dispatch(FileDoc.fromFile(it))
                    }
                } else {
                    dispatch(fileDoc)
                }
            } else {
                onLineImportLive.postValue(uri)
            }
        }.onError {
            it.printOnDebug()
            val msg = appCtx.getString(R.string.association_open_file_failed, it.localizedMessage)
            errorLive.postValue(msg)
            AppLog.put(msg, it)
        }
    }

    private fun dispatch(fileDoc: FileDoc) {
        if (HighlightImport.isHighlightFile(fileDoc)) {
            importHighlightLiveData.postValue(fileDoc.uri)
            return
        }
        kotlin.runCatching {
            if (fileDoc.openInputStream().getOrNull().isJson()) {
                importJson(fileDoc.uri)
                return
            }
        }.onFailure {
            it.printOnDebug()
            AppLog.put("尝试导入为JSON文件失败\n${it.localizedMessage}", it)
        }
        if (fileDoc.name.endsWith(".js", ignoreCase = true)) {
            importJsSource(fileDoc.uri)
            return
        }
        if (fileDoc.name.endsWith(".red", ignoreCase = true)) {
            importRedThemeLiveData.postValue(fileDoc.uri)
            return
        }
        if (fileDoc.name.matches(bookFileRegex)) {
            importBookLiveData.postValue(fileDoc.uri)
            return
        }
        notSupportedLiveData.postValue(Pair(fileDoc.uri, fileDoc.name))
    }

    private fun isThemeZip(fileDoc: FileDoc): Boolean {
        return kotlin.runCatching {
            fileDoc.openInputStream().getOrThrow().use { input ->
                ZipUtils.getFilesName(input) { true }.any { name ->
                    val normalized = name.replace('\\', '/').trim('/')
                    normalized.endsWith("/theme.json", ignoreCase = true) ||
                        normalized.equals("theme.json", ignoreCase = true) ||
                        normalized.endsWith("/appearance_kit.json", ignoreCase = true) ||
                        normalized.equals("appearance_kit.json", ignoreCase = true)
                }
            }
        }.getOrDefault(false)
    }

    private fun isBubbleZip(fileDoc: FileDoc): Boolean {
        return kotlin.runCatching {
            fileDoc.openInputStream().getOrThrow().use { input ->
                ZipUtils.getFilesName(input) { true }.any { name ->
                    val normalized = name.replace('\\', '/').trim('/')
                    normalized.endsWith("/bubble.json", ignoreCase = true) ||
                        normalized.equals("bubble.json", ignoreCase = true)
                }
            }
        }.getOrDefault(false)
    }

    fun importBook(uri: Uri) {
        val book = LocalBook.importFile(uri)
        openBookLiveData.postValue(book)
    }
}
