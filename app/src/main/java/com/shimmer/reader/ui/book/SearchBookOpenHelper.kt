package com.shimmer.reader.ui.book

import android.content.Context
import android.content.Intent
import com.shimmer.reader.constant.BookSourceType
import com.shimmer.reader.constant.BookType
import com.shimmer.reader.constant.SourceType
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.SearchBook
import com.shimmer.reader.ui.book.info.BookInfoNavigator
import com.shimmer.reader.ui.video.VideoPlayerActivity

object SearchBookOpenHelper {

    const val EXTRA_COVER_URL = "coverUrl"

    fun open(context: Context, book: SearchBook, isVideo: Boolean) {
        if (isVideo) {
            openVideo(context, book)
            return
        }
        context.startActivity(BookInfoNavigator.intent(context, book).apply {
            putExtra("videoTitle", book.name)
        })
    }

    private fun openVideo(context: Context, book: SearchBook) {
        openActivity(context, book, VideoPlayerActivity::class.java, true)
    }

    private fun openActivity(
        context: Context,
        book: SearchBook,
        target: Class<*>,
        prepareInPlayer: Boolean
    ) {
        context.startActivity(Intent(context, target).apply {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
            putExtra("origin", book.origin)
            putExtra("originName", book.originName)
            putExtra(EXTRA_COVER_URL, book.coverUrl)
            putExtra("videoTitle", book.name)
            if (target == VideoPlayerActivity::class.java && prepareInPlayer) {
                putExtra("sourceKey", book.origin)
                putExtra("sourceType", SourceType.book)
                // Search/discovery results are temporary until the user explicitly confirms.
                putExtra("inBookshelf", false)
                putExtra(VideoPlayerActivity.EXTRA_PREPARE_BOOK_INFO, true)
            }
        })
    }

    fun isVideoResult(book: SearchBook, sourceTypeHint: Int? = null): Boolean {
        return book.type and BookType.video > 0 ||
                sourceTypeHint == BookSourceType.video ||
                appDb.bookSourceDao.getBookSource(book.origin)?.bookSourceType == BookSourceType.video
    }
}
