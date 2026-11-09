package com.shimmer.reader.ui.book.audio

import android.app.Application
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import com.shimmer.reader.R
import com.shimmer.reader.base.BaseViewModel
import com.shimmer.reader.constant.AppLog
import com.shimmer.reader.constant.BookType
import com.shimmer.reader.constant.EventBus
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.Book
import com.shimmer.reader.data.entities.BookChapter
import com.shimmer.reader.data.entities.BookSource
import com.shimmer.reader.help.book.BookHelp
import com.shimmer.reader.help.book.getBookSource
import com.shimmer.reader.help.book.removeType
import com.shimmer.reader.help.book.simulatedTotalChapterNum
import com.shimmer.reader.model.AudioPlay
import com.shimmer.reader.model.inheritNotShelfStateFrom
import com.shimmer.reader.model.resolveStoredBookshelfState
import com.shimmer.reader.model.webBook.WebBook
import com.shimmer.reader.utils.postEvent
import com.shimmer.reader.utils.toastOnUi

class AudioPlayViewModel(application: Application) : BaseViewModel(application) {
    val titleData = MutableLiveData<String>()
    val coverData = MutableLiveData<String>()
    val customBtnListData = MutableLiveData<Boolean>()

    fun initData(intent: Intent, success: (() -> Unit)) = AudioPlay.apply {
        execute {
            val bookUrl = intent.getStringExtra("bookUrl") ?: book?.bookUrl ?: return@execute
            val storedBook = appDb.bookDao.getBook(bookUrl)
            val targetBook = storedBook ?: run {
                book?.also { appDb.bookDao.insert(it) } ?: return@execute
            }
            inBookshelf = resolveStoredBookshelfState(storedBook)
            initBook(targetBook)
        }.onSuccess {
            success.invoke()
        }.onFinally {
            saveRead(true)
        }
    }

    private suspend fun initBook(book: Book) {
        val isSameBook = AudioPlay.book?.bookUrl == book.bookUrl
        if (isSameBook) {
            AudioPlay.upData(book)
        } else {
            AudioPlay.resetData(book)
        }
        customBtnListData.postValue(AudioPlay.bookSource?.customButton == true)
        titleData.postValue(book.name)
        coverData.postValue(book.getDisplayCover())
        if (book.tocUrl.isEmpty() && !loadBookInfo(book)) {
            return
        }
        if (AudioPlay.chapterSize == 0 && !loadChapterList(book)) {
            return
        }
    }

    private suspend fun loadBookInfo(book: Book): Boolean {
        val bookSource = AudioPlay.bookSource ?: return true
        try {
            WebBook.getBookInfoAwait(bookSource, book)
            return true
        } catch (e: Exception) {
            AppLog.put("详情页出错: ${e.localizedMessage}", e, true)
            return false
        }
    }

    private suspend fun loadChapterList(book: Book): Boolean {
        val bookSource = AudioPlay.bookSource ?: return true
        try {
            val oldBook = book.copy()
            val cList = WebBook.getChapterListAwait(bookSource, book).getOrThrow()
            val oldChapterList = appDb.bookChapterDao.getChapterList(oldBook.bookUrl)
            BookHelp.remapContentCache(oldBook, oldChapterList, cList)
            if (oldBook.bookUrl == book.bookUrl) {
                appDb.bookDao.update(book)
            } else {
                appDb.bookDao.replace(oldBook, book)
                BookHelp.updateCacheFolder(oldBook, book)
            }
            appDb.bookChapterDao.delByBook(oldBook.bookUrl)
            appDb.bookChapterDao.insert(*cList.toTypedArray())
            AudioPlay.chapterSize = cList.size
            AudioPlay.simulatedChapterSize = book.simulatedTotalChapterNum()
            AudioPlay.upDurChapter()
            return true
        } catch (_: Exception) {
            context.toastOnUi(R.string.error_load_toc)
            return false
        }
    }

    fun upSource() {
        execute {
            val book = AudioPlay.book ?: return@execute
            AudioPlay.bookSource = book.getBookSource()?.also{
                customBtnListData.postValue(it.customButton)
            }
        }
    }

    fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        execute {
            val oldBook = AudioPlay.book
            oldBook?.migrateTo(book, toc)
            book.inheritNotShelfStateFrom(oldBook)
            book.removeType(BookType.updateError)
            oldBook?.delete()
            appDb.bookDao.insert(book)
            AudioPlay.book = book
            AudioPlay.bookSource = source
            appDb.bookChapterDao.insert(*toc.toTypedArray())
            AudioPlay.upDurChapter()
        }.onFinally {
            postEvent(EventBus.SOURCE_CHANGED, book.bookUrl)
        }
    }

    fun removeFromBookshelf(success: (() -> Unit)?) {
        val bookUrl = AudioPlay.book?.bookUrl
        execute {
            bookUrl?.let(appDb.bookDao::deleteIfNotShelf)
        }.onError {
            AppLog.put("删除临时音频书失败: bookUrl=$bookUrl", it)
        }.onFinally {
            success?.invoke()
        }
    }

}
