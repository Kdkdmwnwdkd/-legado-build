package com.shimmer.reader.base

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.shimmer.reader.R
import com.shimmer.reader.help.config.AppConfig
import com.shimmer.reader.lib.theme.applyUiBodyTypefaceDeep
import com.shimmer.reader.lib.theme.uiTypeface
import com.shimmer.reader.lib.theme.dialogSurfaceBackground
import com.shimmer.reader.utils.dpToPx


abstract class BasePrefDialogFragment(
) : DialogFragment() {

    override fun onStart() {
        super.onStart()
        if (AppConfig.isEInkMode) {
            dialog?.window?.let {
                it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                val attr = it.attributes
                attr.dimAmount = 0.0f
                attr.windowAnimations = 0
                it.attributes = attr
                it.setBackgroundDrawableResource(R.color.transparent)
            }

            // 修改gravity的时机一般在子类的onStart方法中, 因此需要在onStart之后执行.
            lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) {
                    when (dialog?.window?.attributes?.gravity) {
                        Gravity.TOP -> view?.setBackgroundResource(R.drawable.bg_eink_border_bottom)
                        Gravity.BOTTOM -> view?.setBackgroundResource(R.drawable.bg_eink_border_top)
                        else -> {
                            val padding = 2.dpToPx();
                            view?.setPadding(padding, padding, padding, padding)
                            view?.setBackgroundResource(R.drawable.bg_eink_border_dialog)
                        }
                    }
                }
            })
        } else {
            dialog?.window?.let {
                val attr = it.attributes
                attr.windowAnimations = R.style.AnimDialogCenter
                it.attributes = attr
                it.setBackgroundDrawableResource(R.color.transparent)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.clipToOutline = true
        if (!AppConfig.isEInkMode && view.background == null) {
            view.background = requireContext().dialogSurfaceBackground
        }
        if (!AppConfig.isEInkMode) {
            view.applyUiBodyTypefaceDeep(requireContext().uiTypeface())
        }
    }
}
