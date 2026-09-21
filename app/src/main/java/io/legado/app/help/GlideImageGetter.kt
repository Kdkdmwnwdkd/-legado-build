package io.legado.app.help

import io.legado.app.help.http.dns.DnsScope
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.text.Html
import android.util.Size
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import java.lang.ref.WeakReference
import io.legado.app.utils.lifecycle
import java.io.ByteArrayInputStream
import kotlin.io.encoding.Base64
import io.legado.app.utils.SvgUtils
import java.util.concurrent.ConcurrentHashMap
import kotlin.text.dropLast
import kotlin.text.endsWith
import kotlin.text.toIntOrNull
import androidx.core.graphics.drawable.toDrawable
import android.graphics.Color
import com.bumptech.glide.request.RequestOptions
import io.legado.app.data.appDb
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.analyzeRule.AnalyzeUrl

class GlideImageGetter(
    context: Context,
    textView: TextView,
    private val lifecycle: Lifecycle,
    private val availableWidth: Int,
    private val sourceOrigin: String? = null
) : Html.ImageGetter, Drawable.Callback {
    private val textViewRef = WeakReference(textView)
    private val contextRef = WeakReference(context)
    private val cacheDrawable = ConcurrentHashMap<String, GlideUrlDrawable>()
    private val pendingImages = mutableSetOf<String>()
    private val emptyDrawable by lazy {
        Color.TRANSPARENT.toDrawable()
    }
    private val bookSource by lazy {
        sourceOrigin?.let { appDb.bookSourceDao.getBookSource(it) }
    }
    override fun getDrawable(source: String?): Drawable {
        val context = contextRef.get()
        if (context == null || source.isNullOrBlank()) {
            return emptyDrawable
        }
        val parsedSource = ImageSourceOptions.parse(source)
        val imageSource = parsedSource?.source ?: source
        val urlOption = parsedSource?.options
        if (imageSource.startsWith("data:", ignoreCase = true)) {
            // Preserve source-specific URL handling for raster data while keeping SVG XML intact.
            val normalizedDataSource = if (
                imageSource.substringBefore(",").contains("image/svg", ignoreCase = true)
            ) {
                imageSource
            } else {
                runCatching {
                    AnalyzeUrl(imageSource, dnsScope = DnsScope.IMAGE, source = bookSource).url
                }.getOrDefault(imageSource)
            }
            val bytes = decodeDataUri(normalizedDataSource) ?: return emptyDrawable
            if (normalizedDataSource.substringBefore(",").contains("image/svg", ignoreCase = true)) {
                val (pictureDrawable, size) = SvgUtils.createDrawable(ByteArrayInputStream(bytes))
                    ?: return emptyDrawable
                pictureDrawable.bounds = getDrawableRect(size, urlOption)
                return pictureDrawable
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return emptyDrawable
            val drawable = BitmapDrawable(context.resources, bitmap)
            drawable.bounds = getDrawableRect(
                Size(bitmap.width, bitmap.height),
                urlOption
            )
            return drawable
        }
        cacheDrawable[source]?.let {
            return it
        }
        val urlDrawable = GlideUrlDrawable()
        cacheDrawable[source] = urlDrawable
        pendingImages.add(source)
        val target = ImageTarget(urlDrawable, source, urlOption)
        var options = RequestOptions()
        if (sourceOrigin != null) {
            options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
        }
        // Keep the option suffix for AnalyzeUrl so source headers/decryption remain active.
        Glide.with(context).lifecycle(lifecycle)
            .load(source)
            .apply(options)
            .into(target)
        return urlDrawable
    }

    private fun getDrawableRect(size: Size, urlOption: Map<String, String>?): Rect {
        val drawableWidth = size.width.coerceAtLeast(1)
        val drawableHeight = size.height.coerceAtLeast(1)
        if (urlOption == null) {
            return Rect(0, 0, drawableWidth, drawableHeight)
        }
        val styleWidth = urlOption["width"] ?: urlOption.entries
            .firstOrNull { it.key.equals("width", ignoreCase = true) }
            ?.value
        val styleType = urlOption["style"] ?: urlOption.entries
            .firstOrNull { it.key.equals("style", ignoreCase = true) }
            ?.value
        val contentWidth = availableWidth.takeIf { it > 0 }
            ?: drawableWidth
        if (styleWidth == null && styleType == null) {
            return Rect(0, 0, drawableWidth, drawableHeight)
        }
        val imgWidth = if (styleWidth?.endsWith("%") == true) {
            val sWidth = styleWidth.dropLast(1).toIntOrNull() ?: 80
            contentWidth * sWidth / 100
        } else {
            val sWidth = styleWidth?.toIntOrNull() ?: 0
            if (sWidth > 0) {
                sWidth
            } else {
                drawableWidth
            }
        }
        val showWidth = (contentWidth.takeIf { it in 1..<imgWidth } ?: imgWidth)
            .coerceAtLeast(1)
        val showHeight = (drawableHeight * showWidth / drawableWidth.toFloat())
            .toInt()
            .coerceAtLeast(1)
        val left = when (styleType) {
            "center" -> (contentWidth - showWidth) / 2
            "right" -> contentWidth - showWidth
            else -> 0
        }
        return Rect(left, 0, left + showWidth, showHeight)
    }

    private fun decodeDataUri(source: String): ByteArray? {
        val separator = source.indexOf(',')
        if (separator < 0) return null
        val metadata = source.substring(0, separator)
        val payload = source.substring(separator + 1)
        return if (metadata.contains(";base64", ignoreCase = true)) {
            val normalized = payload
                .filterNot(Char::isWhitespace)
                .replace('-', '+')
                .replace('_', '/')
                .let { value -> value.padEnd((value.length + 3) / 4 * 4, '=') }
            runCatching { Base64.decode(normalized) }.getOrNull()
        } else {
            Uri.decode(payload).toByteArray(Charsets.UTF_8)
        }
    }

    private fun notifyImageLoaded(source: String) {
        pendingImages.remove(source)
        if (pendingImages.isEmpty()) {
            val textView = textViewRef.get() ?: return
            textView.text = textView.text
        }
    }

    override fun invalidateDrawable(who: Drawable) {
        textViewRef.get()?.invalidate()
    }

    override fun scheduleDrawable(
        who: Drawable,
        what: Runnable,
        `when`: Long
    ) {
    }

    override fun unscheduleDrawable(
        who: Drawable,
        what: Runnable
    ) {
    }

    private inner class GlideUrlDrawable() : Drawable(), Animatable {
        private var mDrawable: Drawable? = null
        private var gDrawable: GifDrawable? = null

        fun setDrawable(drawable: Drawable?) {
            if (drawable is GifDrawable) {
                gDrawable?.apply {
                    callback = null
                    stop()
                }
                gDrawable = drawable.apply {
                    if (!isRunning) {
                        callback = this@GlideImageGetter
                        start()
                    }
                }
                mDrawable = null
            } else {
                gDrawable?.apply {
                    callback = null
                    stop()
                }
                gDrawable = null
                mDrawable = drawable
            }
        }

        fun clear() {
            gDrawable?.apply {
                callback = null
                stop()
            }
            gDrawable = null
            mDrawable = null
        }

        override fun draw(canvas: Canvas) {
            (mDrawable ?: gDrawable)?.draw(canvas)
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            (mDrawable ?: gDrawable)?.bounds = bounds
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int {
            val drawable = mDrawable ?: gDrawable
            return if (drawable != null) {
                when {
                    drawable.alpha == 0 -> PixelFormat.TRANSPARENT
                    drawable.alpha == 255 -> PixelFormat.OPAQUE
                    else -> PixelFormat.TRANSLUCENT
                }
            } else {
                PixelFormat.TRANSLUCENT
            }
        }

        override fun setAlpha(alpha: Int) {
            mDrawable?.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            mDrawable?.colorFilter = colorFilter
        }

        override fun getIntrinsicWidth(): Int {
            return (mDrawable ?: gDrawable)?.intrinsicWidth ?: 0
        }

        override fun getIntrinsicHeight(): Int {
            return (mDrawable ?: gDrawable)?.intrinsicHeight ?: 0
        }

        override fun isRunning(): Boolean {
            return gDrawable?.isRunning == true
        }

        override fun start() {
            gDrawable?.start()
        }

        override fun stop() {
            gDrawable?.stop()
        }
    }

    private inner class ImageTarget(
        private val urlDrawable: GlideUrlDrawable,
        private val source: String,
        private val urlOption: Map<String, String>?
    ) : CustomTarget<Drawable>() {

        override fun onResourceReady(
            drawable: Drawable,
            transition: Transition<in Drawable>?
        ) {
            urlDrawable.setDrawable(drawable)
            val rect =
                getDrawableRect(Size(drawable.intrinsicWidth, drawable.intrinsicHeight), urlOption)
            urlDrawable.bounds = rect
            notifyImageLoaded(source)
        }

        override fun onLoadCleared(placeholder: Drawable?) {
            urlDrawable.clear()
            cacheDrawable.remove(source)
        }

        override fun onLoadFailed(errorDrawable: Drawable?) {
            urlDrawable.setDrawable(errorDrawable)
            notifyImageLoaded(source)
        }
    }

    fun start() {
        cacheDrawable.values.forEach { drawable ->
            drawable.start()
        }
    }

    fun stop() {
        cacheDrawable.values.forEach { drawable ->
            drawable.stop()
        }
    }

    fun clear() {
        cacheDrawable.values.forEach { drawable ->
            drawable.clear()
        }
        cacheDrawable.clear()
        pendingImages.clear()
        contextRef.clear()
        textViewRef.clear()
    }
}
