package com.shimmer.reader.ui.about

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import com.shimmer.reader.R
import com.shimmer.reader.base.BaseActivity
import com.shimmer.reader.databinding.ActivityAboutBinding
import com.shimmer.reader.lib.theme.UiCorner
import com.shimmer.reader.lib.theme.themeCardColorOrDefault
import com.shimmer.reader.utils.openUrl
import com.shimmer.reader.utils.share
import com.shimmer.reader.utils.viewbindingdelegate.viewBinding


class AboutActivity : BaseActivity<ActivityAboutBinding>() {

    override val binding by viewBinding(ActivityAboutBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.llAbout.background = UiCorner.opaqueRounded(
            themeCardColorOrDefault(),
            UiCorner.panelRadius(this)
        )
        val fTag = "aboutFragment"
        var aboutFragment = supportFragmentManager.findFragmentByTag(fTag)
        if (aboutFragment == null) aboutFragment = AboutFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fl_fragment, aboutFragment, fTag)
            .commit()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.about, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_scoring -> openUrl("market://details?id=$packageName")
            R.id.menu_share_it -> share(
                getString(R.string.app_share_description_sigma),
                getString(R.string.app_name)
            )
        }
        return super.onCompatOptionsItemSelected(item)
    }

}
