package com.shimmer.reader.ui.rss.article

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import com.bumptech.glide.request.RequestOptions
import com.shimmer.reader.R
import com.shimmer.reader.base.adapter.ItemViewHolder
import com.shimmer.reader.data.entities.RssArticle
import com.shimmer.reader.databinding.ItemRssArticle3Binding
import com.shimmer.reader.help.glide.ImageLoader
import com.shimmer.reader.help.glide.OkHttpModelLoader
import com.shimmer.reader.lib.theme.UiCorner
import com.shimmer.reader.lib.theme.primaryTextColor
import com.shimmer.reader.lib.theme.secondaryTextColor
import com.shimmer.reader.lib.theme.themeCardColorOrDefault
import com.shimmer.reader.ui.widget.WaterfallCardMetrics

class RssArticlesAdapter3(context: Context, callBack: CallBack) :
    BaseRssArticlesAdapter<ItemRssArticle3Binding>(context, callBack) {

    private val orientation = context.resources.configuration.orientation
    private val columnCount = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 3 else 2

    override fun getViewBinding(parent: ViewGroup): ItemRssArticle3Binding {
        return ItemRssArticle3Binding.inflate(inflater, parent, false).apply {
            val metrics = WaterfallCardMetrics.resolve(parent, columnCount)
            root.layoutParams = (root.layoutParams ?: ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )).apply {
                height = metrics.cardHeight
            }
            imageView.layoutParams = imageView.layoutParams.apply {
                height = metrics.coverHeight
            }
            root.setCardBackgroundColor(Color.TRANSPARENT)
            root.getChildAt(0)?.let { content ->
                content.layoutParams = content.layoutParams.apply {
                    height = metrics.cardHeight
                }
                content.background = UiCorner.panelRounded(
                    root.context,
                    root.context.themeCardColorOrDefault(),
                    UiCorner.panelRadius(root.context)
                )
            }
        }
    }

    @SuppressLint("CheckResult")
    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRssArticle3Binding,
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
            val radius = UiCorner.panelRadius(context)
            imageView.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            imageView.clipToOutline = true
            imageView.adjustViewBounds = false
            val imageUrl = item.image
            if (imageUrl.isNullOrEmpty()) {
                imageView.setImageResource(R.drawable.image_rss_article)
                return
            }
            ImageLoader.load(context, imageUrl)
                .apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, item.origin))
                .placeholder(R.drawable.transparent_placeholder)
                .centerCrop()
                .into(imageView)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemRssArticle3Binding) {
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                callBack.readRss(it)
            }
        }
    }
}
