package com.shimmer.reader.ui.main.rss

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.bumptech.glide.request.RequestOptions
import com.shimmer.reader.R
import com.shimmer.reader.base.adapter.ItemViewHolder
import com.shimmer.reader.base.adapter.RecyclerAdapter
import com.shimmer.reader.data.entities.RssSource
import com.shimmer.reader.databinding.ItemRssBinding
import com.shimmer.reader.help.glide.ImageLoader
import com.shimmer.reader.help.glide.OkHttpModelLoader
import com.shimmer.reader.lib.theme.applyUiBodyTypefaceDeep
import com.shimmer.reader.lib.theme.uiTypeface
import com.shimmer.reader.ui.widget.ModernActionPopup
import splitties.views.onLongClick

class RssAdapter(
    context: Context,
    private val fragment: Fragment,
    private val callBack: CallBack,
    private val lifecycle: Lifecycle
) : RecyclerAdapter<RssSource, ItemRssBinding>(context) {

    private var modernMenuPopup: ModernActionPopup.Handle? = null

    override fun getViewBinding(parent: ViewGroup): ItemRssBinding {
        return ItemRssBinding.inflate(inflater, parent, false).apply {
            root.applyUiBodyTypefaceDeep(context.uiTypeface())
        }
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRssBinding,
        item: RssSource,
        payloads: MutableList<Any>
    ) {
        binding.apply {
            tvName.text = item.sourceName
            val options = RequestOptions()
                .set(OkHttpModelLoader.sourceOriginOption, item.sourceUrl)
            ImageLoader.load(fragment, lifecycle, item.sourceIcon)
                .apply(options)
                .centerCrop()
                .placeholder(R.drawable.image_rss)
                .error(R.drawable.image_rss)
                .into(ivIcon)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemRssBinding) {
        binding.apply {
            root.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let {
                    callBack.openRss(it)
                }
            }
            root.onLongClick {
                getItemByLayoutPosition(holder.layoutPosition)?.let {
                    showMenu(ivIcon, it)
                }
            }
        }
    }

    private fun showMenu(view: View, rssSource: RssSource) {
        modernMenuPopup = ModernActionPopup.showFromMenu(
            view,
            R.menu.rss_main_item,
            modernMenuPopup,
            prepare = {
                findItem(R.id.menu_login).isVisible = !rssSource.loginUrl.isNullOrBlank()
            }
        ) {
            when (it.itemId) {
                R.id.menu_edit -> callBack.edit(rssSource)
                R.id.menu_top -> callBack.toTop(rssSource)
                R.id.menu_login -> callBack.login(rssSource)
                R.id.menu_del -> callBack.del(rssSource)
                R.id.menu_disable -> callBack.disable(rssSource)
            }
            true
        }
    }

    interface CallBack {
        fun openRss(rssSource: RssSource)
        fun edit(rssSource: RssSource)
        fun toTop(rssSource: RssSource)
        fun login(rssSource: RssSource)
        fun del(rssSource: RssSource)
        fun disable(rssSource: RssSource)
    }
}
