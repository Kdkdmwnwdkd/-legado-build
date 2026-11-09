package com.shimmer.reader.ui.code

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.util.regex.RegexBackrefGrammar
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.EditorSearcher.SearchOptions
import com.shimmer.reader.R
import com.script.rhino.RhinoScriptEngine
import com.shimmer.reader.base.VMBaseActivity
import com.shimmer.reader.help.CacheManager
import com.shimmer.reader.constant.PreferKey
import com.shimmer.reader.databinding.ActivityCodeEditBinding
import com.shimmer.reader.help.config.AppConfig
import com.shimmer.reader.help.config.ThemeConfig
import com.shimmer.reader.lib.dialogs.SelectItem
import com.shimmer.reader.lib.dialogs.alert
import com.shimmer.reader.lib.theme.themeCardColorOrDefault
import com.shimmer.reader.ui.about.AppLogDialog
import com.shimmer.reader.ui.code.config.ChangeThemeDialog
import com.shimmer.reader.ui.code.config.SettingsDialog
import com.shimmer.reader.ui.widget.keyboard.KeyboardToolPop
import com.shimmer.reader.utils.imeHeight
import com.shimmer.reader.utils.putPrefBoolean
import com.shimmer.reader.utils.setOnApplyWindowInsetsListenerCompat
import com.shimmer.reader.utils.showDialogFragment
import com.shimmer.reader.utils.showHelp
import com.shimmer.reader.utils.viewbindingdelegate.viewBinding

