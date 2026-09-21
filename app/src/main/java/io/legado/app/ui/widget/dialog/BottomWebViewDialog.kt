package io.legado.app.ui.widget.dialog

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.databinding.DialogWebViewBinding
import io.legado.app.help.book.ParagraphRuleJsExtensions
import io.legado.app.help.WebCacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.JS_INJECTION
import io.legado.app.help.webView.WebJsExtensions.Companion.basicJs
import io.legado.app.help.webView.WebJsExtensions.Companion.nameBasic
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.help.webView.WebViewPool
import io.legado.app.help.webView.WebViewRequestLifecycle
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.utils.invisible
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.setLayout
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible
import kotlinx.coroutines.launch
import androidx.core.view.size
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.imageHttpClient
import io.legado.app.help.http.readingHttpClient as okHttpClient
import io.legado.app.help.http.text
import io.legado.app.lib.theme.themeColorOrNull
import io.legado.app.help.webView.WebJsExtensions.Companion.JS_URL
import io.legado.app.help.webView.WebJsExtensions.Companion.nameUrl
import io.legado.app.help.webView.WebViewPool.BLANK_HTML
import io.legado.app.help.webView.WebViewPool.DATA_HTML
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.selector
import io.legado.app.model.Download
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.get
import io.legado.app.utils.writeBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import java.net.URLDecoder
import java.util.Date
import androidx.core.graphics.createBitmap

class BottomWebViewDialog() : BottomSheetDialogFragment(R.layout.dialog_web_view), WebJsExtensions.Callback {

    private companion object {
        const val PARAGRAPH_RULE_SOURCE_PREFIX = "paragraph_rule_"
    }

    constructor(
        sourceKey: String,
        bookType: Int,
        url: String,
        html: String? = null,
        preloadJs: String? = null,
        config: String? = null,
        webViewSession: CommentWebViewSession? = null,
        onDismiss: (() -> Unit)? = null
    ) : this() {
        this.webViewSession = webViewSession
        this.onDismissAction = onDismiss
        arguments = Bundle().apply {
            putString("sourceKey", sourceKey)
            putInt("bookType", bookType)
            putString("url", url)
            putString("html", html)
            putString("preloadJs", preloadJs)
            putString("config", config)
            putBoolean("useCommentWebViewSession", webViewSession != null)
        }
    }

