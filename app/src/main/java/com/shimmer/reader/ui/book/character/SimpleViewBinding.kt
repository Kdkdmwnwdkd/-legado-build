package com.shimmer.reader.ui.book.character

import android.view.View
import androidx.viewbinding.ViewBinding

internal class SimpleViewBinding(
    private val rootView: View
) : ViewBinding {
    override fun getRoot(): View = rootView
}
