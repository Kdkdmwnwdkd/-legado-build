package io.legado.app.ui.book.read.epub

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.model.localBook.epubcore.direct.EpubDirectChapter
import io.legado.app.model.localBook.epubcore.direct.EpubDirectPosition
import io.legado.app.model.localBook.epubcore.direct.EpubDirectSession
import io.legado.app.model.localBook.epubcore.direct.TextReaderImageActionRequest
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import io.legado.app.model.localBook.epubcore.layout.EpubCorePage
import io.legado.app.model.localBook.epubcore.layout.EpubReaderChromeData

/**
 * EPUB reader container for the direct WebView engine.
 *
 * The visible rendering surface is [EpubDirectWebLayer]; this view only hosts it,
 * relays its callbacks and draws the loading/error overlay while no document is
 * visible. The former hidden-WebView/canvas renderer has been removed.
 */
class EpubReadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onCenterTap(x: Float, y: Float) = Unit
        fun onPreviousPage() = Unit
        fun onNextPage() = Unit
        fun onPageClick(x: Float, y: Float) = Unit
        fun onTapAction(action: Int, x: Float, y: Float) = Unit
        fun onPageChanged(pageIndex: Int, pageCount: Int) = Unit
        fun onPageBoundary(direction: Int) = Unit
        fun onTextSelected(startX: Float, topY: Float, endX: Float, bottomY: Float, startBottomY: Float = bottomY, endBottomY: Float = bottomY) = Unit
        fun onSelectionInteractionStarted() = Unit
        fun onSelectionCleared() = Unit
        fun onDirectChapterReady(position: EpubDirectPosition) = Unit
        fun onDirectLinkClicked(url: String) = Unit
        fun onDirectFootnoteClicked(url: String) = Unit
        fun onDirectImageClicked(url: String) = Unit
        fun onDirectSourceImageAction(request: TextReaderImageActionRequest) = Unit
        fun onDirectRenderError(message: String, throwable: Throwable?) = Unit
    }

    data class SelectionAnchor(
        val startX: Float,
        val topY: Float,
        val endX: Float,
        val bottomY: Float,
        val startBottomY: Float = bottomY,
        val endBottomY: Float = bottomY
    )

    private val directLayerDelegate = lazy(LazyThreadSafetyMode.NONE) {
        EpubDirectWebLayer(context).also(::attachDirectLayer)
    }
    private val directLayer: EpubDirectWebLayer
        get() = directLayerDelegate.value
    private var directMode = false
    private var directHostPaused = false
    private var directPosition: EpubDirectPosition? = null
    private var directChapter: EpubDirectChapter? = null
    private var readerChromeData: EpubReaderChromeData = EpubReaderChromeData()

    private var listener: Listener? = null
    private var loadingMessage: String? = null
    private var selectedText: String = ""
    private var selectionAnchor: SelectionAnchor? = null
    private var selectionMenuPending = false
    private var selectionInteractionActive = false
    private var selectionMenuPresented = false
    private val selectionMenuNotifyRunnable = Runnable(::notifySelectionMenuIfReady)

    private val loadingOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000
        style = Paint.Style.FILL
    }
    private val loadingTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }
    /** Overlay text style, kept in sync with the reader config by the host. */
    var overlayTextColor: Int = 0xFF666666.toInt()
    var overlayTextSizePx: Float = 42f

    var layoutConfig: EpubCoreLayoutConfig? = null

    var pageIndex: Int = 0
        private set

    val isTextSelected: Boolean
        get() = selectedText.isNotBlank()

    val isSelectionBlockingPageTurn: Boolean
        get() = selectionInteractionActive || selectedText.isNotBlank()

    val isDirectMode: Boolean
        get() = directMode

    val hasDirectContent: Boolean
        get() = directMode && initializedDirectLayer()?.hasVisibleDocument == true

    init {
        isFocusable = true
        isClickable = true
        setWillNotDraw(false)
    }

    private fun attachDirectLayer(layer: EpubDirectWebLayer) {
        layer.visibility = View.GONE
        addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (directHostPaused) layer.onHostPause()
        layer.updateReaderChromeData(readerChromeData)
        layer.setListener(object : EpubDirectWebLayer.Listener {
            override fun onReady(position: EpubDirectPosition) {
                directPosition = position
                pageIndex = position.pageIndex
                clearLoading()
                listener?.onDirectChapterReady(position)
            }

            override fun onPositionChanged(position: EpubDirectPosition) {
                directPosition = position
                pageIndex = position.pageIndex
                listener?.onPageChanged(position.pageIndex, position.pageCount)
            }

            override fun onPageBoundary(direction: Int) {
                listener?.onPageBoundary(direction)
            }

            override fun onTap(x: Float, y: Float) {
                handleTap(x, y)
            }

            override fun onSelectionChanged(text: String, rects: List<RectF>) {
                applyDirectSelection(text, rects)
            }

            override fun onSelectionInteractionChanged(active: Boolean) {
                updateDirectSelectionInteraction(active)
            }

            override fun onSelectionCleared() {
                if (selectedText.isNotBlank()) clearSelection(notify = true)
            }

            override fun onLinkClicked(url: String) {
                listener?.onDirectLinkClicked(url)
            }

            override fun onFootnoteClicked(url: String) {
                listener?.onDirectFootnoteClicked(url)
            }

            override fun onImageClicked(url: String) {
                listener?.onDirectImageClicked(url)
            }

            override fun onSourceImageAction(request: TextReaderImageActionRequest) {
                listener?.onDirectSourceImageAction(request)
            }

            override fun onError(message: String, throwable: Throwable?) {
                AppLog.putDebug(message, throwable)
                clearLoading()
                if (!layer.hasVisibleDocument) {
                    directPosition = null
                    setError(message)
                }
                listener?.onDirectRenderError(message, throwable)
            }
        })
    }

    private fun initializedDirectLayer(): EpubDirectWebLayer? {
        return if (directLayerDelegate.isInitialized()) directLayerDelegate.value else null
    }

    fun isCurrentSourceImageAction(request: TextReaderImageActionRequest): Boolean =
        directMode && initializedDirectLayer()?.isCurrentSourceImageAction(request) == true

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun showLoading(message: String) {
        loadingMessage = message
        invalidate()
    }

    fun setError(message: String) {
        loadingMessage = message
        invalidate()
    }

    fun clearLoading() {
        if (loadingMessage == null) return
        loadingMessage = null
        invalidate()
    }

    fun hideLoading() {
        clearLoading()
    }

    fun currentPage(): EpubCorePage? {
        if (!directMode) return null
        val position = directPosition ?: return null
        return EpubCorePage(
            chapterIndex = position.chapterIndex,
            chapterHref = position.chapterHref,
            pageIndex = position.pageIndex,
            totalPagesInChapter = position.pageCount,
            text = directLayer.chapterText
        )
    }

    fun nextPage(): EpubPageTurnResult {
        if (isSelectionBlockingPageTurn) return EpubPageTurnResult.Rejected
        return if (directMode) directLayer.nextPage() else EpubPageTurnResult.Unavailable
    }

    fun previousPage(): EpubPageTurnResult {
        if (isSelectionBlockingPageTurn) return EpubPageTurnResult.Rejected
        return if (directMode) directLayer.previousPage() else EpubPageTurnResult.Unavailable
    }

    fun hasPendingBoundaryTurn(direction: Int? = null): Boolean {
        return directMode && initializedDirectLayer()?.hasPendingChapterTurn(direction) == true
    }

    fun pendingBoundaryChapterIndex(direction: Int): Int? {
        if (!directMode) return null
        return initializedDirectLayer()?.pendingChapterTurnTarget(direction)
    }

    fun cancelPendingBoundaryTurn() {
        initializedDirectLayer()?.cancelPendingChapterTurn()
    }

    fun setPageIndex(index: Int): Boolean {
        if (isSelectionBlockingPageTurn) return false
        return directMode && directLayer.setPage(index, animate = false)
    }

    fun currentChapterPageIndex(): Int {
        return if (directMode) directPosition?.pageIndex ?: 0 else 0
    }

    fun currentChapterPageCount(): Int {
        return if (directMode) directPosition?.pageCount ?: 0 else 0
    }

    fun setChapterPageIndex(chapterIndex: Int, chapterPageIndex: Int): Boolean {
        if (!directMode || isSelectionBlockingPageTurn) return false
        if (directPosition?.chapterIndex != chapterIndex) return false
        return directLayer.setPage(chapterPageIndex, animate = false)
    }

    fun setChapterPageEdge(chapterIndex: Int, toLastPage: Boolean): Boolean {
        if (!directMode || isSelectionBlockingPageTurn) return false
        val position = directPosition ?: return false
        if (position.chapterIndex != chapterIndex) return false
        return directLayer.setPage(if (toLastPage) position.pageCount - 1 else 0, animate = false)
    }

    fun followReadAloud(cueText: String, cueOffset: Int, approximateProgress: Float): Boolean {
        if (!directMode || isSelectionBlockingPageTurn) return false
        return directLayer.followReadAloud(cueText, cueOffset, approximateProgress)
    }

    fun getSelectedText(): String = selectedText

    fun selectStartMoveOnScreen(rawX: Float, rawY: Float) = Unit

    fun selectEndMoveOnScreen(rawX: Float, rawY: Float) = Unit

    fun beginSelectionHandleDrag() = Unit

    fun endSelectionHandleDrag() = Unit

    fun cancelSelectionHandleDrag() = Unit

    fun clearSelection(notify: Boolean = true) {
        removeCallbacks(selectionMenuNotifyRunnable)
        if (directMode) initializedDirectLayer()?.clearSelection()
        selectedText = ""
        selectionAnchor = null
        selectionMenuPending = false
        selectionInteractionActive = false
        selectionMenuPresented = false
        if (notify) {
            listener?.onSelectionCleared()
        }
    }

    fun cancelSelect(clearSearchResult: Boolean = false) {
        clearSelection()
    }

    private fun applyDirectSelection(text: String, rects: List<RectF>) {
        if (text.isBlank() || rects.isEmpty()) return
        val selectionStarted = selectedText.isBlank()
        selectedText = text
        val first = rects.first()
        val last = rects.last()
        val anchor = SelectionAnchor(
            startX = first.left,
            topY = rects.minOf { it.top },
            endX = last.right,
            bottomY = rects.maxOf { it.bottom },
            startBottomY = first.bottom,
            endBottomY = last.bottom
        )
        selectionAnchor = anchor
        if (!selectionMenuPresented) selectionMenuPending = true
        if (selectionStarted) listener?.onSelectionInteractionStarted()
        if (!selectionInteractionActive) removeCallbacks(selectionMenuNotifyRunnable)
        notifySelectionMenuIfReady()
    }

    private fun updateDirectSelectionInteraction(active: Boolean) {
        if (selectionInteractionActive == active) return
        selectionInteractionActive = active
        removeCallbacks(selectionMenuNotifyRunnable)
        if (active) {
            if (selectedText.isNotBlank()) {
                selectionMenuPresented = false
                selectionMenuPending = true
                listener?.onSelectionInteractionStarted()
            }
            return
        }
        postDelayed(selectionMenuNotifyRunnable, SELECTION_RELEASE_SETTLE_MS)
    }

    private fun notifySelectionMenuIfReady() {
        val anchor = selectionAnchor ?: return
        if (selectionInteractionActive || selectionMenuPresented || !selectionMenuPending) return
        selectionMenuPending = false
        selectionMenuPresented = true
        listener?.onTextSelected(
            anchor.startX,
            anchor.topY,
            anchor.endX,
            anchor.bottomY,
            anchor.startBottomY,
            anchor.endBottomY
        )
    }

    private fun handleTap(x: Float, y: Float) {
        if (selectedText.isNotBlank()) {
            clearSelection()
            return
        }
        val listener = listener ?: return
        listener.onPageClick(x, y)
        val column = when {
            x < width / 3f -> 0
            x < width * 2f / 3f -> 1
            else -> 2
        }
        val row = when {
            y < height / 3f -> 0
            y < height * 2f / 3f -> 1
            else -> 2
        }
        val action = when (row * 3 + column) {
            0 -> AppConfig.clickActionTL
            1 -> AppConfig.clickActionTC
            2 -> AppConfig.clickActionTR
            3 -> AppConfig.clickActionML
            4 -> AppConfig.clickActionMC
            5 -> AppConfig.clickActionMR
            6 -> AppConfig.clickActionBL
            7 -> AppConfig.clickActionBC
            else -> AppConfig.clickActionBR
        }
        listener.onTapAction(action, x, y)
    }

    fun showDirectChapter(
        session: EpubDirectSession,
        chapter: EpubDirectChapter,
        config: EpubCoreLayoutConfig,
        initialPageIndex: Int,
        openAtEnd: Boolean,
        initialProgress: Float? = null,
        initialFragmentId: String? = null,
        boundaryTransition: Boolean = false
    ) {
        val layer = runCatching { directLayer }.getOrElse { throwable ->
            val message = "EPUB WebView initialization failed: " +
                (throwable.localizedMessage ?: throwable.javaClass.simpleName)
            AppLog.putDebug(message, throwable)
            listener?.onDirectRenderError(message, throwable)
            return
        }
        val replacingVisibleChapter = directMode && layer.hasVisibleDocument
        clearSelection(notify = false)
        directMode = true
        directChapter = chapter
        if (!replacingVisibleChapter) {
            directPosition = null
        }
        layoutConfig = config
        layer.visibility = View.VISIBLE
        layer.bringToFront()
        layer.bindSession(session)
        layer.showChapter(
            chapter,
            config,
            initialPageIndex,
            openAtEnd,
            initialProgress,
            initialFragmentId,
            boundaryTransition
        )
    }

    fun currentDirectProgress(): Float? = directPosition?.progress?.takeIf { directMode }

    fun currentDirectTextPosition(): Int? = directPosition?.characterPosition?.takeIf { directMode }

    fun currentDirectText(): String = if (directMode) directLayer.chapterText else ""
    fun currentSourceChapterUrl(): String? = if (directMode) directLayer.sourceChapterUrl else null

    fun directPreloadCapacity(): Int = initializedDirectLayer()?.preloadCapacity ?: 0

    fun updateDirectPreloadCandidates(
        session: EpubDirectSession,
        chapterIndexes: List<Int>,
        config: EpubCoreLayoutConfig
    ) {
        initializedDirectLayer()?.updatePreloadCandidates(session, chapterIndexes, config)
    }

    fun hasDirectChapterPreload(
        session: EpubDirectSession,
        chapterIndex: Int,
        config: EpubCoreLayoutConfig
    ): Boolean = initializedDirectLayer()?.hasChapterPreload(session, chapterIndex, config) == true

    fun applyDirectPerformanceMode() {
        initializedDirectLayer()?.applyPerformanceMode()
    }

    fun refreshDirectPageAnimationStyle() {
        initializedDirectLayer()?.refreshPageAnimationStyle()
    }

    fun updateDirectReaderChromeData(data: EpubReaderChromeData) {
        readerChromeData = data
        initializedDirectLayer()?.updateReaderChromeData(data)
    }

    fun refreshDirectReaderBackground(config: EpubCoreLayoutConfig) {
        layoutConfig = config
        initializedDirectLayer()?.refreshReaderSurfaceBackground(config)
    }

    fun preloadDirectChapter(
        session: EpubDirectSession,
        chapter: EpubDirectChapter,
        config: EpubCoreLayoutConfig,
        openAtEnd: Boolean = false
    ) {
        if (directMode) directLayer.preloadChapter(session, chapter, config, openAtEnd)
    }

    fun reloadDirectStyle(config: EpubCoreLayoutConfig): Boolean {
        val layer = initializedDirectLayer() ?: return false
        if (!directMode || directChapter == null || !layer.hasVisibleDocument) return false
        layoutConfig = config
        layer.reloadStyle(config)
        return true
    }

    fun releaseDirectMode() {
        clearSelection(notify = false)
        initializedDirectLayer()?.releaseSession()
        directMode = false
        directPosition = null
        directChapter = null
        layoutConfig = null
        clearLoading()
    }

    fun destroyDirectMode() {
        initializedDirectLayer()?.destroy()
        directMode = false
        directPosition = null
        directChapter = null
    }

    fun navigateDirectToFragment(
        fragmentId: String,
        onResult: ((Boolean) -> Unit)? = null
    ): Boolean {
        if (!directMode || isSelectionBlockingPageTurn) return false
        return directLayer.navigateToFragment(fragmentId, onResult)
    }

    fun dismissDirectAnnotation(onResult: (Boolean) -> Unit) {
        if (!directMode) {
            onResult(false)
            return
        }
        initializedDirectLayer()?.dismissAnnotation(onResult) ?: onResult(false)
    }

    fun onDirectHostPause() {
        directHostPaused = true
        initializedDirectLayer()?.onHostPause()
    }

    fun onDirectHostResume() {
        directHostPaused = false
        initializedDirectLayer()?.onHostResume()
    }

    fun setHostOverlayCaptureBlocked(blocked: Boolean) {
        initializedDirectLayer()?.setHostOverlayCaptureBlocked(blocked)
    }

    fun cancelPendingDirectChapterLoad() {
        if (directMode) directLayer.cancelPendingChapterLoad()
    }

    fun trimDirectMemory() {
        if (directMode) directLayer.trimMemory()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawLoadingOverlay(canvas)
    }

    private fun drawLoadingOverlay(canvas: Canvas) {
        val message = loadingMessage ?: return
        if (hasDirectContent) return
        loadingTextPaint.color = overlayTextColor
        loadingTextPaint.textSize = overlayTextSizePx
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), loadingOverlayPaint)
        val centerY = height / 2f - (loadingTextPaint.descent() + loadingTextPaint.ascent()) / 2f
        canvas.drawText(message, width / 2f, centerY, loadingTextPaint)
    }

    private companion object {
        const val SELECTION_RELEASE_SETTLE_MS = 64L
    }
}
