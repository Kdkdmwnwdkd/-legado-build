package com.shimmer.reader.ui.config

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout.LayoutParams as FrameLayoutParams
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.appcompat.widget.Toolbar
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.shimmer.reader.R
import com.shimmer.reader.base.VMBaseActivity
import com.shimmer.reader.constant.EventBus
import com.shimmer.reader.ui.widget.compose.rememberAppSettingPalette
import com.shimmer.reader.utils.dpToPx
import com.shimmer.reader.utils.observeEvent
import com.shimmer.reader.utils.setOnApplyWindowInsetsListenerCompat
import androidx.viewbinding.ViewBinding

private const val CONFIG_MENU_HOST_WIDTH_DP = 128

class ConfigActivity : VMBaseActivity<ViewBinding, ConfigViewModel>() {

    private lateinit var titleComposeView: ComposeView
    private lateinit var menuToolbar: Toolbar
    private var titleText by mutableStateOf("")

    override val binding: ViewBinding by lazy {
        titleText = getString(R.string.setting)
        titleComposeView = ComposeView(this)
        menuToolbar = Toolbar(this).apply {
            title = ""
            subtitle = ""
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setContentInsetsAbsolute(0, 0)
            contentInsetStartWithNavigation = 0
            contentInsetEndWithActions = 0
        }
        val topBarHost = FrameLayout(this).apply {
            addView(
                titleComposeView,
                FrameLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                menuToolbar,
                FrameLayoutParams(
                    CONFIG_MENU_HOST_WIDTH_DP.dpToPx(),
                    56.dpToPx(),
                    Gravity.END or Gravity.BOTTOM
                )
            )
            setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
                val top = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                (menuToolbar.layoutParams as? FrameLayoutParams)?.let { params ->
                    if (params.topMargin != top) {
                        params.topMargin = top
                        menuToolbar.layoutParams = params
                    }
                }
                windowInsets
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(
                topBarHost,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                FrameLayout(this@ConfigActivity).apply {
                    id = R.id.configFrameLayout
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }
        object : ViewBinding {
            override fun getRoot(): View = root
        }
    }
    override val viewModel by viewModels<ConfigViewModel>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        setSupportActionBar(menuToolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        titleComposeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        titleComposeView.setContent {
            ConfigTopBar(
                title = titleText,
                onBack = ::supportFinishAfterTransition
            )
        }
        val page = intent.getStringExtra("configTag")?.let(ConfigPageRegistry::find)
        if (page == null) {
            finish()
            return
        }
        replaceFragment(page.tag, page.fragmentClass)
    }

    override fun setTitle(resId: Int) {
        super.setTitle(resId)
        titleText = getString(resId)
    }

    override fun setTitle(title: CharSequence?) {
        super.setTitle(title)
        titleText = title?.toString().orEmpty()
    }

    fun replaceFragment(configTag: String, fragmentClass: Class<out Fragment>) {
        intent.putExtra("configTag", configTag)
        val configFragment = supportFragmentManager.findFragmentByTag(configTag)
            ?: fragmentClass.getDeclaredConstructor().newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.configFrameLayout, configFragment, configTag)
            .commit()
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.RECREATE) {
            recreate()
        }
    }

}

@Composable
private fun ConfigTopBar(
    title: String,
    onBack: () -> Unit
) {
    val palette = rememberAppSettingPalette()
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = palette.bodyFontFamily)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = null,
                    tint = palette.primaryText
                )
            }
            Text(
                text = title,
                color = palette.primaryText,
                fontFamily = palette.titleFontFamily,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(CONFIG_MENU_HOST_WIDTH_DP.dp))
        }
    }
}
