package com.shimmer.reader.ui.book.toc

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.shimmer.reader.constant.EventBus
import com.shimmer.reader.base.VMBaseActivity
import com.shimmer.reader.data.entities.Book
import com.shimmer.reader.data.entities.BookChapter
import com.shimmer.reader.data.entities.Bookmark
import com.shimmer.reader.databinding.ActivityChapterListBinding
import com.shimmer.reader.help.book.isVideo
import com.shimmer.reader.model.ReadBook
import com.shimmer.reader.ui.about.AppLogDialog
import com.shimmer.reader.ui.book.bookmark.BookmarkDialog
import com.shimmer.reader.ui.book.toc.rule.TxtTocRuleDialog
import com.shimmer.reader.ui.file.HandleFileContract
import com.shimmer.reader.ui.widget.dialog.WaitDialog
import com.shimmer.reader.utils.observeEvent
import com.shimmer.reader.utils.showDialogFragment
import com.shimmer.reader.utils.viewbindingdelegate.viewBinding

/**
 * 目录
 */
class TocActivity : VMBaseActivity<ActivityChapterListBinding, TocViewModel>(),
    TxtTocRuleDialog.CallBack {

    override val binding by viewBinding(ActivityChapterListBinding::inflate)
    override val viewModel by viewModels<TocViewModel>()

    private val waitDialog by lazy { WaitDialog(this) }
    private var cacheRefreshTick = mutableIntStateOf(0)
    private var contentRefreshTick = mutableIntStateOf(0)
    private val exportDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                1 -> viewModel.saveBookmark(uri)
                2 -> viewModel.saveBookmarkMd(uri)
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val bookUrl = intent.getStringExtra("bookUrl").orEmpty()
        if (bookUrl.isNotBlank()) {
            viewModel.initBook(bookUrl)
        }
        binding.composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeView.setContent {
            TocComposeScreen(
                bookUrl = bookUrl,
                contentRefreshTick = contentRefreshTick.intValue,
                cacheRefreshTick = cacheRefreshTick.intValue,
                viewModel = viewModel,
                onBack = { finish() },
                onBookLoaded = { book ->
                    viewModel.bookData.value = book
                },
                onOpenChapter = ::openChapter,
                onEditBookmark = ::editBookmark,
                onShowTocRule = { book ->
                    showDialogFragment(TxtTocRuleDialog(book?.tocUrl ?: viewModel.bookData.value?.tocUrl))
                },
                onUpdateToc = ::upBookAndToc,
                onExportBookmark = {
                    exportDir.launch { requestCode = 1 }
                },
                onExportBookmarkMd = {
                    exportDir.launch { requestCode = 2 }
                },
                onShowLog = {
                    showDialogFragment<AppLogDialog>()
                }
            )
        }
    }

    override fun observeLiveBus() {
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, _) ->
            if (book.bookUrl == viewModel.bookUrl) {
                cacheRefreshTick.intValue++
            }
        }
    }

    override fun onResume() {
        super.onResume()
        contentRefreshTick.intValue++
    }

    override fun onTocRegexDialogResult(tocRegex: String) {
        viewModel.bookData.value?.let { book ->
            book.tocUrl = tocRegex
            upBookAndToc(book)
        }
    }

    private fun upBookAndToc(book: Book) {
        waitDialog.show()
        viewModel.upBookTocRule(book) {
            waitDialog.dismiss()
            contentRefreshTick.intValue++
            if (ReadBook.book == book) {
                if (it == null) {
                    ReadBook.upMsg(null)
                } else {
                    ReadBook.upMsg("LoadTocError:${it.localizedMessage}")
                }
            }
        }
    }

    private fun editBookmark(bookmark: Bookmark, position: Int) {
        showDialogFragment(BookmarkDialog(bookmark, position))
    }

    private fun openChapter(book: Book, chapterList: List<BookChapter>, bookChapter: BookChapter) {
        if (book.isVideo) {
            val volumes = chapterList.filter { it.isVolume }
            var chapterInVolumeIndex = 0
            var durVolumeIndex = 0
            if (volumes.isNotEmpty()) {
                for ((index, volume) in volumes.reversed().withIndex()) {
                    val first = bookChapter.index
                    if (volume.index < first) {
                        chapterInVolumeIndex = first - volume.index - 1
                        durVolumeIndex = volumes.size - index - 1
                        break
                    } else if (volume.index == first) {
                        chapterInVolumeIndex = 0
                        durVolumeIndex = volumes.size - index - 1
                        break
                    }
                }
            } else {
                chapterInVolumeIndex = bookChapter.index
            }
            setResult(
                RESULT_OK, Intent()
                    .putExtra("index", bookChapter.index)
                    .putExtra("chapterChanged", bookChapter.index != book.durChapterIndex)
                    .putExtra("chapterUrl", bookChapter.url)
                    .putExtra("chapterStartFragmentId", bookChapter.startFragmentId.orEmpty())
                    .putExtra("durVolumeIndex", durVolumeIndex)
                    .putExtra("chapterInVolumeIndex", chapterInVolumeIndex)
            )
            finish()
            return
        }
        setResult(
            RESULT_OK, Intent()
                .putExtra("index", bookChapter.index)
                .putExtra("chapterChanged", bookChapter.index != book.durChapterIndex)
                .putExtra("chapterUrl", bookChapter.url)
                .putExtra("chapterStartFragmentId", bookChapter.startFragmentId.orEmpty())
        )
        finish()
    }
}
