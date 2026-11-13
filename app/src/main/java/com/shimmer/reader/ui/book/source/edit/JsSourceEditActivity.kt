package com.shimmer.reader.ui.book.source.edit

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStateAtLeast
import com.script.rhino.RhinoScriptEngine
import com.shimmer.reader.R
import com.shimmer.reader.base.BaseActivity
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.BookSource
import com.shimmer.reader.databinding.ActivityJsSourceEditBinding
import com.shimmer.reader.help.CacheManager
import com.shimmer.reader.model.jsSource.JsSourceUpsert
import com.shimmer.reader.ui.book.source.debug.BookSourceDebugActivity
import com.shimmer.reader.ui.code.CodeEditActivity
import com.shimmer.reader.ui.login.SourceLoginActivity
import com.shimmer.reader.utils.StartActivityContract
import com.shimmer.reader.utils.toastOnUi
import com.shimmer.reader.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * JS 单文件书源的编辑器。
 *
 * 本身不画界面，作用是把「打开编辑器 → 保存 → 调试 / 登录 → 回到编辑器」串成一条链，
 * 进程被回收后也能靠 stage 恢复到正确的一步。
 */
class JsSourceEditActivity : BaseActivity<ActivityJsSourceEditBinding>(imageBg = false) {

    companion object {
        private const val STATE_OPENED_SOURCE_URL = "openedSourceUrl"
        private const val STATE_PENDING_TEXT = "pendingText"
        private const val STATE_STAGE = "stage"
        const val EXTRA_SOURCE_URL = "sourceUrl"

        // 大文本走内存缓存，避免 Intent 超限
        private const val INLINE_TEXT_LIMIT = 100_000
    }

    override val binding by viewBinding(ActivityJsSourceEditBinding::inflate)

    private var openedSourceUrl: String? = null
    private var pendingText: String? = null
    private var stage = JsSourceEditStage.READY

    private val debugResult = registerForActivityResult(
        StartActivityContract(BookSourceDebugActivity::class.java)
    ) {
        stage = stage.afterDebugResult()
        pendingText?.let(::openEditor) ?: super.finish()
    }

    private val editorResult = registerForActivityResult(
        StartActivityContract(CodeEditActivity::class.java)
    ) { result ->
        val text = result.data?.readEditedText()
        if (result.resultCode != Activity.RESULT_OK || text == null) {
            stage = JsSourceEditStage.READY
            super.finish()
            return@registerForActivityResult
        }
        pendingText = text
        val action = result.data?.getStringExtra(CodeEditActivity.EXTRA_RESULT_ACTION)
        val debugRequested = action == CodeEditActivity.RESULT_ACTION_DEBUG_SOURCE
        val loginRequested = action == CodeEditActivity.RESULT_ACTION_LOGIN_SOURCE
        stage = stageForEditorResult(debugRequested, loginRequested)
        when {
            debugRequested -> saveForDebug(text)
            loginRequested -> saveForLogin(text)
            else -> saveSource(text)
        }
    }

