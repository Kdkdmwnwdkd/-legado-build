package com.shimmer.reader.ui.widget.text

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import com.shimmer.reader.R
import com.shimmer.reader.lib.theme.Selector
import com.shimmer.reader.lib.theme.ThemeStore
import com.shimmer.reader.lib.theme.UiCorner
import com.shimmer.reader.lib.theme.uiTypeface
import com.shimmer.reader.utils.ColorUtils
import com.shimmer.reader.utils.dpToPx
import com.shimmer.reader.utils.getCompatColor

class AccentBgTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {

    private var radius = 0

    init {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.AccentBgTextView)
        radius = (typedArray.getDimensionPixelOffset(R.styleable.AccentBgTextView_radius, radius) *
            UiCorner.scale()).toInt()
        typedArray.recycle()
        if (!isInEditMode) {
            typeface = context.uiTypeface()
        }
        upBackground()
    }

    fun setRadius(radius: Int) {
        this.radius = radius.dpToPx()
        upBackground()
    }

    private fun upBackground() {
        val accentColor = if (isInEditMode) {
            context.getCompatColor(R.color.accent)
        } else {
            ThemeStore.accentColor(context)
        }
        background = Selector.shapeBuild()
            .setCornerRadius(radius)
            .setDefaultBgColor(accentColor)
            .setPressedBgColor(ColorUtils.darkenColor(accentColor))
            .create()
        setTextColor(
            if (ColorUtils.isColorLight(accentColor)) {
                Color.BLACK
            } else {
                Color.WHITE
            }
        )
    }
}
