package com.shimmer.reader.ui.main.explore

import androidx.recyclerview.widget.DiffUtil
import com.shimmer.reader.data.entities.BookSourcePart


class ExploreDiffItemCallBack : DiffUtil.ItemCallback<BookSourcePart>() {

    override fun areItemsTheSame(oldItem: BookSourcePart, newItem: BookSourcePart): Boolean {
        return oldItem.bookSourceUrl == newItem.bookSourceUrl
    }

    override fun areContentsTheSame(oldItem: BookSourcePart, newItem: BookSourcePart): Boolean {
        return oldItem.bookSourceName == newItem.bookSourceName
    }

}