    private val loginResult = registerForActivityResult(
        StartActivityContract(SourceLoginActivity::class.java)
    ) {
        stage = stage.afterLoginResult()
        pendingText?.let(::openEditor) ?: super.finish()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        openedSourceUrl = savedInstanceState?.getString(STATE_OPENED_SOURCE_URL)
            ?: intent.getStringExtra(EXTRA_SOURCE_URL)
        pendingText = savedInstanceState?.getString(STATE_PENDING_TEXT)
        stage = savedInstanceState?.getString(STATE_STAGE)
            ?.let { savedStage ->
                runCatching { JsSourceEditStage.valueOf(savedStage) }.getOrNull()
            }
            ?: JsSourceEditStage.READY
        lifecycleScope.launch {
            val text = pendingText ?: withContext(Dispatchers.IO) {
                openedSourceUrl?.let { appDb.bookSourceDao.getBookSource(it)?.mainJs }
                    ?: runCatching {
                        assets.open("js_source_template.js").bufferedReader().use { it.readText() }
                    }.getOrElse { "" }
            }
            pendingText = text
            when (stage.restoreAction()) {
                JsSourceEditRestoreAction.OPEN_EDITOR -> openEditor(text)
                JsSourceEditRestoreAction.SAVE_AND_FINISH -> saveSource(text)
                JsSourceEditRestoreAction.SAVE_FOR_DEBUG -> saveForDebug(text)
                JsSourceEditRestoreAction.SAVE_FOR_LOGIN -> saveForLogin(text)
                JsSourceEditRestoreAction.LAUNCH_DEBUG -> launchDebugWhenResumed()
                JsSourceEditRestoreAction.LAUNCH_LOGIN -> launchLoginWhenResumed()
                JsSourceEditRestoreAction.AWAIT_RESULT -> Unit
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_OPENED_SOURCE_URL, openedSourceUrl)
        outState.putString(STATE_PENDING_TEXT, pendingText)
        outState.putString(STATE_STAGE, stage.name)
    }

    private fun openEditor(text: String) {
        if (stage == JsSourceEditStage.EDITOR_OPEN || isFinishing) return
        stage = JsSourceEditStage.EDITOR_OPEN
        editorResult.launch {
            putExtra("title", getString(R.string.js_source_edit))
            putExtra("languageName", "source.js")
            if (text.toByteArray(Charsets.UTF_8).size > INLINE_TEXT_LIMIT) {
                val key = "js_source_edit_" + System.nanoTime()
                CacheManager.putMemory(key, text)
                putExtra("cacheKey", key)
                // 大文本走缓存时编辑器默认只读，这里显式打开编辑
                putExtra("writable", true)
            } else {
                putExtra("text", text)
            }
            putExtra(CodeEditActivity.EXTRA_CHECK_JAVASCRIPT_SYNTAX, true)
            putExtra(CodeEditActivity.EXTRA_SHOW_DEBUG_SOURCE, true)
            putExtra(CodeEditActivity.EXTRA_SHOW_LOGIN_SOURCE, true)
        }
    }

    private fun saveForDebug(text: String) {
        saveSource(text, showSuccessToast = false, finishAfterSave = false) {
            launchDebugWhenResumed()
        }
    }

    private fun launchDebugWhenResumed() {
        val sourceUrl = openedSourceUrl ?: run {
            val text = pendingText ?: return super.finish()
            stage = JsSourceEditStage.SAVING_FOR_DEBUG
            saveForDebug(text)
            return
        }
        lifecycleScope.launch {
            lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) {
                if (stage != JsSourceEditStage.DEBUG_READY) return@withStateAtLeast
                stage = JsSourceEditStage.DEBUG_OPEN
                debugResult.launch {
                    putExtra("key", sourceUrl)
                }
            }
        }
    }

    private fun saveForLogin(text: String) {
        saveSource(text, showSuccessToast = false, finishAfterSave = false) { source ->
            if (source.hasLogin()) {
                launchLoginWhenResumed()
            } else {
                stage = JsSourceEditStage.READY
                toastOnUi(R.string.source_no_login)
                pendingText?.let(::openEditor) ?: super.finish()
            }
        }
    }

    private fun launchLoginWhenResumed() {
        val sourceUrl = openedSourceUrl ?: run {
            val text = pendingText ?: return super.finish()
            stage = JsSourceEditStage.SAVING_FOR_LOGIN
            saveForLogin(text)
            return
        }
        lifecycleScope.launch {
            lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) {
                if (stage != JsSourceEditStage.LOGIN_READY) return@withStateAtLeast
                stage = JsSourceEditStage.LOGIN_OPEN
                loginResult.launch {
                    putExtra("type", "bookSource")
                    putExtra("key", sourceUrl)
                }
            }
        }
    }

    private fun saveSource(
        text: String,
        showSuccessToast: Boolean = true,
        finishAfterSave: Boolean = true,
        onSuccess: ((BookSource) -> Unit)? = null,
    ) {
        lifecycleScope.launch {
            try {
                val source = JsSourceUpsert.save(text, openedSourceUrl)
                openedSourceUrl = source.bookSourceUrl
                stage = stage.afterSuccessfulSave()
                pendingText = source.mainJs ?: text
                if (showSuccessToast) {
                    toastOnUi(R.string.success)
                }
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra("origin", source.bookSourceUrl)
                )
                onSuccess?.invoke(source)
                if (finishAfterSave) {
                    super.finish()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                stage = JsSourceEditStage.READY
                toastOnUi(error.localizedMessage)
                openEditor(text)
            }
        }
    }

    /**
     * 编辑器可能把大文本放在内存缓存里，读回时要兼容两种方式
     */
    private fun Intent.readEditedText(): String? {
        getStringExtra("text")?.let { return it }
        val key = getStringExtra("cacheKey") ?: return null
        return CacheManager.getFromMemory(key) as? String
    }
}
