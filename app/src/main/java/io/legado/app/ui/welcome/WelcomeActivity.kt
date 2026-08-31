package io.legado.app.ui.welcome

import android.content.Intent
import android.os.Bundle
import io.legado.app.base.BaseActivity
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.databinding.ActivityWelcomeBinding
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

open class WelcomeActivity : BaseActivity<ActivityWelcomeBinding>(imageBg = false) {

    override val binding by viewBinding(ActivityWelcomeBinding::inflate)
    private val welcomeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        if (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT != 0) {
            finish()
        } else {
            startMainActivityFast()
        }
    }

    /**
     * 先立刻跳 MainActivity（让书架界面抢先渲染），
     * lastReadBook 放后台线程异步查，查到后再跳阅读页，避免启动时DB读阻塞主线程。
     */
    private fun startMainActivityFast() {
        startActivity<MainActivity>()
        val defaultToRead = getPrefBoolean(PreferKey.defaultToRead, false)
        if (defaultToRead) {
            welcomeScope.launch {
                val lastRead = runCatching { appDb.bookDao.lastReadBook }.getOrNull()
                if (lastRead != null && !isFinishing && !isDestroyed) {
                    runOnUiThread {
                        startActivity<ReadBookActivity>()
                        finish()
                    }
                } else {
                    runOnUiThread { finish() }
                }
            }
        } else {
            finish()
        }
    }

}

class Launcher1 : WelcomeActivity()
class Launcher2 : WelcomeActivity()
class Launcher3 : WelcomeActivity()
class Launcher4 : WelcomeActivity()
class Launcher5 : WelcomeActivity()
class Launcher6 : WelcomeActivity()
class Launcher7 : WelcomeActivity()
