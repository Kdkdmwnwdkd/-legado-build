package io.legado.app.ui.widget.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.appcompat.widget.AppCompatImageView
import androidx.collection.LruCache
import androidx.core.graphics.createBitmap
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.CoverThumbnailCache
import io.legado.app.help.CoverDisplayResolver
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.help.config.CoverCollectionManager.isRealCoverPath
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.titleTextColor
import io.legado.app.model.BookCover
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.textHeight
import io.legado.app.utils.toStringArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import splitties.init.appCtx

/**
 * 封面
 */
@Suppress("unused")
class CoverImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {
    companion object {
        private val nameBitmapCache by lazy { LruCache<String, Bitmap>(33) }
        private val needNameBitmap by lazy { LruCache<String, Boolean>(99) }
    }

    enum class CoverStyle(
        val radiusDp: Float,
        val elevationDp: Float,
        val strokeWidthDp: Float,
        val strokeAlpha: Float
    ) {
        FLAT(8f, 0f, 0f, 0f),
        COMPACT(7f, 1f, 0f, 0f),
        LIST(8f, 1.5f, 0f, 0f),
        GRID(8f, 2f, 0f, 0f),
        DETAIL(12f, 5f, 0f, 0f),
        PREVIEW(10f, 6f, 0f, 0f)
    }

