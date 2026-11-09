package com.shimmer.reader.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.shimmer.reader.R
import kotlin.math.PI
import kotlin.math.sin

/**
 * 书架上的 AI 助手形象。
 *
 * 不使用圆形底板，直接显示一张透明立绘：
 * 待机时做很轻的呼吸浮动，按住时略微放大，拖动时随手指倾斜，松手回正，点击弹一下。
 * 透明区域不拦截触摸，避免挡住下面的书籍列表滑动。
 */
class AiMascotView(context: Context) : FrameLayout(context) {

    private val artwork = ImageView(context)
    private var idleAnimator: ValueAnimator? = null
    private var alphaMask: ByteArray? = null
    private var maskWidth = 0
    private var maskHeight = 0
    private var edgePeek = false

    private val floatAmplitude = 4f * resources.displayMetrics.density
    private val tiltLimit = 11f

    init {
        clipChildren = false
        isClickable = true
        artwork.scaleType = ImageView.ScaleType.FIT_CENTER
        artwork.setImageResource(R.drawable.ai_mascot_ball)
        addView(artwork, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        buildAlphaMask()
    }

    /** 读取一张低分辨率的透明度表，用于判断手指是否落在人物身上。 */
    private fun buildAlphaMask() {
        val options = BitmapFactory.Options().apply { inSampleSize = 8 }
        val bitmap = BitmapFactory.decodeResource(resources, R.drawable.ai_mascot_ball, options) ?: return
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            bitmap.recycle()
            return
        }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val mask = ByteArray(pixels.size)
        for (i in pixels.indices) {
            mask[i] = if ((pixels[i] ushr 24) > 40) 1 else 0
        }
        alphaMask = mask
        maskWidth = width
        maskHeight = height
        bitmap.recycle()
    }

    private fun hitSolid(x: Float, y: Float): Boolean {
        val mask = alphaMask ?: return true
        if (width <= 0 || height <= 0) return true
        val column = (x / width * maskWidth).toInt().coerceIn(0, maskWidth - 1)
        val row = (y / height * maskHeight).toInt().coerceIn(0, maskHeight - 1)
        if (mask[row * maskWidth + column].toInt() != 0) return true
        // 边缘描线很细，允许相邻一两个像素也算命中，避免点不中。
        for (dy in -1..1) {
            for (dx in -1..1) {
                val c = column + dx
                val r = row + dy
                if (c in 0 until maskWidth && r in 0 until maskHeight &&
                    mask[r * maskWidth + c].toInt() != 0
                ) return true
            }
        }
        return false
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 缩在屏幕边上的时候只剩窄窄一条，这时整条都算可点，
        // 不然人物边缘是透明的，会点不到、也抓不回来。
        if (!edgePeek && ev.actionMasked == MotionEvent.ACTION_DOWN && !hitSolid(ev.x, ev.y)) {
            return false
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 收起贴边时放宽触摸判定。 */
    fun setEdgePeek(peeking: Boolean) {
        edgePeek = peeking
    }

    fun startIdle() {
        if (idleAnimator != null) return
        idleAnimator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
            duration = 3800
            repeatCount = ValueAnimator.INFINITE
            interpolator = null
            addUpdateListener { animator ->
                val phase = animator.animatedValue as Float
                artwork.translationY = -floatAmplitude * sin(phase)
                val scale = 1f + 0.012f * sin(phase * 2f)
                artwork.scaleX = scale
                artwork.scaleY = scale
            }
            start()
        }
    }

    fun stopIdle() {
        idleAnimator?.cancel()
        idleAnimator = null
        artwork.translationY = 0f
        artwork.scaleX = 1f
        artwork.scaleY = 1f
        artwork.rotation = 0f
    }

    /** 手指按下：停下待机动画，轻微放大当作反馈。 */
    fun onPressDown() {
        idleAnimator?.cancel()
        idleAnimator = null
        artwork.animate().cancel()
        artwork.animate().scaleX(1.03f).scaleY(1.03f).setDuration(110).start()
    }

    /** 拖动过程中按水平位移倾斜，做出被拎起来的感觉。 */
    fun onDragTilt(dxFromDown: Float) {
        val base = width.coerceAtLeast(1)
        artwork.rotation = (dxFromDown / base * 46f).coerceIn(-tiltLimit, tiltLimit)
    }

    /** 松手：回正并恢复待机动画。 */
    fun onRelease() {
        artwork.animate().cancel()
        artwork.animate()
            .rotation(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { startIdle() }
            .start()
    }

    /** 点击时先压一下再弹回，然后执行动作。 */
    fun playTapBounce(onEnd: () -> Unit) {
        idleAnimator?.cancel()
        idleAnimator = null
        artwork.animate().cancel()
        artwork.animate()
            .scaleX(1.06f)
            .scaleY(0.95f)
            .setDuration(80)
            .withEndAction {
                artwork.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(190)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        startIdle()
                        onEnd()
                    }
                    .start()
            }
            .start()
    }
}
