package io.legado.app.ui.book.import.local

import android.app.Application
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.constant.PreferKey
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.FileDoc
import io.legado.app.utils.delete
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.list
import io.legado.app.utils.mapParallel
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import java.util.Collections
import kotlin.math.max
import splitties.init.appCtx

class ImportBookViewModel(application: Application) : BaseViewModel(application) {
    var rootDoc: FileDoc? = null
    val subDocs = arrayListOf<FileDoc>()
    var sort = context.getPrefInt(PreferKey.localBookImportSort)
    var dataCallback: DataCallback? = null
    var dataFlowStart: (() -> Unit)? = null
    var filterKey: String? = null
    val scanProgressFlow = MutableStateFlow<ScanProgress?>(null)
    val importProgressFlow = MutableStateFlow<ImportProgress?>(null)
    val dataFlow = callbackFlow<List<ImportBook>> {

        val list = Collections.synchronizedList(ArrayList<ImportBook>())

        dataCallback = object : DataCallback {

            override fun setItems(fileDocs: List<FileDoc>) {
                list.clear()
                fileDocs.mapTo(list) {
                    ImportBook(it)
                }
                trySend(list)
            }

            override fun addItems(fileDocs: List<FileDoc>) {
                fileDocs.mapTo(list) {
                    ImportBook(it)
                }
                trySend(list)
            }

            override fun clear() {
                list.clear()
                trySend(emptyList())
            }

            override fun upAdapter() {
                trySend(list)
            }

            override fun onScanStart() {
                scanProgressFlow.value = ScanProgress(
                    pendingDirs = 1,
                    scannedDirs = 0,
                    foundBooks = 0
                )
            }

            override fun onScanProgress(progress: ScanProgress) {
                scanProgressFlow.value = progress
            }

            override fun onScanFinish() {
                scanProgressFlow.value = null
            }
        }

        withContext(Main) {
            dataFlowStart?.invoke()
        }

        awaitClose {
            dataCallback = null
        }

    }.map { docList ->
        val docList = docList.toList()
        val filterKey = filterKey
        val skipFilter = filterKey.isNullOrBlank()
        val comparator = when (sort) {
            2 -> compareBy<ImportBook>({ !it.isDir }, { -it.lastModified })
            1 -> compareBy({ !it.isDir }, { -it.size })
            else -> compareBy { !it.isDir }
        } then compareBy(AlphanumComparator) { it.name }
        docList.asSequence().filter {
            skipFilter || it.name.contains(filterKey)
        }.sortedWith(comparator).toList()
    }.flowOn(IO)

    fun addToBookshelf(bookList: HashSet<ImportBook>, finally: () -> Unit) {
        execute {
            val fileUris = bookList.map {
                it.file.uri
            }
            LocalBook.importFiles(fileUris)
        }.onError {
            context.toastOnUi(R.string.toast_add_shelf_failed)
            AppLog.put("添加书架失败\n${it.localizedMessage}", it)
        }.onSuccess {
            context.toastOnUi(R.string.toast_add_shelf_success)
        }.onFinally {
            finally.invoke()
        }
    }

