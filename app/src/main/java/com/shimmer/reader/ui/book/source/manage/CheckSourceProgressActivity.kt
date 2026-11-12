package com.shimmer.reader.ui.book.source.manage

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import com.shimmer.reader.base.BaseActivity
import com.shimmer.reader.model.CheckSource
import com.shimmer.reader.model.CheckRunStatus
import com.shimmer.reader.model.CheckSourceRunStore
import com.shimmer.reader.ui.book.character.SimpleViewBinding
import com.shimmer.reader.ui.book.source.manage.compose.CheckSourceProgressScreen
import com.shimmer.reader.utils.keepScreenOn
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 书源校验的实时进度与逐条结果。
 *
 * 校验本体仍然跑在 [com.shimmer.reader.service.CheckSourceService] 里，这个页面只订阅
 * [CheckSourceRunStore]，不参与取数，也不改任何判定。
 */
class CheckSourceProgressActivity : BaseActivity<ViewBinding>(
    fullScreen = false,
    imageBg = false
) {

    private lateinit var composeView: ComposeView

    override val binding: ViewBinding by lazy {
        composeView = ComposeView(this)
        SimpleViewBinding(composeView)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeView.setContent {
            val run by CheckSourceRunStore.state.collectAsState()
            CheckSourceProgressScreen(
                run = run,
                onBack = ::finish,
                onCancel = { CheckSource.stop(this) },
                onOpenManage = ::openManage
            )
        }
        // 校验要跑十几分钟，中途息屏会让后台任务被判成空闲，跑完之前先亮着
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CheckSourceRunStore.state.collect {
                    keepScreenOn(it.status == CheckRunStatus.RUNNING)
                }
            }
        }
    }

    /** 从通知直接进来时，返回键会掉到桌面，这里给个明确的去处。 */
    private fun openManage() {
        startActivity(Intent(this, BookSourceActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }
}
