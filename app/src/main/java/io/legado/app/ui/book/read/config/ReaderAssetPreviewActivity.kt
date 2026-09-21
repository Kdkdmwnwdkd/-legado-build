package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.text.format.Formatter
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityReaderAssetPreviewBinding
import io.legado.app.help.reader.ReaderAssetReferences
import io.legado.app.help.reader.ReaderAssets
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream

class ReaderAssetPreviewActivity : BaseActivity<ActivityReaderAssetPreviewBinding>() {
    override val binding by viewBinding(ActivityReaderAssetPreviewBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.root.applyUiBodyTypefaceDeep(uiTypeface())
        binding.preview.settings.apply {
            javaScriptEnabled = false; allowFileAccess = false; allowContentAccess = false; blockNetworkLoads = true
        }
        binding.preview.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val resource = if (request.method == "GET" || request.method == "HEAD") runCatching {
                    ReaderAssets.resource(request.url.toString(), request.method == "HEAD")
                }.getOrNull() else null
                return resource?.let { WebResourceResponse(it.mimeType, it.encoding, it.statusCode, it.reasonPhrase, it.headers, it.stream) }
                    ?: WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        lifecycleScope.launch {
            try {
                val asset = withContext(Dispatchers.IO) {
                    val id = intent.getStringExtra("assetId").orEmpty()
                    ReaderAssets.store.find(id)?.also { ReaderAssets.store.verifiedBytes(id) }
                        ?: error(getString(R.string.reader_assets_missing))
                }
                binding.titleBar.title = asset.name
                binding.tvInfo.text = listOf(asset.extension.uppercase(), Formatter.formatFileSize(this@ReaderAssetPreviewActivity, asset.size),
                    if (asset.kind == "image") "${asset.width} × ${asset.height}" else getString(R.string.reader_assets_fonts)).joinToString(" · ")
                val doc = Document.createShell("")
                doc.head().appendElement("meta").attr("name", "viewport").attr("content", "width=device-width,initial-scale=1")
                val family = ReaderAssetReferences.fontFamily(asset.id)
                val background = "#%06x".format(themeCardColorOrDefault() and 0xffffff)
                val foreground = "#%06x".format(primaryTextColor and 0xffffff)
                doc.head().appendElement("style").appendText(
                    "html,body{margin:0;min-height:100%;box-sizing:border-box;}body{padding:24px;background:$background;color:$foreground;}" +
                        "img{display:block;max-width:100%;max-height:80vh;margin:auto;object-fit:contain;}" +
                        ReaderAssetReferences.fontCss(family) + "p{font-family:'$family',serif;font-size:24px;line-height:1.8;white-space:pre-wrap;}")
                if (asset.kind == "image") doc.body().appendElement("img").attr("src", ReaderAssetReferences.url(asset.id)).attr("alt", asset.name)
                else doc.body().appendElement("p").text(getString(R.string.reader_assets_font_sample))
                binding.preview.loadDataWithBaseURL("https://reader-assets-preview.epub.local/", doc.outerHtml(), "text/html", "UTF-8", null)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                toastOnUi(error.localizedMessage)
                finish()
            }
        }
    }

    override fun onDestroy() {
        binding.preview.stopLoading()
        (binding.preview.parent as? ViewGroup)?.removeView(binding.preview)
        binding.preview.destroy()
        super.onDestroy()
    }
}