class CodeEditActivity :
    VMBaseActivity<ActivityCodeEditBinding, CodeEditViewModel>(),
    KeyboardToolPop.CallBack, ChangeThemeDialog.CallBack, SettingsDialog.CallBack {
    companion object {
        const val EXTRA_RESULT_ACTION = "resultAction"
        const val EXTRA_CHECK_JAVASCRIPT_SYNTAX = "checkJavaScriptSyntax"
        const val EXTRA_SHOW_DEBUG_SOURCE = "showDebugSource"
        const val EXTRA_SHOW_LOGIN_SOURCE = "showLoginSource"
        const val RESULT_ACTION_DEBUG_SOURCE = "debugSource"
        const val RESULT_ACTION_LOGIN_SOURCE = "loginSource"

        private var isInitialized = false
        private var findText = ""
        private var replaceText = ""
        private var isRegex = true
    }
    override val binding by viewBinding(ActivityCodeEditBinding::inflate)
    override val viewModel by viewModels<CodeEditViewModel>()
    private val softKeyboardTool by lazy {
        KeyboardToolPop(this, lifecycleScope, binding.root, this)
    }
    private val editor: CodeEditor by lazy { binding.editText }
    private val editorSearcher: EditorSearcher by lazy { editor.searcher }
    private var searchOptions: SearchOptions? = null
    private var menuSaveBtn: MenuItem? = null
    private var menuDebugSource: MenuItem? = null
    private var menuLoginSource: MenuItem? = null
    // 从书源编辑页进入时，保存后可直接跳调试/登录
    private val showDebugSource get() = intent.getBooleanExtra(EXTRA_SHOW_DEBUG_SOURCE, false)
    private val showLoginSource get() = intent.getBooleanExtra(EXTRA_SHOW_LOGIN_SOURCE, false)

    private val isDark
        get() = AppConfig.editTemeAuto && ThemeConfig.isDarkTheme()
    private var themeIndex = -1

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        softKeyboardTool.attachToWindow(window)
        editor.colorScheme = TextMateColorScheme2.create(ThemeRegistry.getInstance()) //先设置颜色,避免一开始的白屏
        viewModel.initData(intent) {
            editor.apply {
                viewModel.title?.let {
                    binding.titleBar.title = it
                }
                // IME 组合态和提交态同时触发配对时会出现 `())` 这类重复闭合
                getProps().autoCompletionOnComposing = false
                nonPrintablePaintingFlags = AppConfig.editNonPrintable
                setEditorLanguage(viewModel.language)
                upEdit(AppConfig.editFontScale, null, AppConfig.editAutoWrap)
                setText(viewModel.initialText)
                editable = viewModel.writable
                menuSaveBtn?.isVisible = viewModel.writable
                requestFocus()
                postDelayed({
                    val pos = cursor.indexer.getCharPosition(viewModel.cursorPosition)
                    setSelection(pos.line, pos.column, true)
                }, 360) // 进行延时,确保加载渲染完成,从而确保光标能显示跳转到长文本最后
            }
        }
        initView()
    }

    private fun initView() {
        binding.searchGroup.setBackgroundColor(themeCardColorOrDefault())
        binding.root.setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
            softKeyboardTool.initialPadding = windowInsets.imeHeight
            windowInsets
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        editorSearcher.stopSearch()
        editor.release()
    }

    /**
     * 使用super.finish(),防止循环回调
     * */
    private fun save(check: Boolean, resultAction: String? = null) {
        if (!viewModel.writable) return super.finish()
        val text = editor.text.toString()
        val cursorPos = editor.cursor?.left ?: 0
        when {
            text == viewModel.initialText -> {
                if (cursorPos > 0 || resultAction != null) {
                    val result = Intent().apply {
                        putExtra("cursorPosition", cursorPos)
                        resultAction?.let { putExtra(EXTRA_RESULT_ACTION, it) }
                    }
                    setResult(RESULT_OK, result)
                }
                super.finish()
            }
            check -> {
                alert(R.string.exit) {
                    setMessage(R.string.exit_no_save)
                    positiveButton(R.string.yes)
                    negativeButton(R.string.no) {
                        if (cursorPos > 0) {
                            val result = Intent().apply {
                                putExtra("cursorPosition", cursorPos)
                            }
                            setResult(RESULT_OK, result)
                        }
                        super.finish()
                    }
                }
            }
            else -> {
                val syntaxError = checkJavaScriptSyntax(text)
                if (syntaxError != null) {
                    alert(R.string.js_syntax_error) {
                        setMessage(syntaxError)
                        positiveButton(R.string.continue_save) {
                            finishWithText(text, cursorPos, resultAction)
                        }
                        negativeButton(R.string.cancel)
                    }
                    return
                }
                finishWithText(text, cursorPos, resultAction)
            }
        }
    }

    /**
     * 只有从书源编辑页进来才校验，普通文本编辑不该被 JS 语法挡住
     */
    private fun checkJavaScriptSyntax(text: String): String? {
        if (!intent.getBooleanExtra(EXTRA_CHECK_JAVASCRIPT_SYNTAX, false)) return null
        if (text.isBlank()) return null
        return runCatching {
            RhinoScriptEngine.compile(text)
        }.exceptionOrNull()?.let {
            it.localizedMessage ?: it.toString()
        }
    }

    private fun finishWithText(text: String, cursorPos: Int, resultAction: String?) {
        val result = Intent().apply {
            // Keep large payloads out of Binder; some ROMs fail well under 1MB.
            val bytes = text.toByteArray(Charsets.UTF_8).size
            if (bytes > 100_000) {
                val key = "code_edit_result_" + System.nanoTime()
                CacheManager.putMemory(key, text)
                putExtra("cacheKey", key)
            } else {
                putExtra("text", text)
            }
            putExtra("cursorPosition", cursorPos)
            resultAction?.let { putExtra(EXTRA_RESULT_ACTION, it) }
        }
        setResult(RESULT_OK, result)
        super.finish()
    }

    override fun upEdit(fontSize: Int?, autoComplete: Boolean?, autoWarp: Boolean?, editNonPrintable: Int?) {
        if (fontSize != null) {
            editor.setTextSize(fontSize.toFloat())
        }
        if (autoComplete != null) {
            viewModel.language?.isAutoCompleteEnabled = autoComplete
            editor.setEditorLanguage(viewModel.language)
        }
        if (autoWarp != null) {
            editor.isWordwrap = autoWarp
        }
        if (editNonPrintable != null) {
            editor.nonPrintablePaintingFlags = editNonPrintable
        }
    }

    override fun initTheme() {
        super.initTheme()
        if (!isInitialized) {
            viewModel.initSora()
            isInitialized = true
        }
        val index = if (isDark) {
            AppConfig.editThemeDark
        } else {
            AppConfig.editTheme
        }
        upTheme(index)
        themeIndex = index
    }

    override fun upTheme(index: Int) {
        if (themeIndex != index) {
            viewModel.loadTextMateThemes(index)
            editor.setEditorLanguage(viewModel.language) //每次更改颜色后需要再执行一次语言设置,防止切换主题后高亮颜色不正确
            themeIndex = index
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.code_edit_activity, menu)
        menuDebugSource = menu.findItem(R.id.menu_debug_source).apply {
            isVisible = showDebugSource
        }
        menuLoginSource = menu.findItem(R.id.menu_login_source).apply {
            isVisible = showLoginSource
        }
        menuSaveBtn = menu.findItem(R.id.menu_save).apply {
            isVisible = viewModel.writable
        }
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_auto_wrap)?.isChecked = AppConfig.editAutoWrap
        return super.onPrepareOptionsMenu(menu)
    }

    private fun setSearchOptions() {
        searchOptions =  SearchOptions(
            if (isRegex) SearchOptions.TYPE_REGULAR_EXPRESSION else SearchOptions.TYPE_NORMAL,
            !isRegex,
            RegexBackrefGrammar.DEFAULT
        )
    }

    private fun search() {
        if (binding.searchGroup.isVisible) return
        binding.switchRegex.run {
            isChecked = isRegex
            setSearchOptions()
            setOnCheckedChangeListener { _, isChecked ->
                isRegex = isChecked
                setSearchOptions()
                searchTxt(binding.etFind.text.toString())
            }
        }
        val receiptSearch =
            editor.subscribeEvent(PublishSearchResultEvent::class.java) { event, _ ->
                if (event.editor == editor) {
                    updateSearchResults()
                }
            }
        val receiptChange = editor.subscribeEvent(SelectionChangeEvent::class.java) { event, _ ->
            if (event.cause == SelectionChangeEvent.CAUSE_SEARCH) {
                updateSearchResults()
            }
        }
        binding.searchGroup.visibility = View.VISIBLE
        binding.btnCloseFind.setOnClickListener {
            binding.searchGroup.visibility = View.GONE
            editorSearcher.stopSearch()
            receiptSearch.unsubscribe()
            receiptChange.unsubscribe()
            editor.requestFocus()
            editor.invalidate()
        }
        searchTxt(findText)
        binding.etFind.run {
            requestFocus()
            setText(findText)
            addTextChangedListener { text ->
                if (!text.isNullOrEmpty()) {
                    findText = text.toString()
                    searchTxt(findText)
                } else {
                    editorSearcher.stopSearch()
                    editor.invalidate()
                }
            }

        }
        binding.etReplace.run {
            setText(replaceText)
            addTextChangedListener { text ->
                if (!text.isNullOrEmpty()) {
                    replaceText = text.toString()
                }
            }
        }
        binding.btnPrevious.setOnClickListener {
            if (editorSearcher.hasQuery()) {
                editorSearcher.gotoPrevious()
            }
        }
        binding.btnNext.setOnClickListener {
            if (editorSearcher.hasQuery()) {
                editorSearcher.gotoNext()
            }
        }
        binding.btnReplace.setOnClickListener {
            if (binding.replaceGroup.isGone) {
                binding.replaceGroup.visibility = View.VISIBLE
                binding.btnReplaceAll.isEnabled = true
                binding.etReplace.requestFocus()
            } else {
                if (editorSearcher.hasQuery()) {
                    editorSearcher.replaceCurrentMatch(binding.etReplace.text.toString())
                }
            }
        }
        binding.btnCloseReplace.setOnClickListener {
            binding.replaceGroup.visibility = View.GONE
            binding.btnReplaceAll.isEnabled = false
            binding.etFind.requestFocus()
        }
        binding.btnReplaceAll.setOnClickListener {
            if (editorSearcher.hasQuery()) {
                editorSearcher.replaceAll(binding.etReplace.text.toString())
            }
        }
    }

    private fun searchTxt(txt: String) {
        if (txt.isNotEmpty()) {
            try {
                searchOptions?.let {
                    editorSearcher.search(txt, it)
                }
            } catch (_: java.util.regex.PatternSyntaxException) {
                // 忽略正则表达式语法错误
                editorSearcher.stopSearch()
                editor.invalidate()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateSearchResults() {
        if (editorSearcher.hasQuery()) {
            val totalResults = editorSearcher.matchedPositionCount
            val currentPosition = editorSearcher.currentMatchedPositionIndex + 1
            binding.tvSearchResult.text =
                "${if (currentPosition > 0) "$currentPosition/" else ""}$totalResults"
        }
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_search -> search()
            R.id.menu_save -> save(false)
            R.id.menu_debug_source -> save(false, RESULT_ACTION_DEBUG_SOURCE)
            R.id.menu_login_source -> save(false, RESULT_ACTION_LOGIN_SOURCE)
            R.id.menu_format_code -> viewModel.formatCode(editor)
            R.id.menu_change_theme -> showDialogFragment(ChangeThemeDialog())
            R.id.menu_config_settings -> showDialogFragment(SettingsDialog(this, this))
            R.id.menu_auto_wrap -> {
                item.isChecked = !AppConfig.editAutoWrap
                upEdit(autoWarp = !AppConfig.editAutoWrap)
                putPrefBoolean(PreferKey.editAutoWrap, !AppConfig.editAutoWrap)
            }
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun finish() {
        save(true)
    }

    override fun helpActions(): List<SelectItem<String>> {
        return arrayListOf(
            SelectItem(getString(R.string.source_edit_book_tutorial), "ruleHelp"),
            SelectItem(getString(R.string.source_edit_feed_tutorial), "rssRuleHelp"),
            SelectItem(getString(R.string.source_edit_js_tutorial), "jsHelp"),
            SelectItem(getString(R.string.source_edit_regex_tutorial), "regexHelp")
        )
    }

    override fun onHelpActionSelect(action: String) {
        when (action) {
            "ruleHelp" -> showHelp("ruleHelp")
            "rssRuleHelp" -> showHelp("rssRuleHelp")
            "jsHelp" -> showHelp("jsHelp")
            "regexHelp" -> showHelp("regexHelp")
        }
    }

    override fun sendText(text: String) {
        val view = window.decorView.findFocus()
        if (view is TextInputEditText) {
            var start = view.selectionStart
            var end = view.selectionEnd
            if (start > end) {
                val temp = start
                start = end
                end = temp
            }
            if (text.isNotEmpty()) {
                val edit = view.editableText//获取EditText的文字
                if (start < 0 || start >= edit.length) {
                    edit.append(text)
                } else {
                    edit.replace(start, end, text)//光标所在位置插入文字
                }
            }
        }
        else {
            editor.insertText(text, text.length)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onUndoClicked() {
        editor.undo()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onRedoClicked() {
        editor.redo()
    }
}
