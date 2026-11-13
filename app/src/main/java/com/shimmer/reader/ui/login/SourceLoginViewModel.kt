package com.shimmer.reader.ui.login

import android.app.Application
import android.content.Intent
import com.script.rhino.runScriptWithContext
import com.shimmer.reader.base.BaseViewModel
import com.shimmer.reader.constant.AppLog
import com.shimmer.reader.constant.BookType
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.BaseSource
import com.shimmer.reader.data.entities.Book
import com.shimmer.reader.data.entities.BookChapter
import com.shimmer.reader.exception.NoStackTraceException
import com.shimmer.reader.help.book.ParagraphRuleJsExtensions
import com.shimmer.reader.help.book.ReadMenuCustomButtonSource
import com.shimmer.reader.model.AudioPlay
import com.shimmer.reader.model.AutoTask
import com.shimmer.reader.model.ReadBook
import com.shimmer.reader.model.VideoPlay
import com.shimmer.reader.utils.GSON
import com.shimmer.reader.utils.fromJsonObject
import com.shimmer.reader.utils.toastOnUi
import java.util.Locale
import com.shimmer.reader.R
import splitties.init.appCtx

class SourceLoginViewModel(application: Application) : BaseViewModel(application) {

    var source: BaseSource? = null
    var headerMap: Map<String, String> = emptyMap()
    var book: Book? = null
    var bookType: Int = 0
    var chapter: BookChapter? = null
    var loginInfo: MutableMap<String, String> = mutableMapOf()

    fun initData(intent: Intent, success: (bookSource: BaseSource) -> Unit, error: () -> Unit) {
        var requestedAutoTask = false
        execute {
            bookType = intent.getIntExtra("bookType", 0)
            val sourceType = intent.readStringExtra("type")
            val sourceTypeKey = sourceType?.lowercase(Locale.ROOT)
            val sourceKeyExtra = intent.readStringExtra("key")
            val taskIdExtra = intent.readStringExtra(
                AutoTask.EXTRA_TASK_ID,
                "taskId"
            )
            val autoTaskSourceKeyExtra = intent.readStringExtra(
                AutoTask.EXTRA_SOURCE_KEY,
                "sourceKey"
            )
            val resolvedTaskId = AutoTask.resolveTaskId(
                sourceType = sourceType,
                key = sourceKeyExtra,
                taskId = taskIdExtra,
                sourceKey = autoTaskSourceKeyExtra
            )
            val contextSource = sourceTypeKey == "readmenucustombutton" ||
                sourceTypeKey == "paragraphrule"
            val explicitAutoTask = AutoTask.isAutoTaskType(sourceType) ||
                AutoTask.isAutoTaskSourceKey(sourceType) ||
                AutoTask.isAutoTaskSourceKey(sourceKeyExtra) ||
                AutoTask.isAutoTaskSourceKey(autoTaskSourceKeyExtra) ||
                taskIdExtra != null
            val implicitAutoTask = !explicitAutoTask &&
                bookType == 0 &&
                sourceType.isNullOrBlank() &&
                sourceKeyExtra != null &&
                AutoTask.getBySourceKey(sourceKeyExtra) != null
            val autoTaskSource = explicitAutoTask || implicitAutoTask
            requestedAutoTask = autoTaskSource
            when {
                autoTaskSource -> {
                    val taskId = resolvedTaskId
                        ?: sourceKeyExtra
                            ?.takeIf { it.isNotBlank() }
                        ?: throw NoStackTraceException(appCtx.getString(R.string.login_missing_task_params))
                    source = AutoTask.getBySourceKey(taskId)?.let(AutoTask::buildSource)
                        ?: throw NoStackTraceException(appCtx.getString(R.string.login_task_not_found))
                    book = null
                    chapter = null
                }

                bookType == BookType.text -> {
                    source = ReadBook.bookSource
                    book = ReadBook.book?.also {
                        chapter = appDb.bookChapterDao.getChapter(it.bookUrl, ReadBook.durChapterIndex)
                    }
                }

                bookType == BookType.audio -> {
                    source = AudioPlay.bookSource
                    book = AudioPlay.book
                    chapter = AudioPlay.durChapter
                }

                bookType == BookType.video -> {
                    source = VideoPlay.source
                    book = VideoPlay.book
                    chapter = VideoPlay.chapter
                }

                else -> {
                    val sourceKey = sourceKeyExtra
                        ?: throw NoStackTraceException(appCtx.getString(R.string.login_missing_params))
                    source = when (sourceTypeKey) {
                        "booksource" -> appDb.bookSourceDao.getBookSource(sourceKey)
                        "rsssource" -> appDb.rssSourceDao.getByKey(sourceKey)
                        "httptts" -> appDb.httpTTSDao.get(sourceKey.toLong())
                        "autotask", "auto_task", "auto-task" ->
                            AutoTask.getBySourceKey(sourceKey)?.let(AutoTask::buildSource)
                        "paragraphrule" -> appDb.paragraphRuleDao.get(sourceKey.toLong())?.let { rule ->
                            ParagraphRuleJsExtensions(rule)
                        }
                        "readmenucustombutton" -> appDb.readMenuCustomButtonDao.get(sourceKey.toLong())?.let { button ->
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
                context.toastOnUi(
                    if (requestedAutoTask) appCtx.getString(R.string.login_task_not_found)
                    else appCtx.getString(R.string.login_source_not_found)
                )
            }
        }.onError {
            error.invoke()
            val type = intent.readStringExtra("type").orEmpty()
            val key = intent.readStringExtra("key").orEmpty()
            val taskId = intent.readStringExtra(AutoTask.EXTRA_TASK_ID, "taskId").orEmpty()
            AppLog.put(
                "登录 UI 初始化失败\n$type=$type key=$key taskId=$taskId\n$it",
                it,
                true
            )
        }
    }

    private fun Intent.readStringExtra(vararg names: String): String? {
        return names.asSequence()
            .mapNotNull { name ->
                runCatching { extras?.get(name)?.toString() }.getOrNull()
            }
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
    }

}