    private var currentBinding: DialogWebViewBinding? = null
    private val binding: DialogWebViewBinding
        get() = checkNotNull(currentBinding) { "Comment dialog view is not available" }
    private var cachedBottomSheet: View? = null
    private var cachedBehavior: BottomSheetBehavior<View>? = null
    private val bottomSheet: View?
        get() = cachedBottomSheet ?: dialog
            ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.also { cachedBottomSheet = it }
    private val behavior: BottomSheetBehavior<View>?
        get() = cachedBehavior ?: bottomSheet
            ?.let { sheet -> BottomSheetBehavior.from(sheet) }
            ?.also { cachedBehavior = it }
    private val displayMetrics by lazy { resources.displayMetrics }
    private var pendingImageSave: Pair<WebViewRequestLifecycle, WebViewRequestLifecycle.Page>? = null
    private val selectImageDir = registerForActivityResult(HandleFileContract()) {
        val pending = pendingImageSave ?: return@registerForActivityResult
        pendingImageSave = null
        if (!ownsPage(pending.first, pending.second)) return@registerForActivityResult
        it.uri?.let { uri ->
            ACache.get().put(imagePathKey, uri.toString())
            saveImage(it.value, uri)
        }
    }
    private lateinit var pooledWebView: PooledWebView
    private lateinit var currentWebView: WebView
    @Volatile
    private var requestLifecycle: WebViewRequestLifecycle? = null
    private var ownsPooledWebView = false
    private var webViewSession: CommentWebViewSession? = null
    private var onDismissAction: (() -> Unit)? = null
    private var dismissActionNotified = false
    private var source: BaseSource? = null
    private var preloadJs: String? = null
    private var isFullScreen = false
    private var customWebViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originOrientation: Int? = null
    private var needClearHistory = true
    private var pendingConfig: Config? = null
    private var pendingConfigFirst = false

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (webViewSession == null && arguments?.getBoolean("useCommentWebViewSession") == true) {
            webViewSession = CommentWebViewSession.shared
        }
    }

    private fun ownsLease(lease: WebViewRequestLifecycle): Boolean =
        requestLifecycle === lease && lease.isActive && ownsPooledWebView &&
            pooledWebView.isInUse && !pooledWebView.isDestroyed

    private fun ownsPage(lease: WebViewRequestLifecycle, page: WebViewRequestLifecycle.Page): Boolean =
        ownsLease(lease) && page.isCurrent

    private fun closeViewRequests() {
        requestLifecycle?.close()
        requestLifecycle = null
    }

    @Suppress("DEPRECATION")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.let { window ->
            window.decorView.systemUiVisibility = activity?.window?.decorView?.systemUiVisibility ?: 0
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val lease = requestLifecycle ?: return
        // Dispatch like View.post, but cancel the work with this view's lease.
        lease.scope.launch(Dispatchers.Main) {
            if (ownsLease(lease)) applyPendingConfig()
        }
    }

    override fun show(manager: FragmentManager, tag: String?) {
        kotlin.runCatching {
            manager.beginTransaction().remove(this).commit()
            super.show(manager, tag)
        }.onFailure {
            AppLog.put("显示对话框失败 tag:$tag", it)
            notifyDismissAction()
        }
    }

    private fun submitConfig(config: Config, first: Boolean = false) {
        pendingConfig = config
        pendingConfigFirst = pendingConfigFirst || first
        applyPendingConfig()
    }

    private fun applyPendingConfig() {
        val config = pendingConfig ?: return
        if (bottomSheet == null) return
        setConfig(config, pendingConfigFirst)
        pendingConfigFirst = false
    }

    private fun setConfig(config: Config, first: Boolean = false) {
        val lease = requestLifecycle?.takeIf(::ownsLease) ?: return
        if (!isAdded || context == null) {
            return
        }
        behavior?.let { behavior ->
            config.state?.let { behavior.state = it }
            config.peekHeight?.let { behavior.peekHeight = it }
            config.isHideable?.let { behavior.isHideable = it }
            config.skipCollapsed?.let { behavior.skipCollapsed = it }
            config.setHalfExpandedRatio?.let { behavior.setHalfExpandedRatio(it) }
            config.setExpandedOffset?.let { behavior.setExpandedOffset(it) }
            config.setFitToContents?.let { behavior.setFitToContents(it) }
            config.isDraggable?.let { behavior.isDraggable = it }
            config.isDraggableOnNestedScroll?.let { behavior.isDraggableOnNestedScroll = it }
            config.significantVelocityThreshold?.let { behavior.significantVelocityThreshold = it }
            config.hideFriction?.let { behavior.hideFriction = it }
            config.maxWidth?.let { behavior.maxWidth = it }
            config.maxHeight?.let { behavior.maxHeight = it }
            config.isGestureInsetBottomIgnored?.let { behavior.isGestureInsetBottomIgnored = it }
            config.setUpdateImportantForAccessibilityOnSiblings?.let {
                behavior.setUpdateImportantForAccessibilityOnSiblings(it)
            }
        }

        config.expandedCornersRadius?.let {
            val radius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, it, displayMetrics
            )
            applySheetCorners(radius)
        }

        dialog?.let { dialog ->
            config.backgroundDimAmount?.let { amount ->
                dialog.window?.setDimAmount(amount)
            }
            config.shouldDimBackground?.let { shouldDim ->
                if (!shouldDim) {
                    dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                }
            }
            config.dismissOnTouchOutside?.let { touchOutside ->
                isCancelable = touchOutside
            }
            config.hardwareAccelerated?.let { hwAccel ->
                if (hwAccel) {
                    dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
                }
            }
        }

        currentWebView.let { webView ->
            config.webViewInitialScale?.let { scale ->
                webView.settings.apply {
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    textZoom = scale
                }
            }
            config.webViewCacheMode?.let { cacheMode ->
                webView.settings.cacheMode = cacheMode
            }
            config.isNestedScrollingEnabled?.let { enabled ->
                webView.isNestedScrollingEnabled = enabled
            }
        }

        bottomSheet?.let { sheet ->
            val params = sheet.layoutParams
            var hasChanged = false
            config.widthPercentage?.let { percentage ->
                if (percentage in 0.0..1.0) {
                    val width = (displayMetrics.widthPixels * percentage).toInt()
                    params.width = width
                    hasChanged = true
                }
            }

            val dialogHeight = config.dialogHeight ?: if (first) -1 else null
            dialogHeight?.let { height ->
                params.height = height
                hasChanged = true
            }
            config.heightPercentage?.let { percentage ->
                if (percentage in 0.0..1.0) {
                    val height = (displayMetrics.heightPixels * percentage).toInt()
                    params.height = height
                    // 同时更新peekHeight和最大高度
                    if (config.peekHeight == null) {
                        behavior?.peekHeight = height
                    }
                    if (config.maxHeight == null) {
                        behavior?.maxHeight = height
                    }
                    hasChanged = true
                }
            }
            if (hasChanged) {
                sheet.layoutParams = params
            }
        }

        config.responsiveBreakpoint?.let { breakpoint ->
            val screenWidth = displayMetrics.widthPixels
            if (screenWidth < breakpoint) {
                // 移动端布局（小屏幕）设置
                behavior?.peekHeight = config.peekHeight ?: 300
                config.widthPercentage?.let { percentage ->
                    if (percentage > 0.8f) {
                        // 小屏幕上最大宽度限制
                        val maxWidth = (screenWidth * 0.9).toInt()
                        behavior?.maxWidth = maxWidth
                    }
                }
            } else {
                // 平板/大屏幕布局设置
                behavior?.peekHeight = config.peekHeight ?: 400
                config.widthPercentage?.let { percentage ->
                    if (percentage < 0.6f) {
                        // 大屏幕上居中显示
                        bottomSheet?.layoutParams?.width =
                            (screenWidth * percentage).toInt()
                        (bottomSheet?.layoutParams as? FrameLayout.LayoutParams)?.gravity =
                            Gravity.CENTER_HORIZONTAL
                    }
                }
            }
        }

        val scrollNoDraggable = config.scrollNoDraggable ?: if (first) true else null
        scrollNoDraggable?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (it) {
                    currentWebView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                        if (ownsLease(lease)) behavior?.isDraggable = scrollY == 0
                    }
                } else {
                    currentWebView.setOnScrollChangeListener(null)
                }
            }
        }

        val longClickSaveImg = config.longClickSaveImg ?: if (first) true else null
        longClickSaveImg?.let {
            if (it) {
                setLongClickSaveImg()
            } else {
                currentWebView.setOnLongClickListener(null)
            }
        }
    }

    private fun applySheetCorners(radius: Float) {
        val hasRadius = radius > 0f
        val surfaceColor = requireContext().themeColorOrNull(PreferKey.themeCardColor)
            ?: ContextCompat.getColor(requireContext(), R.color.dialog_surface)
        bottomSheet?.let { sheet ->
            sheet.backgroundTintList = null
            sheet.background = if (hasRadius) topRoundDrawable(Color.TRANSPARENT, radius) else null
            sheet.clipToOutline = hasRadius
            setTopRoundOutline(sheet, radius)
        }
        binding.nativeSheetSurface.background = if (hasRadius) {
            topRoundDrawable(surfaceColor, radius)
        } else {
            topRoundDrawable(surfaceColor, 0f)
        }
        listOf(binding.nativeSheetSurface, binding.webViewContainer, binding.customWebView).forEach { view ->
            view.clipToPadding = false
            view.clipToOutline = hasRadius
            setTopRoundOutline(view, radius)
        }
        currentWebView.setBackgroundColor(Color.TRANSPARENT)
        currentWebView.clipToOutline = false
        currentWebView.outlineProvider = null
        currentWebView.setLayerType(View.LAYER_TYPE_NONE, null)
        logConfigApply(radius)
    }

    private fun resetWebViewPresentationState() {
        currentWebView.run {
            setLayerType(View.LAYER_TYPE_NONE, null)
            outlineProvider = null
            clipToOutline = false
            setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun topRoundDrawable(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadii = floatArrayOf(
                radius, radius,
                radius, radius,
                0f, 0f,
                0f, 0f
            )
        }
    }

    private fun setTopRoundOutline(view: View, radius: Float) {
        if (radius <= 0f) {
            view.outlineProvider = null
            return
        }
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height + radius.toInt(), radius)
            }
        }
    }

    private fun logConfigApply(radius: Float) {
        AppLog.putDebug(
            "BottomWebViewDialog configApply " +
                "radius=$radius " +
                "sdk=${Build.VERSION.SDK_INT} " +
                "manufacturer=${Build.MANUFACTURER} " +
                "brand=${Build.BRAND} " +
                "sheetAttached=${bottomSheet != null} " +
                "webAttached=${currentWebView.parent != null} " +
                "webLayer=${currentWebView.layerType}"
        )
    }

    private fun setLongClickSaveImg() {
        val lease = requestLifecycle?.takeIf(::ownsLease) ?: return
        currentWebView.setOnLongClickListener {
            val page = lease.currentPage() ?: return@setOnLongClickListener false
            if (!ownsPage(lease, page)) return@setOnLongClickListener false
            val hitTestResult = currentWebView.hitTestResult
            if (hitTestResult.type == WebView.HitTestResult.IMAGE_TYPE ||
                hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                hitTestResult.extra?.let { webPic ->
                    requireContext().selector(
                        arrayListOf(
                            SelectItem(getString(R.string.action_save), "save"),
                            SelectItem(getString(R.string.select_folder), "selectFolder")
                        )
                    ) { _, charSequence, _ ->
                        if (ownsPage(lease, page)) {
                            when (charSequence.value) {
                                "save" -> saveImage(webPic)
                                "selectFolder" -> selectSaveFolder(null)
                            }
                        }
                    }
                    return@setOnLongClickListener true
                }
            }
            return@setOnLongClickListener false
        }
        currentWebView.setDownloadListener { url, _, contentDisposition, _, _ ->
            val page = lease.currentPage() ?: return@setDownloadListener
            if (!ownsPage(lease, page)) return@setDownloadListener
            var fileName = URLUtil.guessFileName(url, contentDisposition, null)
            fileName = URLDecoder.decode(fileName, "UTF-8")
            currentWebView.longSnackbar(fileName, getString(R.string.action_download)) {
                if (ownsPage(lease, page)) Download.start(requireContext(), url, fileName)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        currentBinding = DialogWebViewBinding.bind(view)
        // A Fragment can recreate its view without another onAttach. Each view
        // must acquire its own lease, and invalidate it before returning to a pool.
        pooledWebView = webViewSession?.acquire(requireContext()) ?: WebViewPool.acquire(requireContext())
        currentWebView = pooledWebView.realWebView
        ownsPooledWebView = true
        val lease = WebViewRequestLifecycle(viewLifecycleOwner.lifecycleScope)
        requestLifecycle = lease
        source = null
        preloadJs = null
        needClearHistory = true
        view.setBackgroundColor(0)
        binding.webViewContainer.addView(currentWebView)
        val args = arguments?.let(::Bundle)
        val initialPage = lease.currentPage() ?: return
        initialPage.scope.launch(Dispatchers.Main) {
            if (args == null) {
                dismiss()
                return@launch
            }
            val sourceKey = args.getString("sourceKey") ?: return@launch
            val url = args.getString("url") ?: return@launch
            try {
                args.getString("config")?.let { json ->
                    try {
                        GSON.fromJsonObject<Config>(json).getOrThrow().let { config ->
                            submitConfig(config, true)
                        }
                        true
                    } catch (e: Exception) {
                        AppLog.put("config err", e)
                        null
                    }
                } ?: run {
                    bottomSheet?.let { sheet ->
                        val layoutParams = sheet.layoutParams
                        layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                        sheet.layoutParams = layoutParams
                    }
                    setLongClickSaveImg()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        currentWebView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                            if (ownsLease(lease)) behavior?.isDraggable = scrollY == 0
                        }
                    }
                }
                val loadedSource = withContext(IO) {
                    if (sourceKey.startsWith(PARAGRAPH_RULE_SOURCE_PREFIX)) {
                        val ruleId = sourceKey.removePrefix(PARAGRAPH_RULE_SOURCE_PREFIX).toLongOrNull()
                        ruleId?.let { appDb.paragraphRuleDao.get(it) }?.let(::ParagraphRuleJsExtensions)
                    } else {
                        appDb.bookSourceDao.getBookSource(sourceKey)
                    }
                }
                if (!ownsPage(lease, initialPage)) return@launch
                if (loadedSource == null) {
                    activity?.toastOnUi(
                        if (sourceKey.startsWith(PARAGRAPH_RULE_SOURCE_PREFIX)) "no find paragraphRule"
                        else "no find bookSource"
                    )
                    dismiss()
                    return@launch
                }
                source = loadedSource
                val requestedPreloadJs = args.getString("preloadJs")
                preloadJs = requestedPreloadJs
                val argHtml = args.getString("html")
                val bookType = args.getInt("bookType", 0)
                val document = withContext(IO) {
                    val analyzeUrl = AnalyzeUrl(url, source = loadedSource, coroutineContext = coroutineContext)
                    val spliceHtml = if (shouldLoadUrlDirectly(url, argHtml, requestedPreloadJs)) {
                        null
                    } else {
                        val html = argHtml ?: analyzeUrl.getStrResponseAwait().body
                        if (html.isNullOrEmpty()) throw NoStackTraceException("html is NullOrEmpty")
                        if (requestedPreloadJs.isNullOrEmpty()) {
                            html
                        } else {
                            val headIndex = html.indexOf("<head", ignoreCase = true)
                            val headEnd = if (headIndex >= 0) html.indexOf('>', headIndex) else -1
                            if (headEnd >= 0) StringBuilder(html).insert(headEnd + 1, JS_URL).toString()
                            else JS_URL + html
                        }
                    }
                    Triple(analyzeUrl.url, analyzeUrl.headerMap, spliceHtml)
                }
                if (!ownsPage(lease, initialPage)) return@launch
                val html = document.third
                if (html == null) {
                    initWebViewForUrl(document.first, document.second, bookType, lease)
                } else {
                    initWebView(document.first, html, document.second, bookType, lease)
                }
                currentWebView.clearHistory()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!ownsPage(lease, initialPage)) return@launch
                currentWebView.webViewClient = CustomWebViewClient(lease)
                currentWebView.loadDataWithBaseURL(url, error.stackTraceToString(), "text/html", "utf-8", url)
                currentWebView.clearHistory()
            }
        }
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                return@setOnKeyListener handleBackPressed()
            }
            false
        }
        registerBackPressedCallbacks()
    }

    private fun registerBackPressedCallbacks() {
        (dialog as? ComponentDialog)?.onBackPressedDispatcher?.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBackPressed()
                }
            }
        )
        activity?.onBackPressedDispatcher?.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBackPressed()
                }
            }
        )
    }

    private fun handleBackPressed(): Boolean {
        if (requestLifecycle?.let(::ownsLease) != true) return true
        if (binding.customWebView.size > 0) { //网页全屏
            customWebViewCallback?.onCustomViewHidden()
            return true
        }
        if (currentWebView.canGoBack()) {
            val list = currentWebView.copyBackForwardList()
            val size = list.size
            if (size == 1) {
                dismiss()
                return true
            }
            val currentIndex = list.currentIndex
            val currentItem = list.currentItem
            val currentUrl = currentItem?.originalUrl ?: BLANK_HTML
            val currentTitle = currentItem?.title
            var steps = 1
            for (i in currentIndex - 1 downTo 0) {
                val item = list.getItemAtIndex(i)
                val itemUrl = item.originalUrl
                if (itemUrl == BLANK_HTML) {
                    dismiss()
                    return true
                }
                if (itemUrl != currentUrl || currentTitle != item.title) {
                    break
                }
                if (currentUrl == DATA_HTML) {
                    break
                }
                steps++
            }
            if (steps == size) {
                dismiss()
                return true
            }
            currentWebView.goBackOrForward(-steps)
            return true
        }
        dismiss()
        return true
    }

    private fun shouldLoadUrlDirectly(rawUrl: String, html: String?, preloadJs: String?): Boolean {
        if (webViewSession == null || html != null || !preloadJs.isNullOrEmpty()) return false
        val url = rawUrl.trim()
        return URLUtil.isNetworkUrl(url) &&
            !url.contains(",{", ignoreCase = false) &&
            !url.contains("{{", ignoreCase = false) &&
            !url.contains("}}", ignoreCase = false) &&
            !url.contains("@js", ignoreCase = true) &&
            !url.contains("<js", ignoreCase = true)
    }

    private fun initWebViewForUrl(
        url: String,
        headerMap: HashMap<String, String>,
        bookType: Int,
        lease: WebViewRequestLifecycle
    ) {
        prepareWebView(headerMap, bookType, lease)
        currentWebView.loadUrl(url, webViewExtraHeaders(headerMap))
    }

    private fun initWebView(
        url: String,
        html: String,
        headerMap: HashMap<String, String>,
        bookType: Int,
        lease: WebViewRequestLifecycle
    ) {
        prepareWebView(headerMap, bookType, lease)
        currentWebView.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
    }

    private fun prepareWebView(
        headerMap: HashMap<String, String>,
        bookType: Int,
        lease: WebViewRequestLifecycle
    ) {
        currentWebView.webChromeClient = CustomWebChromeClient(lease)
        currentWebView.addJavascriptInterface(JSInterface(this, lease), nameBasic)
        currentWebView.webViewClient = CustomWebViewClient(lease)
        currentWebView.settings.userAgentString = headerMap.get(AppConst.UA_NAME, true)
        source?.let { source ->
            (activity as? AppCompatActivity)?.let { currentActivity ->
                val webJsExtensions =
                    WebJsExtensions(
                        source, currentActivity, currentWebView, bookType,
                        callback = this, requestLifecycle = lease
                    )
                currentWebView.addJavascriptInterface(webJsExtensions, nameJava)
            }
            currentWebView.addJavascriptInterface(source, nameSource)
            currentWebView.addJavascriptInterface(WebCacheManager, nameCache)
        }
    }

    private fun webViewExtraHeaders(headerMap: HashMap<String, String>): Map<String, String> {
        return HashMap(headerMap).apply {
            remove(AppConst.UA_NAME)
            remove("User-Agent")
            remove("user-agent")
        }
    }


    private fun saveImage(webPic: String) {
        val path = ACache.get().getAsString(imagePathKey)
        if (path.isNullOrEmpty()) {
            selectSaveFolder(webPic)
        } else {
            saveImage(webPic, path.toUri())
        }
    }

    private fun selectSaveFolder(webPic: String?) {
        val lease = requestLifecycle ?: return
        val page = lease.currentPage() ?: return
        if (!ownsPage(lease, page)) return
        val default = arrayListOf<SelectItem<Int>>()
        val path = ACache.get().getAsString(imagePathKey)
        if (!path.isNullOrEmpty()) {
            default.add(SelectItem(path, -1))
        }
        pendingImageSave = lease to page
        selectImageDir.launch {
            otherActions = default
            value = webPic
        }
    }

    private fun saveImage(webPic: String?, uri: Uri) {
        webPic ?: return
        val lease = requestLifecycle ?: return
        val page = lease.currentPage() ?: return
        if (!ownsPage(lease, page)) return
        val context = requireContext()
        Coroutine.async(page.scope) {
            val fileName = "${AppConst.fileNameFormat.format(Date(System.currentTimeMillis()))}.jpg"
            val byteArray = webData2bitmap(webPic) ?: throw NoStackTraceException("NULL")
            uri.writeBytes(context, fileName, byteArray)
        }.onError {
            if (!ownsPage(lease, page)) return@onError
            ACache.get().remove(imagePathKey)
            context.toastOnUi("保存图片失败:${it.localizedMessage}")
        }.onSuccess {
            if (ownsPage(lease, page)) context.toastOnUi("保存成功")
        }
    }

    private suspend fun webData2bitmap(data: String): ByteArray? {
        return if (URLUtil.isValidUrl(data)) {
            imageHttpClient.newCallResponseBody {
                url(data)
            }.bytes()
        } else {
            Base64.decode(data.split(",").toTypedArray()[1], Base64.DEFAULT)
        }
    }

    private fun notifyDismissAction() {
        if (dismissActionNotified) return
        dismissActionNotified = true
        onDismissAction?.invoke()
        onDismissAction = null
    }

    override fun onDestroyView() {
        closeViewRequests()
        customWebViewCallback?.onCustomViewHidden()
        customWebViewCallback = null
        isFullScreen = false
        binding.customWebView.removeAllViews()
        dialog?.keepScreenOn(false)
        if (ownsPooledWebView) {
            resetWebViewPresentationState()
            ownsPooledWebView = false
            webViewSession?.detachForReuse(pooledWebView) ?: WebViewPool.release(pooledWebView)
        }
        notifyDismissAction()
        originOrientation?.let {
            activity?.requestedOrientation = it
        }
        originOrientation = null
        cachedBottomSheet = null
        cachedBehavior = null
        pendingConfig = null
        pendingConfigFirst = false
        pendingImageSave = null
        source = null
        preloadJs = null
        currentBinding = null
        super.onDestroyView()
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (dialog === this.dialog) {
            closeViewRequests()
            notifyDismissAction()
        }
        super.onDismiss(dialog)
    }

    override fun upConfig(config: String) {
        val lease = requestLifecycle ?: return
        val page = lease.currentPage() ?: return
        page.scope.launch(Dispatchers.Main) {
            if (!ownsPage(lease, page)) return@launch
            try {
                GSON.fromJsonObject<Config>(config).getOrThrow().let { config ->
                    submitConfig(config)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.put("config err", error)
            }
        }
    }

    @Suppress("unused")
    private class JSInterface(
        dialog: BottomWebViewDialog,
        private val lease: WebViewRequestLifecycle
    ) {
        private val dialogRef: WeakReference<BottomWebViewDialog> = WeakReference(dialog)

        @JavascriptInterface
        fun lockOrientation(orientation: String) {
            val fra = dialogRef.get() ?: return
            val page = lease.currentPage() ?: return
            page.scope.launch(Dispatchers.Main) {
                if (!fra.ownsPage(lease, page)) return@launch
                val ctx = fra.activity ?: return@launch
                if (fra.isFullScreen && fra.dialog?.isShowing == true) {
                    ctx.requestedOrientation = when (orientation) {
                        "portrait", "portrait-primary" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        "portrait-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                        "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE //横屏且受重力控制正反
                        "landscape-primary" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE //正向横屏
                        "landscape-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE //反向横屏
                        "any", "unspecified" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
            }
        }

        @JavascriptInterface
        fun onCloseRequested() {
            val fra = dialogRef.get() ?: return
            val page = lease.currentPage() ?: return
            page.scope.launch(Dispatchers.Main) {
                if (fra.ownsPage(lease, page) && fra.dialog?.isShowing == true) {
                    fra.dismiss()
                }
            }
        }
    }

    @Keep
    data class Config(
        // 底部弹窗状态相关配置
        var state: Int? = null, // 设置弹窗的初始状态： 3 STATE_EXPANDED(展开) 、 4 STATE_COLLAPSED(折叠) 、 5 STATE_HIDDEN(隐藏) 、 6 STATE_HALF_EXPANDED(半展开)
        var peekHeight: Int? = null, // 设置折叠状态下的高度（像素）
        var isHideable: Boolean? = null, // 设置弹窗是否可以通过向下拖拽隐藏
        var skipCollapsed: Boolean? = null, // 设置是否跳过折叠状态，下滑对话框时直接关闭
        var setHalfExpandedRatio: Float? = null, // 设置半展开状态的比例（0.0-1.0），相对于父容器的高度
        var setExpandedOffset: Int? = null, // 设置完全展开状态时顶部距离父容器顶部的偏移量（像素）
        var setFitToContents: Boolean? = null, // 设置展开时的高度计算方式true（默认值）自适应内容、false 固定比例

        // 交互行为相关配置
        var isDraggable: Boolean? = null, // 设置弹窗是否可以通过拖拽交互
        var isDraggableOnNestedScroll: Boolean? = null, //是否允许webview滚动控制折叠展开  默认值为true允许
        var significantVelocityThreshold: Int? = null, // 设置判定为快速滑动的速度阈值（像素/秒）
        var hideFriction: Float? = null, // 设置隐藏时的摩擦系数，影响拖拽回弹效果（0.0-1.0）

        // 视觉和布局相关配置
        var maxWidth: Int? = null, // 设置弹窗的最大宽度（像素）
        var maxHeight: Int? = null, // 设置弹窗的最大高度（像素）
        var isGestureInsetBottomIgnored: Boolean? = null, // 是否忽略系统手势区域（如下方的导航条）
        var expandedCornersRadius: Float? = null, // 展开状态的圆角半径

        // 无障碍功能相关配置
        var setUpdateImportantForAccessibilityOnSiblings: Boolean? = null, // 设置是否在弹窗展开时更新兄弟视图的无障碍重要性

        // 背景相关配置
        var backgroundDimAmount: Float? = null, // 背景遮罩透明度（0.0-1.0）
        var shouldDimBackground: Boolean? = null, // 是否显示背景遮罩

        // WebView特定配置
        var webViewInitialScale: Int? = null, // WebView初始缩放比例 默认100
        var webViewCacheMode: Int? = null, // WebView缓存模式： -1 LOAD_DEFAULT 、 1 LOAD_NO_CACHE 、 2 LOAD_CACHE_ONLY 、 3 LOAD_CACHE_ELSE_NETWORK

        // 生命周期配置
        var dismissOnTouchOutside: Boolean? = null, // 点击外部是否关闭弹窗

        // 性能优化配置
        var hardwareAccelerated: Boolean? = null, // 是否启用硬件加速
        var isNestedScrollingEnabled: Boolean? = null, // 是否启用嵌套滚动

        // 响应式设计相关配置
        var widthPercentage: Float? = null, // 弹窗宽度占屏幕宽度的百分比（0.0-1.0）
        var heightPercentage: Float? = null, // 弹窗高度占屏幕高度的百分比（0.0-1.0）
        var responsiveBreakpoint: Int? = null, // 响应式断点（像素），小于此宽度时使用移动端布局
        var dialogHeight: Int? = null, //弹窗高度（像素），默认为-1（父容器最大高度）、-2（最大内容高度）

        //阅读功能自定义配置
        var longClickSaveImg : Boolean? = null, //是否启用长按图片保存功能，默认启用
        var scrollNoDraggable : Boolean? = null, //网页有滚动时禁止对话框拖拽，默认启用
    )

    private inner class CustomWebChromeClient(
        private val lease: WebViewRequestLifecycle
    ) : WebChromeClient() {
        override fun getDefaultVideoPoster(): Bitmap {
            return super.getDefaultVideoPoster() ?: createBitmap(100, 100)
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (!ownsLease(lease)) {
                callback?.onCustomViewHidden()
                return
            }
            originOrientation = activity?.requestedOrientation //先记录原始方向，避免被js控制的影响
            isFullScreen = true
            binding.webViewContainer.invisible()
            binding.customWebView.addView(view)
            customWebViewCallback = callback
            dialog?.keepScreenOn(true)
            behavior?.state = BottomSheetBehavior.STATE_EXPANDED
        }

        override fun onHideCustomView() {
            if (!ownsLease(lease)) return
            originOrientation?.let {
                activity?.requestedOrientation = it
                originOrientation = null
            }
            isFullScreen = false
            binding.webViewContainer.visible()
            binding.customWebView.removeAllViews()
            customWebViewCallback = null
            dialog?.keepScreenOn(false)
        }

        /* 覆盖window.close() */
        override fun onCloseWindow(window: WebView?) {
            if (ownsLease(lease)) dismiss()
        }

        /* 监听网页日志 */
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            if (!ownsLease(lease)) return false
            if (!AppConfig.recordLog) return false
            val source = source ?: return false
            val messageLevel = consoleMessage.messageLevel().name
            val message = consoleMessage.message()
            AppLog.put(
                "${source.getTag()}${messageLevel}: $message",
                NoStackTraceException("\n${message}\n- Line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}")
            )
            return true
        }
    }

    private inner class CustomWebViewClient(
        private val lease: WebViewRequestLifecycle
    ) : WebViewClient() {
        private val documentPreloadJs = preloadJs
        private var firstDocumentStarted = false

        override fun shouldOverrideUrlLoading(
            view: WebView?, request: WebResourceRequest?
        ): Boolean {
            if (!ownsLease(lease) || view !== currentWebView) return true
            request?.let {
                return shouldOverrideUrlLoading(it.url)
            }
            return true
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            if (!ownsLease(lease) || view !== currentWebView) return true
            url?.let {
                return shouldOverrideUrlLoading(it.toUri())
            }
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (!ownsLease(lease) || view !== currentWebView) return
            // The initial load already owns the lease's first page. Only a later
            // navigation invalidates that document's outstanding bridge requests.
            if (firstDocumentStarted) lease.startPage() else firstDocumentStarted = true
            if (needClearHistory) {
                needClearHistory = false
                currentWebView.clearHistory() //清除历史
            }
            super.onPageStarted(view, url, favicon)
            currentWebView.evaluateJavascript(basicJs, null)
        }

        private fun shouldOverrideUrlLoading(url: Uri): Boolean {
            val page = lease.currentPage() ?: return true
            if (!ownsPage(lease, page)) return true
            return when (url.scheme) {
                "http", "https" -> false
                "legado", "yuedu" -> {
                    startActivity<OnLineImportActivity> {
                        data = url
                    }
                    true
                }

                else -> {
                    binding.root.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                        if (ownsPage(lease, page)) activity?.openUrl(url)
                    }
                    true
                }
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?, handler: SslErrorHandler?, error: SslError?
        ) {
            if (ownsLease(lease) && view === currentWebView) handler?.proceed()
            else handler?.cancel()
        }

        @Volatile
        private var jsInjected = false
        override fun shouldInterceptRequest(
            view: WebView, request: WebResourceRequest
        ): WebResourceResponse? {
            if (!ownsLease(lease) || view !== currentWebView) return cancelledResponse()
            val url = request.url.toString()
            if (request.isForMainFrame) {
                if (!documentPreloadJs.isNullOrEmpty()) {
                    jsInjected = false
                    if (url.startsWith("data:text/html;") || request.method == "POST") {
                        return super.shouldInterceptRequest(view, request)
                    }
                    return try {
                        runBlocking(lease.scope.coroutineContext + IO) {
                            getModifiedContentWithJs(url, request) ?: super.shouldInterceptRequest(view, request)
                        }
                    } catch (_: CancellationException) {
                        // WebView's synchronous interception API has no cancellation
                        // channel. Do not restart a cancelled request via the network.
                        cancelledResponse()
                    }
                }
            } else if (!jsInjected && url == nameUrl) {
                jsInjected = true
                val preloadJs = documentPreloadJs ?: ""
                return WebResourceResponse(
                    "text/javascript",
                    "utf-8",
                    ByteArrayInputStream("(() => {$JS_INJECTION\n$preloadJs\n})();".toByteArray())
                )
            }
            return super.shouldInterceptRequest(view, request)
        }

        private fun cancelledResponse() = WebResourceResponse(
            "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
        )
        private val webCookieManager by lazy { android.webkit.CookieManager.getInstance() }
        private suspend fun getModifiedContentWithJs(url: String, request: WebResourceRequest): WebResourceResponse? {
            try {
                val cookie = webCookieManager.getCookie(url)
                val res = okHttpClient.newCallResponse {
                    url(url)
                    method(request.method, null)
                    if (!cookie.isNullOrEmpty()) {
                        addHeader("Cookie", cookie)
                    }
                    request.requestHeaders?.forEach { (key, value) ->
                        addHeader(key, value)
                    }
                }
                res.use {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    if (!ownsLease(lease)) {
                        return cancelledResponse()
                    }
                    res.headers("Set-Cookie").forEach { setCookie ->
                        webCookieManager.setCookie(url, setCookie)
                    }
                    val body = res.body
                    val contentType = body.contentType()
                    val mimeType = contentType?.toString()?.substringBefore(";") ?: "text/html"
                    val charset = contentType?.charset() ?: Charsets.UTF_8
                    val charsetSre = charset.name()
                    val bodyText = body.text().let { originalText ->
                        val headIndex = originalText.indexOf("<head", ignoreCase = true)
                        if (headIndex >= 0) {
                            val closingHeadIndex = originalText.indexOf('>', startIndex = headIndex)
                            if (closingHeadIndex >= 0) {
                                val insertPos = closingHeadIndex + 1
                                StringBuilder(originalText).insert(insertPos, JS_URL).toString()
                            } else {
                                originalText
                            }
                        } else {
                            originalText
                        }
                    }
                    return WebResourceResponse(
                        mimeType,
                        charsetSre,
                        ByteArrayInputStream(bodyText.toByteArray(charset))
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return null
            }
        }
    }

}
