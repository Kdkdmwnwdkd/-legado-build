package io.legado.app.lib.theme.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.legado.app.databinding.ViewNavigationBadgeBinding
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.transparentNavBar
import io.legado.app.ui.widget.text.BadgeView
import io.legado.app.lib.theme.elevation
import androidx.core.graphics.drawable.toDrawable

class ThemeBottomNavigationVIew(context: Context, attrs: AttributeSet) :
    BottomNavigationView(context, attrs) {

    init {
        val transparentNavBar = context.transparentNavBar
        if (transparentNavBar) {
            setBackgroundColor(Color.TRANSPARENT)
        } else {
            setBackgroundColor(Color.TRANSPARENT)
            elevation = context.elevation
        }
        val colorStateList = createThemeColorStateList()
        itemIconTintList = colorStateList
        itemTextColor = colorStateList
        itemRippleColor = null
        isItemHorizontalTranslationEnabled = false
        setItemActiveIndicatorEnabled(false)
        setItemActiveIndicatorColor(ColorStateList.valueOf(Color.TRANSPARENT))
        setItemActiveIndicatorWidth(0)
        setItemActiveIndicatorHeight(0)
        setItemActiveIndicatorMarginHorizontal(0)
        itemBackground = Color.TRANSPARENT.toDrawable()

        ViewCompat.setOnApplyWindowInsetsListener(this, null)
    }

    fun createThemeColorStateList(): ColorStateList {
        val selectedColor = context.accentColor
        val textColor = context.secondaryTextColor
        return Selector.colorBuild()
            .setDefaultColor(textColor)
            .setSelectedColor(selectedColor)
            .create()
    }

    fun restoreThemeIconTint() {
        val colorStateList = createThemeColorStateList()
        itemIconTintList = colorStateList
        itemTextColor = colorStateList
    }

    fun addBadgeView(index: Int): BadgeView {
        //获取底部菜单view
        val menuView = getChildAt(0) as ViewGroup
        //获取第index个itemView
        val itemView = menuView.getChildAt(index) as ViewGroup
        if (itemView.layoutParams is FrameLayout.LayoutParams) {
            (itemView.layoutParams as FrameLayout.LayoutParams).apply {
                marginStart = 2
                marginEnd = 2
            }
        }
        val badgeBinding = ViewNavigationBadgeBinding.inflate(LayoutInflater.from(context))
        itemView.addView(badgeBinding.root)
        return badgeBinding.viewBadge
    }

}