    private var viewWidth: Float = 0f
    private var viewHeight: Float = 0f
    private var currentJob: Job? = null
    private val triggerChannel = Channel<Unit>(Channel.CONFLATED)
    private val outlineRect = RectF()
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private var coverStyle = CoverStyle.LIST
    private var coverRadiusPx = CoverStyle.LIST.radiusDp.dpToPx()
    private var coverStrokeWidthPx = CoverStyle.LIST.strokeWidthDp.dpToPx()
    private var coverStrokeAlpha = CoverStyle.LIST.strokeAlpha
    var bitmapPath: String? = null
        private set
    private var loadKey: String? = null
    private var loadedKey: String? = null
    private var name: String? = null
    private var author: String? = null
    private var drawNameOverlayForCurrentCover = false
    private val drawBookName = BookCover.drawBookName
    private val drawBookAuthor by lazy { BookCover.drawBookAuthor }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        scaleType = ScaleType.CENTER_CROP
        setCoverStyle(CoverStyle.LIST)
    }

    fun setCoverStyle(style: CoverStyle) {
        val shadowElevation = if (AppConfig.bookCoverShadow) style.elevationDp.dpToPx() else 0f
        if (coverStyle == style && elevation == shadowElevation) return
        coverStyle = style
        coverRadiusPx = style.radiusDp.dpToPx()
        coverStrokeWidthPx = style.strokeWidthDp.dpToPx()
        coverStrokeAlpha = style.strokeAlpha
        elevation = shadowElevation
        translationZ = 0f
        updateOutline()
        invalidateOutline()
        invalidate()
    }

    override fun setLayoutParams(params: ViewGroup.LayoutParams?) {
        if (params != null) {
            val width = params.width
            if (width >= 0) {
                params.height = width * 4 / 3
            } else {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        super.setLayoutParams(params)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val measuredHeight = measuredWidth * 4 / 3
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateOutline()
    }

    private fun updateOutline() {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (width <= 0 || height <= 0) return
                outline.setRoundRect(0, 0, width, height, coverRadiusPx)
            }
        }
        clipToOutline = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentName = this.name
        if (drawBookName && currentName != null && drawNameOverlayForCurrentCover) {
            val currentAuthor = this.author
            val pathName = if (drawBookAuthor){
                currentName + currentAuthor
            } else {
                currentName
            }
            val cacheKey = nameBitmapCacheKey(pathName)
            val cacheBitmap = nameBitmapCache[cacheKey]
            if (cacheBitmap != null) {
                canvas.drawBitmap(cacheBitmap, 0f, 0f, null)
            } else {
                drawNameAuthor(pathName, currentName, currentAuthor, false)
            }
        }
        drawCoverStroke(canvas)
    }

    private fun drawCoverStroke(canvas: Canvas) {
        if (coverStrokeWidthPx <= 0f || coverStrokeAlpha <= 0f || width <= 0 || height <= 0) return
        val inset = coverStrokeWidthPx / 2f
        outlineRect.set(inset, inset, width - inset, height - inset)
        strokePaint.strokeWidth = coverStrokeWidthPx
        strokePaint.color = ColorUtils.withAlpha(context.secondaryTextColor, coverStrokeAlpha)
        canvas.drawRoundRect(outlineRect, coverRadiusPx, coverRadiusPx, strokePaint)
    }

    private fun drawNameAuthor(pathName: String, name: String, author: String?, asyncAwait: Boolean = true) {
        generateCoverAsync(pathName, name, author, asyncAwait)
    }
    private fun generateCoverAsync(pathName: String, name: String, author: String?, asyncAwait: Boolean) {
        currentJob?.cancel()
        currentJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                if (asyncAwait) {
                    withTimeoutOrNull(1200) {
                        triggerChannel.receive()
                    }
                    ensureActive()
                }
                if (width == 0) {
                    var attempts = 0
                    do {
                        delay(1L)
                        attempts++
                    } while (width == 0 && attempts < 2000)
                }
                ensureActive()
                val bitmap = generateCoverBitmap(name, author)
                ensureActive()
                needNameBitmap.put(bitmapPath.toString(), true)
                nameBitmapCache.put(nameBitmapCacheKey(pathName), bitmap)
                invalidate()
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun generateCoverBitmap(name: String?, author: String?): Bitmap {
        viewWidth = width.toFloat()
        viewHeight = height.toFloat()
        val bitmap = createBitmap(width, height)
        val bitmapCanvas = Canvas(bitmap)
        var startX = width * 0.2f
        var startY = viewHeight * 0.2f
        val backgroundColor = appCtx.backgroundColor
        val nameColor = appCtx.titleTextColor
        val authorColor = appCtx.secondaryTextColor
        val namePaint = TextPaint().apply {
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        name?.toStringArray()?.let { name ->
            var line = 0
            namePaint.textSize = viewWidth / 7
            namePaint.strokeWidth = namePaint.textSize / 6
            name.forEachIndexed { index, char ->
                namePaint.color = backgroundColor
                namePaint.style = Paint.Style.STROKE
                bitmapCanvas.drawText(char, startX, startY, namePaint)
                namePaint.color = nameColor
                namePaint.style = Paint.Style.FILL
                bitmapCanvas.drawText(char, startX, startY, namePaint)
                startY += namePaint.textHeight
                if (startY > viewHeight * 0.9) {
                    if ((name.size - index - 1) == 1) { //只剩一个字
                        startY -= namePaint.textHeight / 5
                        namePaint.textSize = viewWidth / 9
                        return@forEachIndexed
                    }
                    startX += namePaint.textSize
                    line++
                    namePaint.textSize = viewWidth / 10
                    startY = viewHeight * 0.2f + namePaint.textHeight * line
                }
                else if (startY > viewHeight * 0.8 && (name.size - index - 1) > 2) { //剩余字数大于2
                    startX += namePaint.textSize
                    line++
                    namePaint.textSize = viewWidth / 10
                    startY = viewHeight * 0.2f + namePaint.textHeight * line
                }
            }
        }
        if (!drawBookAuthor){
            return bitmap
        }
        val authorPaint = TextPaint(namePaint).apply {
            typeface = Typeface.DEFAULT
        }
        author?.toStringArray()?.let { author ->
            authorPaint.textSize = viewWidth / 10
            authorPaint.strokeWidth = authorPaint.textSize / 5
            startX = width * 0.8f
            startY = viewHeight * 0.95f - author.size * authorPaint.textHeight
            startY = maxOf(startY, viewHeight * 0.3f)
            author.forEach {
                authorPaint.color = backgroundColor
                authorPaint.style = Paint.Style.STROKE
                bitmapCanvas.drawText(it, startX, startY, authorPaint)
                authorPaint.color = authorColor
                authorPaint.style = Paint.Style.FILL
                bitmapCanvas.drawText(it, startX, startY, authorPaint)
                startY += authorPaint.textHeight
                if (startY > viewHeight * 0.95) {
                    return@let
                }
            }
        }
        return bitmap
    }

    private fun nameBitmapCacheKey(pathName: String): String {
        return listOf(
            pathName,
            width.toString(),
            appCtx.backgroundColor.toString(),
            appCtx.titleTextColor.toString(),
            appCtx.secondaryTextColor.toString()
        ).joinToString("|")
    }

    fun setHeight(height: Int) {
        val width = height * 3 / 4
        minimumWidth = width
    }

    private val glideListener by lazy {
        object : RequestListener<Drawable> {

            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<Drawable>,
                isFirstResource: Boolean
            ): Boolean {
                triggerChannel.trySend(Unit)
                needNameBitmap.put(bitmapPath.toString(), true)
                loadedKey = null
                return false
            }

            override fun onResourceReady(
                resource: Drawable,
                model: Any,
                target: Target<Drawable>?,
                dataSource: DataSource,
                isFirstResource: Boolean
            ): Boolean {
                currentJob?.cancel()
                currentJob = null
                needNameBitmap.remove(bitmapPath.toString())
                loadedKey = loadKey
                invalidate()
                return false
            }

        }
    }

    fun load(
        searchBook: SearchBook,
        loadOnlyWifi: Boolean = false,
        fragment: Fragment? = null,
        lifecycle: Lifecycle? = null
    ) {
        val display = CoverDisplayResolver.resolve(searchBook)
        load(
            display.path,
            display.name,
            display.author,
            loadOnlyWifi,
            display.sourceOrigin,
            fragment,
            lifecycle,
            forcePath = display.forcePath,
            allowNameOverlay = display.allowNameOverlay
        )
    }

    fun load(
        book: Book,
        loadOnlyWifi: Boolean = false,
        fragment: Fragment? = null,
        lifecycle: Lifecycle? = null,
        onLoadFinish: (() -> Unit)? = null
    ) {
        val display = CoverDisplayResolver.resolve(book)
        load(
            display.path,
            display.name,
            display.author,
            loadOnlyWifi,
            display.sourceOrigin,
            fragment,
            lifecycle,
            onLoadFinish,
            forcePath = display.forcePath,
            allowNameOverlay = display.allowNameOverlay
        )
    }

    fun loadThumb(
        book: Book,
        loadOnlyWifi: Boolean = false,
        fragment: Fragment? = null,
        lifecycle: Lifecycle? = null
    ) {
        val display = CoverDisplayResolver.resolve(book)
        load(
            display.path,
            display.name,
            display.author,
            loadOnlyWifi,
            display.sourceOrigin,
            fragment,
            lifecycle,
            null,
            true,
            forcePath = display.forcePath,
            allowNameOverlay = display.allowNameOverlay
        )
    }

    fun load(
        path: String? = null,
        name: String? = null,
        author: String? = null,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
        fragment: Fragment? = null,
        lifecycle: Lifecycle? = null,
        onLoadFinish: (() -> Unit)? = null,
        preferThumb: Boolean = false,
        forcePath: Boolean = false,
        allowNameOverlay: Boolean? = null
    ) {
        val currentAuthor = author?.replace(AppPattern.bdRegex, "")?.trim()?.also {
            this.author = it
        }
        val currentName = name?.replace(AppPattern.bdRegex, "")?.trim()?.also {
            this.name = it
        }
        val useThumb = preferThumb && !AppConfig.loadCoverHighQuality
        val newLoadKey = listOf(
            path.orEmpty(),
            currentName.orEmpty(),
            currentAuthor.orEmpty(),
            sourceOrigin.orEmpty(),
            loadOnlyWifi.toString(),
            AppConfig.useDefaultCover.toString(),
            CoverCollectionManager.selectionKey(),
            useThumb.toString(),
            forcePath.toString(),
            allowNameOverlay.toString()
        ).joinToString("|")
        if (loadedKey == newLoadKey && drawable != null) {
            return
        }
        loadKey = newLoadKey
        this.bitmapPath = path
        val thumbKey = "$sourceOrigin|$path|$currentName|$currentAuthor"
        val hasRealCover = path.isRealCoverPath()
        drawNameOverlayForCurrentCover = allowNameOverlay ?: (AppConfig.useDefaultCover && !forcePath || !hasRealCover)
        if (AppConfig.useDefaultCover && !forcePath) {
            loadedKey = newLoadKey
            ImageLoader.load(context, BookCover.defaultDrawable)
                .centerCrop()
                .into(this)
        } else {
            if (drawNameOverlayForCurrentCover && drawBookName && currentName != null) {
                val pathName = if (drawBookAuthor){
                    currentName + currentAuthor
                } else {
                    currentName
                }
                drawNameAuthor(pathName, currentName, currentAuthor, false)
            }
            var options = RequestOptions()
                .format(DecodeFormat.PREFER_ARGB_8888)
                .disallowHardwareConfig()
                .set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)
            if (sourceOrigin != null) {
                options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
            }
            val thumbFile = if (useThumb) CoverThumbnailCache.existing(context, thumbKey) else null
            var builder = if (thumbFile != null) {
                ImageLoader.load(context, thumbFile)
            } else if (fragment != null && lifecycle != null) {
                ImageLoader.load(fragment, lifecycle, path)
            } else {
                ImageLoader.load(context, path)//Glide自动识别http://,content://和file://
            }
            builder = builder.apply(options)
                .let {
                    if (thumbFile == null) it.placeholder(BookCover.defaultDrawable) else it
                }
                .error(BookCover.defaultDrawable)
                .listener(glideListener)
            if (onLoadFinish != null) {
                builder = builder.addListener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable?>,
                        isFirstResource: Boolean
                    ): Boolean {
                        onLoadFinish.invoke()
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        onLoadFinish.invoke()
                        return false
                    }
                })
            }
            builder
                .priority(Priority.HIGH)
                .override(if (useThumb) 240 else Target.SIZE_ORIGINAL, if (useThumb) 320 else Target.SIZE_ORIGINAL)
                .centerCrop()
                .addListener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable?>,
                        isFirstResource: Boolean
                    ): Boolean {
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        if (useThumb && thumbFile == null) {
                            CoverThumbnailCache.saveAsync(context, thumbKey, resource)
                        }
                        return false
                    }
                })
                .into(this)
        }
    }

    override fun onDetachedFromWindow() {
        currentJob?.cancel()
        currentJob = null
        super.onDetachedFromWindow()
    }

}
