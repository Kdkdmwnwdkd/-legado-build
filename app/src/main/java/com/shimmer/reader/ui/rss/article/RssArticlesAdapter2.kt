package com.shimmer.reader.ui.rss.article

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.shimmer.reader.R
import com.shimmer.reader.base.adapter.ItemViewHolder
import com.shimmer.reader.data.entities.RssArticle
import com.shimmer.reader.databinding.ItemRssArticle2Binding
import com.shimmer.reader.help.glide.ImageLoader
import com.shimmer.reader.help.glide.OkHttpModelLoader
import com.shimmer.reader.lib.theme.primaryTextColor
import com.shimmer.reader.lib.theme.secondaryTextColor
import com.shimmer.reader.utils.gone
import com.shimmer.reader.utils.visible


class RssArticlesAdapter2(context: Context, callBack: CallBack) :
    BaseRssArticlesAdapter<ItemRssArticle2Binding>(context, callBack) {

    override fun getViewBinding(parent: ViewGroup): ItemRssArticle2Binding {
        return ItemRssArticle2Binding.inflate(inflater, parent, false)
    }

    @SuppressLint("CheckResult")
    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRssArticle2Binding,
        item: RssArticle,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty()) {
            payloads.forEach { payload ->
                when (payload) {
                    "read" -> {
                        if (item.read) {
                            binding.tvTitle.setTextColor(context.secondaryTextColor)
                        } else {
                            binding.tvTitle.setTextColor(context.primaryTextColor)
                        }
                    }
                    "title" -> {
                        binding.tvTitle.text = item.title
                    }
                }
            }
            return
        }
        binding.run {
            tvTitle.text = item.title
            if (item.read) {
                tvTitle.setTextColor(context.secondaryTextColor)
            } else {
                tvTitle.setTextColor(context.primaryTextColor)
            }
            tvPubDate.text = item.pubDate
            if (item.image.isNullOrBlank() && !callBack.isGridLayout) {
                imageView.gone()
            } else {
                val options =
                    RequestOptions().set(OkHttpModelLoader.sourceOriginOption, item.origin)
                ImageLoader.load(context, item.image).apply(options).apply {
                    if (callBack.isGridLayout) {
                        placeholder(R.drawable.transparent_placeholder)
                    } else {
                        addListener(object : RequestListener<Drawable> {
                            override fun onLoadFailed(
                                e: GlideException?,
                                model: Any?,
                                target: Target<Drawable>,
                                isFirstResource: Boolean
                            ): Boolean {
                                imageView.gone()
                                return false
                            }

                            override fun onResourceReady(
                                resource: Drawable,
                                model: Any,
                                target: Target<Drawable>?,
                                dataSource: DataSource,
                                isFirstResource: Boolean
                            ): Boolean {
                                imageView.visible()
                                return false
                            }

                        })
                    }
                }.into(imageView)
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemRssArticle2Binding) {
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                callBack.readRss(it)
            }
        }
    }

}
