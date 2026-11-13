package com.shimmer.reader.ui.book.explore

import android.content.Context
import com.shimmer.reader.R
import com.shimmer.reader.data.entities.SearchBook
import com.shimmer.reader.utils.BookIntroUtils

interface ExploreShowBookCallback {
    fun isInBookshelf(book: SearchBook): Boolean

    fun showBookInfo(book: SearchBook)
}

internal fun SearchBook.exploreListIntro(context: Context): String {
    return BookIntroUtils.listIntro(intro)
        ?: context.getString(R.string.intro_show_null)
}
