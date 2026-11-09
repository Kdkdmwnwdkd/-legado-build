package com.shimmer.reader.ui.widget.text

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import com.shimmer.reader.R
import com.shimmer.reader.lib.theme.accentColor
import com.shimmer.reader.lib.theme.uiTypeface
import com.shimmer.reader.utils.getCompatColor

class AccentTextView(context: Context, attrs: AttributeSet?) :
    AppCompatTextView(context, attrs) {

    init {
        if (!isInEditMode) {
            setTextColor(context.accentColor)
        } else {
            setTextColor(context.getCompatColor(R.color.accent))
        }
        if (!isInEditMode) {
            typeface = context.uiTypeface()
        }
    }

}