    fun addToBookshelfWithProgress(
        bookList: HashSet<ImportBook>,
        onProgress: (ImportProgress) -> Unit,
        finally: () -> Unit
    ) {
        execute {
            val total = bookList.size.coerceAtLeast(1)
            var processed = 0
            var imported = 0
            var failed = 0
            val initialProgress = ImportProgress(total, processed, imported, failed)
            importProgressFlow.value = initialProgress
            onProgress(initialProgress)
            bookList.forEach { item ->
                kotlin.runCatching {
                    fun emitItemStage(message: String) {
                        val progress = ImportProgress(
                            total = total,
                            processed = processed,
                            imported = imported,
                            failed = failed,
                            message = message
                        )
                        importProgressFlow.value = progress
                        onProgress(progress)
                    }
                    emitItemStage(appCtx.getString(R.string.import_reading_file_info, item.name))
                    val books = LocalBook.importFiles(item.file.uri) { stage ->
                        emitItemStage("$stage ${item.name}")
                    }
                    books.forEach { book ->
                        var lastEmitTime = 0L
                        LocalBook.prepareImportedBookCache(book) { stage, stageProcessed, stageTotal, title ->
                            val now = System.currentTimeMillis()
                            val shouldEmit = stageProcessed >= stageTotal || now - lastEmitTime >= 120L
                            if (shouldEmit) {
                                lastEmitTime = now
                                val stageName = when (stage) {
                                    "toc" -> appCtx.getString(R.string.import_stage_toc)
                                    "index" -> appCtx.getString(R.string.import_stage_index)
                                    "layout" -> appCtx.getString(R.string.import_stage_layout)
                                    else -> appCtx.getString(R.string.import_stage_decode)
                                }
                                val decodeProgress = ImportProgress(
                                    total = total,
                                    processed = processed,
                                    imported = imported,
                                    failed = failed,
                                    message = appCtx.getString(
                                    R.string.import_stage_progress,
                                    stageName,
                                    book.name,
                                    title,
                                    stageProcessed,
                                    max(stageTotal, 1)
                                )
                                )
                                importProgressFlow.value = decodeProgress
                                onProgress(decodeProgress)
                            }
                        }
                    }
                }.onSuccess {
                    imported += 1
                }.onFailure {
                    failed += 1
                    AppLog.put("导入失败: ${item.name}\n${it.localizedMessage}", it)
                }
                processed += 1
                val progress = ImportProgress(
                    total = total,
                    processed = processed,
                    imported = imported,
                    failed = failed,
                    message = appCtx.getString(R.string.import_start_book, item.name)
                )
                importProgressFlow.value = progress
                onProgress(progress)
            }
            if (processed == failed) {
                throw NoStackTraceException("ImportFiles Error:\nAll input files occur error")
            }
        }.onError {
            context.toastOnUi(R.string.toast_add_shelf_failed)
            AppLog.put("添加书架失败\n${it.localizedMessage}", it)
        }.onSuccess {
            context.toastOnUi(R.string.toast_add_shelf_success)
        }.onFinally {
            importProgressFlow.value = null
            finally.invoke()
        }
    }

    fun deleteDoc(bookList: HashSet<ImportBook>, finally: () -> Unit) {
        execute {
            bookList.forEach {
                it.file.delete()
            }
        }.onFinally {
            finally.invoke()
        }
    }

    fun loadDoc(fileDoc: FileDoc) {
        execute {
            val docList = fileDoc.list { item ->
                item.isVisibleImportBookItem()
            }
            dataCallback?.setItems(docList!!)
        }.onError {
            context.toastOnUi(appCtx.getString(R.string.toast_list_files_failed, it.localizedMessage))
        }
    }

    suspend fun scanDoc(fileDoc: FileDoc) {
        dataCallback?.clear()
        dataCallback?.onScanStart()
        try {
            val channel = Channel<FileDoc>(UNLIMITED)
            var n = 1
            var scannedDirCount = 0
            var foundBookCount = 0
            channel.trySend(fileDoc)
            val list = arrayListOf<FileDoc>()
            channel.consumeAsFlow()
                .mapParallel(16) { fileDoc ->
                    fileDoc.list()!!
                }.onEach { fileDocs ->
                    n--
                    scannedDirCount += 1
                    list.clear()
                    fileDocs.forEach {
                        if (it.isDir) {
                            n++
                            channel.trySend(it)
                        } else if (it.isVisibleImportBookItem()) {
                            list.add(it)
                            foundBookCount += 1
                        }
                    }
                    dataCallback?.addItems(list)
                    dataCallback?.onScanProgress(
                        ScanProgress(
                            pendingDirs = n.coerceAtLeast(0),
                            scannedDirs = scannedDirCount,
                            foundBooks = foundBookCount
                        )
                    )
                }.takeWhile {
                    n > 0
                }.catch {
                    context.toastOnUi(appCtx.getString(R.string.toast_scan_folder_failed, it.localizedMessage))
                }.collect()
        } finally {
            dataCallback?.onScanFinish()
        }
    }

    fun updateCallBackFlow(filterKey: String?) {
        this.filterKey = filterKey
        dataCallback?.upAdapter()
    }

    interface DataCallback {

        fun setItems(fileDocs: List<FileDoc>)

        fun addItems(fileDocs: List<FileDoc>)

        fun clear()

        fun upAdapter()

        fun onScanStart() {}

        fun onScanProgress(progress: ScanProgress) {}

        fun onScanFinish() {}

    }

    data class ScanProgress(
        val pendingDirs: Int,
        val scannedDirs: Int,
        val foundBooks: Int
    )

    data class ImportProgress(
        val total: Int,
        val processed: Int,
        val imported: Int,
        val failed: Int,
        val message: String = ""
    )

    private fun FileDoc.isVisibleImportBookItem(): Boolean {
        return when {
            name.startsWith(".") -> false
            isDir -> true
            else -> name.matches(bookFileRegex)
        }
    }

}
