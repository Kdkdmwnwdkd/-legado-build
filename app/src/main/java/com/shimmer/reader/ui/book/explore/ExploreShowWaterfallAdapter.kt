package com.shimmer.reader.ui.book.explore

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.shimmer.reader.R
import com.shimmer.reader.base.adapter.ItemViewHolder
import com.shimmer.reader.base.adapter.RecyclerAdapter
import com.shimmer.reader.data.entities.SearchBook
import com.shimmer.reader.databinding.ItemSearchWaterfallBinding
import com.shimmer.reader.help.config.AppConfig
import com.shimmer.reader.lib.theme.UiCorner
import com.shimmer.reader.lib.theme.applyUiBodyTypefaceDeep
import com.shimmer.reader.lib.theme.themeCardColorOrDefault
import com.shimmer.reader.lib.theme.uiTypeface
import com.shimmer.reader.ui.widget.WaterfallCardMetrics
import com.shimmer.reader.ui.widget.image.CoverImageView
import com.shimmer.reader.utils.gone
import com.shimmer.reader.utils.visible

class ExploreShowWaterfallAdapter(
    context: Context,
    private val callBack: ExploreShowBookCallback,
    private val columns: Int
) : RecyclerAdapter<SearchBook, ItemSearchWaterfallBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemSearchWaterfallBinding {
        return ItemSearchWaterfallBinding.inflate(inflater, parent, false).apply {
            root.applyUiBodyTypefaceDeep(context.uiTypeface())
            val metrics = WaterfallCardMetrics.resolve(parent, columns)
            root.layoutParams = (root.layoutParams ?: ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )).apply {
                height = metrics.cardHeight
            }
            ivCover.layoutParams = ivCover.layoutParams.apply {
                height = metrics.coverHeight
            }
            root.background = UiCorner.panelRounded(
                root.context,
                root.context.themeCardColorOrDefault(),
                UiCorner.panelRadius(root.context)
            )
        }
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemSearchWaterfallBinding,
        item: SearchBook,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty()) {
            payloads.forEach { payload ->
                val bundle = payload as? Bundle ?: return@forEach
                if (bundle.containsKey("isInBookshelf")) {
                    bindKindLabels(binding, item)
                }
            }
            return
        }
        binding.run {
            tvName.text = item.name
            tvAuthor.text = context.getString(R.string.author_show, item.author)
            if (item.latestChapterTitle.isNullOrEmpty()) {
                tvLasted.gone()
            } else {
                tvLasted.text = context.getString(R.string.lasted_show, item.latestChapterTitle)
                tvLasted.visible()
            }
            tvIntroduce.text = item.exploreListIntro(context)
            bindKindLabels(this, item)
            ivCover.setCoverStyle(CoverImageView.CoverStyle.GRID)
            ivCover.load(item, AppConfig.loadCoverOnlyWifi)
        }
    }

    private fun bindKindLabels(binding: ItemSearchWaterfallBinding, item: SearchBook) {
        val labels = buildList {
            if (callBack.isInBookshelf(item)) add(binding.root.context.getString(R.string.in_bookshelf))
            addAll(item.getKindList())
        }.take(4)
        if (labels.isEmpty()) {
            binding.llKind.gone()
        } else {
            binding.llKind.visible()
            binding.llKind.setLabels(labels)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemSearchWaterfallBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.bindingAdapterPosition - getHeaderCount())?.let {
                callBack.showBookInfo(it)
            }
        }
    }

    override fun onViewAttachedToWindow(holder: ItemViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder.itemViewType < 0 || holder.itemViewType >= TYPE_FOOTER_VIEW) {
            (holder.itemView.layoutParams as? StaggeredGridLayoutManager.LayoutParams)?.isFullSpan = true
        }
    }
}

