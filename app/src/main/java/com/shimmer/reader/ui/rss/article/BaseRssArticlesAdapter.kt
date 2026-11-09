package com.shimmer.reader.ui.rss.article

import android.content.Context
import androidx.viewbinding.ViewBinding
import com.shimmer.reader.base.adapter.RecyclerAdapter
import com.shimmer.reader.data.entities.RssArticle


abstract class BaseRssArticlesAdapter<VB : ViewBinding>(context: Context, val callBack: CallBack) :
    RecyclerAdapter<RssArticle, VB>(context) {
    interface CallBack {
        val isGridLayout: Boolean
        fun readRss(rssArticle: RssArticle)
    }
}