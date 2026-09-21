package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import com.google.gson.Gson
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityHighlightRuleEditBinding
import io.legado.app.help.book.highlight.HighlightDocument
import io.legado.app.help.book.highlight.HighlightMatcher
import io.legado.app.help.book.highlight.HighlightRule
import io.legado.app.help.book.highlight.HighlightRules
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.reader.ReaderAssetReferences
import io.legado.app.help.reader.ReaderAssets
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.themeMutedColorOrDefault
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream

class HighlightRuleEditActivity : BaseActivity<ActivityHighlightRuleEditBinding>() {
    override val binding by viewBinding(ActivityHighlightRuleEditBinding::inflate)
    private var rule = HighlightRule()
    private var loaded = false
    private var saving = false
    private var selectingAsset = false
    private var previewJob: Job? = null
    private var assetLabelJob: Job? = null
    private var pendingFont: String? = null
    private var pendingImage: String? = null
    private val fontPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingFont = result.data?.getStringExtra("assetId")
            applyAssetSelection()
        }
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingImage = result.data?.getStringExtra("assetId")
            applyAssetSelection()
        }
    }
    private val editingId get() = intent.getStringExtra("id")
    private val types = listOf("textColor", "background", "underline", "doubleLine", "wavyLine", "strikethrough")
    private val typeLabels = listOf("文字上色", "背景上色", "下划线", "双下划线", "波浪线", "删除线")
    private val colors = listOf("accent", "red", "orange", "yellow", "green", "teal", "blue", "purple", "pink", "brown", "gray")
    private val colorLabels = listOf("阅读强调色", "红色", "橙色", "黄色", "绿色", "青色", "蓝色", "紫色", "粉色", "棕色", "灰色")

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        if (pendingFont == null) pendingFont = savedInstanceState?.getString("pendingFont")
        if (pendingImage == null) pendingImage = savedInstanceState?.getString("pendingImage")
        binding.root.applyUiBodyTypefaceDeep(uiTypeface())
        updateEnabled()
        listOf(binding.btnType, binding.btnColor, binding.btnPreview, binding.btnFont, binding.btnImage).forEach {
            it.background = UiCorner.actionSelector(themeCardColorOrDefault(), themeMutedColorOrDefault(), UiCorner.actionRadius(this))
            it.setTextColor(accentColor)
        }
        binding.btnType.setOnClickListener {
            if (loaded && !saving) showComposeChoiceListDialog(getString(R.string.highlight_rule_type), typeLabels) {
                rule = rule.copy(styleType = types[it])
                updateButtons()
            }
        }
        binding.btnColor.setOnClickListener {
            if (loaded && !saving) showComposeChoiceListDialog(getString(R.string.highlight_rule_color), colorLabels) {
                rule = rule.copy(styleColorType = colors[it])
                updateButtons()
            }
        }
        binding.btnPreview.setOnClickListener { preview() }
        binding.btnFont.setOnClickListener {
            if (loaded && !saving) fontPicker.launch(Intent(this, ReaderAssetManageActivity::class.java)
                .putExtra("pickKind", "font").putExtra("selectedId", ReaderAssetReferences.selectedFont(binding.etCss.text.toString())))
        }
        binding.btnImage.setOnClickListener {
            if (loaded && !saving) imagePicker.launch(Intent(this, ReaderAssetManageActivity::class.java)
                .putExtra("pickKind", "image").putExtra("selectedId", rule.asset))
        }
        binding.etCss.doAfterTextChanged { if (loaded) updateAssetLabels() }
        binding.preview.settings.apply {
            javaScriptEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
        }
        binding.preview.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val resource = runCatching {
                    if (request.method != "GET" && request.method != "HEAD") null
                    else if (request.url.host == "highlight-preview.epub.local")
                        HighlightRules.resource(request.url.path.orEmpty().removePrefix("/"), request.method == "HEAD")
                    else ReaderAssets.resource(request.url.toString(), request.method == "HEAD")
                }.getOrNull()
                return resource?.let { WebResourceResponse(it.mimeType, it.encoding, it.statusCode, it.reasonPhrase, it.headers, it.stream) }
                    ?: WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
            }
        }
        lifecycleScope.launch {
            try {
                val saved = savedInstanceState?.getString("draft")
                rule = if (saved != null) Gson().fromJson(saved, HighlightRule::class.java) else {
                    val id = editingId
                    if (id == null) HighlightRule() else withContext(Dispatchers.IO) {
                        HighlightRules.store.all().find { it.id == id }
                            ?: error("此高亮规则已被删除，请返回管理页刷新")
                    }
                }
                binding.run {
                    etName.setText(rule.name)
                    etGroup.setText(rule.groupName)
                    etKeyword.setText(rule.keyword)
                    etCss.setText(rule.styleCssText)
                    cbEnabled.isChecked = rule.enabled
                    cbGlobal.isChecked = rule.global
                    cbRegex.isChecked = rule.isRegex
                    cbMultiline.isChecked = rule.isMultiline
                    cbTitle.isChecked = rule.titleOnly
                    cbStyled.isChecked = rule.applyToStyledBooks
                    etSample.setText(savedInstanceState?.getString("sample") ?: getString(R.string.highlight_rule_sample_text))
                }
                loaded = true
                updateEnabled()
                updateButtons()
                applyAssetSelection()
                preview(showError = false)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                toastOnUi(error.localizedMessage)
            }
        }
    }

    private fun updateEnabled() {
        if (isDestroyed) return
        binding.run {
            listOf(etName, etGroup, etKeyword, etCss, etSample, cbEnabled, cbGlobal,
                cbRegex, cbMultiline, cbTitle, cbStyled, btnType, btnColor, btnPreview, btnFont, btnImage)
                .forEach { it.isEnabled = loaded && !saving && !selectingAsset }
        }
    }

    private fun updateButtons() {
        binding.btnType.text = getString(R.string.highlight_rule_option_label,
            getString(R.string.highlight_rule_type), typeLabels.getOrNull(types.indexOf(rule.styleType)) ?: rule.styleType)
        binding.btnColor.text = getString(R.string.highlight_rule_option_label,
            getString(R.string.highlight_rule_color), colorLabels.getOrNull(colors.indexOf(rule.styleColorType)) ?: rule.styleColorType)
        updateAssetLabels()
    }

    private fun updateAssetLabels() {
        assetLabelJob?.cancel()
        val css = binding.etCss.text.toString()
        val fontId = ReaderAssetReferences.selectedFont(css)
        val imageId = rule.asset
        assetLabelJob = lifecycleScope.launch {
            val names = withContext(Dispatchers.IO) {
                runCatching { fontId?.let { ReaderAssets.store.find(it)?.name } to imageId?.let { ReaderAssets.store.find(it)?.name } }
                    .getOrDefault(null to null)
            }
            val font = names.first ?: when {
                fontId != null -> getString(R.string.reader_assets_missing)
                Regex("(?i)font-family\\s*:").containsMatchIn(css) && !css.contains("reeden-font:", true) -> "自定义 CSS 字体"
                else -> getString(R.string.reader_assets_follow_font)
            }
            binding.btnFont.text = getString(R.string.reader_assets_picker_label, getString(R.string.reader_assets_font_label), font)
            binding.btnImage.text = getString(R.string.reader_assets_picker_label, getString(R.string.reader_assets_background_label),
                names.second ?: if (imageId == null) getString(R.string.reader_assets_no_image) else "原规则背景")
        }
    }

    private fun applyAssetSelection() {
        if (!loaded || saving || selectingAsset) return
        pendingFont?.let { id ->
            pendingFont = null
            binding.etCss.setText(ReaderAssetReferences.withFont(binding.etCss.text.toString(), id.ifEmpty { null }))
            updateAssetLabels(); preview(showError = false)
        }
        pendingImage?.let { id ->
            selectingAsset = true
            updateEnabled()
            lifecycleScope.launch {
                try {
                    val asset = withContext(Dispatchers.IO) { if (id.isEmpty()) null else
                        ReaderAssets.store.find(id)?.takeIf { it.kind == "image" } ?: error(getString(R.string.reader_assets_missing)) }
                    rule = rule.copy(asset = asset?.id, imageWidth = asset?.width ?: 0, imageHeight = asset?.height ?: 0)
                    pendingImage = null
                    updateAssetLabels()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    toastOnUi(error.localizedMessage)
                } finally {
                    selectingAsset = false
                    updateEnabled()
                    if (!isDestroyed) preview(showError = false)
                }
            }
        }
    }

    private fun draft(): HighlightRule = binding.run {
        rule.copy(name = etName.text.toString().trim(), groupName = etGroup.text.toString().trim(),
            keyword = etKeyword.text.toString(), styleCssText = etCss.text.toString(),
            styleMode = if (etCss.text.isNullOrBlank()) "" else "advanced",
            enabled = cbEnabled.isChecked, global = cbGlobal.isChecked,
            bookUrl = if (cbGlobal.isChecked) null else rule.bookUrl ?: intent.getStringExtra("bookUrl"),
            isRegex = cbRegex.isChecked, isMultiline = cbMultiline.isChecked,
            titleOnly = cbTitle.isChecked, applyToStyledBooks = cbStyled.isChecked)
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.action_save).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, 2, 1, R.string.highlight_rule_preview)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> save()
            2 -> preview()
            else -> return super.onCompatOptionsItemSelected(item)
        }
        return true
    }

    private fun save() {
        if (!loaded || saving || selectingAsset) return
        val value = draft()
        val invalid = HighlightMatcher.validationError(value)
        if (value.enabled && invalid != null) { toastOnUi(invalid); return }
        if (!value.global && value.bookUrl == null) { toastOnUi(R.string.highlight_rule_bind_hint); return }
        val warnings = value.importWarning.split("；").filter { it.startsWith("背景图片未导入") } +
            listOfNotNull(invalid?.let { "表达式不可用：" + it },
                if (value.styleCssText.contains("reeden-font:", true)) "未附带 Reeden 字体，使用当前阅读字体" else null)
        saving = true
        updateEnabled()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val updated = value.copy(importWarning = warnings.joinToString("；"))
                    val id = editingId
                    if (id == null) HighlightRules.store.put(updated)
                    else HighlightRules.store.update(id) { updated }
                }
                setResult(RESULT_OK)
                finish()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                toastOnUi(error.localizedMessage)
            } finally {
                saving = false
                updateEnabled()
            }
        }
    }

    private fun preview(showError: Boolean = true) {
        if (!loaded || saving || selectingAsset) return
        previewJob?.cancel()
        val value = draft().copy(enabled = true)
        HighlightMatcher.validationError(value)?.let {
            if (showError) toastOnUi(it)
            return
        }
        val palette = HighlightRules.palette()
        val alignment = if (ReadBookConfig.textFullJustify) "justify" else "start"
        val title = getString(R.string.highlight_rule_sample_title)
        val sample = binding.etSample.text.toString()
        previewJob = lifecycleScope.launch {
            try {
                val html = withContext(Dispatchers.Default) {
                    val document = Document.createShell("")
                    document.head().appendElement("meta").attr("charset", "UTF-8")
                    document.head().appendElement("meta").attr("name", "viewport").attr("content", "width=device-width,initial-scale=1")
                    document.head().appendElement("style").appendText(
                        "body{--legado-highlight-room-left:20px;--legado-highlight-room-right:20px;" +
                            "font:18px/1.8 serif;padding:12px;overflow-wrap:break-word;text-align:$alignment;" +
                            "color:${palette.text};background:${palette.background}}h2{font-size:1.15em}p{margin:.8em 0}")
                    document.body().appendElement("h2").text(title)
                    sample.lineSequence().forEach { document.body().appendElement("p").text(it) }
                    HighlightDocument.apply(document.outerHtml(), listOf(value), palette) {
                        "https://highlight-preview.epub.local/highlight-asset/" + it
                    }
                }
                binding.preview.loadDataWithBaseURL("https://highlight-preview.epub.local/", html, "text/html", "UTF-8", null)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (showError) toastOnUi(error.localizedMessage)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingFont?.let { outState.putString("pendingFont", it) }
        pendingImage?.let { outState.putString("pendingImage", it) }
        if (loaded) {
            outState.putString("draft", Gson().toJson(draft()))
            outState.putString("sample", binding.etSample.text.toString())
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        previewJob?.cancel()
        binding.preview.stopLoading()
        (binding.preview.parent as? ViewGroup)?.removeView(binding.preview)
        binding.preview.destroy()
        super.onDestroy()
    }
}
