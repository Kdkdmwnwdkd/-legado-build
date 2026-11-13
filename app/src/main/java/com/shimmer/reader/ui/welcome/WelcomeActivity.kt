package com.shimmer.reader.ui.welcome

import android.content.Intent
import android.os.Bundle
import android.view.View
import com.shimmer.reader.R
import com.shimmer.reader.base.BaseActivity
import com.shimmer.reader.constant.PreferKey
import com.shimmer.reader.data.appDb
import com.shimmer.reader.databinding.ActivityWelcomeBinding
import com.shimmer.reader.lib.theme.backgroundColor
import com.shimmer.reader.ui.book.read.ReadBookActivity
import com.shimmer.reader.ui.main.MainActivity
import com.shimmer.reader.utils.fullScreen
import com.shimmer.reader.utils.getPrefBoolean
import com.shimmer.reader.utils.setStatusBarColorAuto
import com.shimmer.reader.utils.startActivity
import com.shimmer.reader.utils.viewbindingdelegate.viewBinding

open class WelcomeActivity : BaseActivity<ActivityWelcomeBinding>(imageBg = false) {

    override val binding by viewBinding(ActivityWelcomeBinding::inflate)

    override fun initTheme() {
        setTheme(R.style.AppTheme_Welcome)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        if (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT != 0) {
            // 避免从桌面启动程序后，会重新实例化入口类的activity
            finish()
        } else {
            startMainActivity()
        }
        binding.tvLegado.visibility = View.GONE
        binding.ivBook.visibility = View.GONE
        binding.tvGzh.visibility = View.GONE
    }

    override fun setupSystemBar() {
        fullScreen()
        setStatusBarColorAuto(backgroundColor, true, fullScreen)
        upNavigationBarColor()
    }

    override fun upBackgroundImage() {
        // Keep the launcher hand-off visually neutral. The welcome activity is
        // still used for default-to-read routing, but it must not flash the old
        // branded splash artwork before MainActivity is ready.
        window.decorView.setBackgroundColor(backgroundColor)
    }

    private fun startMainActivity() {
        startActivity<MainActivity>()
        if (getPrefBoolean(PreferKey.defaultToRead) && appDb.bookDao.lastReadBook != null) {
            startActivity<ReadBookActivity>()
        }
        finish()
    }

}

class Launcher1 : WelcomeActivity()
class Launcher2 : WelcomeActivity()
class Launcher3 : WelcomeActivity()
class Launcher4 : WelcomeActivity()
class Launcher5 : WelcomeActivity()
class Launcher6 : WelcomeActivity()
class Launcher7 : WelcomeActivity()
