package com.shimmer.reader.ui.rss.favorites

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.shimmer.reader.R
import com.shimmer.reader.base.BaseDialogFragment
import com.shimmer.reader.data.entities.RssArticle
import com.shimmer.reader.data.entities.RssStar
import com.shimmer.reader.databinding.DialogRssFavoriteConfigBinding
import com.shimmer.reader.lib.theme.dialogSurfaceBackground
import com.shimmer.reader.lib.theme.primaryColor
import com.shimmer.reader.utils.setLayout
import com.shimmer.reader.utils.viewbindingdelegate.viewBinding

class RssFavoritesDialog() : BaseDialogFragment(R.layout.dialog_rss_favorite_config, true) {

    constructor(rssArticle: RssArticle) : this() {
        arguments = Bundle().apply {
            putString("title", rssArticle.title)
            putString("group", rssArticle.group)
        }
    }

    constructor(rssStar: RssStar) : this() {
        arguments = Bundle().apply {
            putString("title", rssStar.title)
            putString("group", rssStar.group)
        }
    }

    private val binding by viewBinding(DialogRssFavoriteConfigBinding::bind)

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.vwBg.background = requireContext().dialogSurfaceBackground
        binding.toolBar.setBackgroundColor(primaryColor)
        val arguments = arguments ?: let {
            dismiss()
            return
        }

        var title = arguments.getString("title")
        var group = arguments.getString("group")
        binding.run {
            editTitle.setText(title)
            editGroup.setText(group)
            tvCancel.setOnClickListener {
                dismiss()
            }
            tvOk.setOnClickListener {
                val editTitle = editTitle.text.toString()
                if (editTitle.isNotBlank()) {
                    title = editTitle
                }
                val editGroup = editGroup.text.toString()
                if (editGroup.isNotBlank()) {
                    group = editGroup
                }
                callback?.updateFavorite(title, group)
                dismiss()
            }
            tvFooterLeft.setOnClickListener {
                callback?.deleteFavorite()
                dismiss()
            }
        }
    }

    val callback get() = (parentFragment as? Callback) ?: (activity as? Callback)

    interface Callback {

        fun updateFavorite(title: String?, group: String?)

        fun deleteFavorite()

    }

}
