package io.legado.app.ui.login

import android.app.Application
import android.content.Intent
import com.script.rhino.runScriptWithContext
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.ParagraphRuleJsExtensions
import io.legado.app.help.book.ReadMenuCustomButtonSource
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadBook
import io.legado.app.model.VideoPlay
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toastOnUi

class SourceLoginViewModel(application: Application) : BaseViewModel(application) {

    var source: BaseSource? = null
    var headerMap: Map<String, String> = emptyMap()
    var book: Book? = null
    var bookType: Int = 0
    var chapter: BookChapter? = null
    var loginInfo: MutableMap<String, String> = mutableMapOf()

    fun initData(intent: Intent, success: (bookSource: BaseSource) -> Unit, error: () -> Unit) {
        execute {
            bookType = intent.getIntExtra("bookType", 0)
            val sourceType = intent.getStringExtra("type")
            val contextSource = sourceType == "readMenuCustomButton" || sourceType == "paragraphRule"
            when (bookType) {
                BookType.text -> {
                    source = ReadBook.bookSource
                    book = ReadBook.book?.also {
                        chapter = appDb.bookChapterDao.getChapter(it.bookUrl, ReadBook.durChapterIndex)
                    }
                }

                BookType.audio -> {
                    source = AudioPlay.bookSource
                    book = AudioPlay.book
                    chapter = AudioPlay.durChapter
                }

                BookType.video -> {
                    source = VideoPlay.source
                    book = VideoPlay.book
                    chapter = VideoPlay.chapter
                }

                else -> {
                    val sourceKey = intent.getStringExtra("key")
                        ?: throw NoStackTraceException("没有参数")
                    source = when (sourceType) {
                        "bookSource" ->  appDb.bookSourceDao.getBookSource(sourceKey)
                        "rssSource" -> appDb.rssSourceDao.getByKey(sourceKey)
                        "httpTts" -> appDb.httpTTSDao.get(sourceKey.toLong())
                        "paragraphRule" -> appDb.paragraphRuleDao.get(sourceKey.toLong())?.let { rule ->
                            ParagraphRuleJsExtensions(rule)
                        }
                        "readMenuCustomButton" -> appDb.readMenuCustomButtonDao.get(sourceKey.toLong())?.let { button ->
                            ReadMenuCustomButtonSource(button)
                        }
                        else -> null
                    }
                    val bookUrl = intent.getStringExtra("bookUrl")
                    book = bookUrl?.let {
                        appDb.bookDao.getBook(it) ?: appDb.searchBookDao.getSearchBook(it)?.toBook()
                    }
                    if (book == null && contextSource) {
                        book = ReadBook.book
                    }
                    val chapterIndex = intent.getIntExtra("chapterIndex", -1)
                    chapter = book?.let { currentBook ->
                        chapterIndex.takeIf { it >= 0 }?.let {
                            appDb.bookChapterDao.getChapter(currentBook.bookUrl, it)
                        }
                    } ?: if (contextSource) {
                        ReadBook.curTextChapter?.chapter
                    } else {
                        null
                    }
                }
            }
            headerMap = runScriptWithContext {
                source?.getHeaderMap(true) ?: emptyMap()
            }
            source?.let {
                loginInfo = if (contextSource) {
                    it.getLoginInfo()?.let { json ->
                        GSON.fromJsonObject<MutableMap<String, String>>(json).getOrNull()
                    } ?: mutableMapOf()
                } else {
                    it.getLoginInfoMap()
                }
            }
            source
        }.onSuccess {
            if (it != null) {
                success.invoke(it)
            } else {
                context.toastOnUi("未找到书源")
            }
        }.onError {
            error.invoke()
            AppLog.put("登录 UI 初始化失败\n$it", it, true)
        }
    }

}
