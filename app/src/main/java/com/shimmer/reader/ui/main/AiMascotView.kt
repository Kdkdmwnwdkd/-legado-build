package com.shimmer.reader.ui.main

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.shimmer.reader.R
import com.shimmer.reader.ui.main.spirit.SpiritGesture
import com.shimmer.reader.ui.main.spirit.SpiritGestureId
import com.shimmer.reader.ui.main.spirit.SpiritMesh
import com.shimmer.reader.ui.main.spirit.SpiritPose
import com.shimmer.reader.ui.main.spirit.SpiritRepertoire
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * 书架上的书灵。
 *
 * 它只有一张立绘，但整张图被铺成网格做顺序蒙皮：转头时脖子会跟着弯，
 * 呼吸以脚底为锚，被拎起来时上半身会滞后半拍。这些细节决定了它是"站着"还是"贴上去的"。
 *
 * 平时会自己做一些小动作（张望、歪头、作揖、抖袖、打盹），节奏是 7~10 秒一次，
 * 同一个动作不会连着做两遍。缩到屏幕边上之后，它会隔一阵探头出来看一眼。
 *
 * 透明区域不拦截触摸，避免挡住下面的书籍列表滑动。
 */
class AiMascotView(context: Context) : View(context) {

    private enum class Mode {
        /** 站着不动，只有呼吸和飘带。 */
        STILL,

        /** 正在做一个动作。 */
        GESTURE,

        /** 手指按住了，但还没拖动。 */
        HELD,

        /** 正被拖着走。 */
        DRAGGING,
    }

    private val sheet = SpiritMesh()
    private val ink = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var figure: Bitmap? = null

    private var alphaMask: ByteArray? = null
    private var maskWidth = 0
    private var maskHeight = 0
    private var docked = false
    private var dockSide = 1

    private var running = false
    private var mode = Mode.STILL
    private var clockSec = 0f
    private var lastTickMs = 0L

    private var gesture: SpiritGesture? = null
    private var gestureStartedSec = 0f
    private var nextGestureSec = 0f
    private var lastGestureId: SpiritGestureId? = null
    private var quietRounds = 0
    private var rollSeed = 0x2F1B_9C4D

    private var hemPhase = 0f
    private var holdAmount = 0f
    private var leanDeg = 0f
    private var leanTarget = 0f
    private var trailX = 0f
    private var trailY = 0f
    private var squashAtSec = -1f
    private var peekShift = 0f

    private val density = resources.displayMetrics.density
    private val bobAmplitude = 3.2f * density
    private val leanLimit = 11f
    private val peekReach = 14f * density

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val delta = if (lastTickMs == 0L) 0f else ((now - lastTickMs) / 1000f).coerceIn(0f, 0.05f)
            lastTickMs = now
            advance(delta)
            invalidate()
            postOnAnimationDelayed(this, frameGap())
        }
    }

    init {
        isClickable = true
        readFigure()
    }

    private fun readFigure() {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bitmap = BitmapFactory.decodeResource(resources, R.drawable.ai_mascot_ball, options) ?: return
        figure = bitmap
        buildMask(bitmap)
    }

    /** 读一张低分辨率透明度表，用来判断手指有没有落在人物身上。 */
    private fun buildMask(source: Bitmap) {
        val sample = 8
        val width = (source.width / sample).coerceAtLeast(1)
        val height = (source.height / sample).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(source, width, height, true)
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        val mask = ByteArray(pixels.size)
        for (i in pixels.indices) {
            mask[i] = if ((pixels[i] ushr 24) > 40) 1 else 0
        }
        alphaMask = mask
        maskWidth = width
        maskHeight = height
        if (small !== source) small.recycle()
    }

    private fun onFigure(x: Float, y: Float): Boolean {
        val mask = alphaMask ?: return true
        if (width <= 0 || height <= 0) return true
        val column = (x / width * maskWidth).toInt().coerceIn(0, maskWidth - 1)
        val row = (y / height * maskHeight).toInt().coerceIn(0, maskHeight - 1)
        if (mask[row * maskWidth + column].toInt() != 0) return true
        // 描边很细，容一两个像素，免得点不中。
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
        // 缩在屏幕边上时只剩窄窄一条，这时整条都算可点，
        // 否则人物边缘是透明的，会点不到、也抓不回来。
        if (!docked && ev.actionMasked == MotionEvent.ACTION_DOWN && !onFigure(ev.x, ev.y)) {
            return false
        }
        return super.dispatchTouchEvent(ev)
    }

    // ------------------------------------------------------------ 生命周期

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (running) resumeTicker()
    }

    override fun onDetachedFromWindow() {
        pauseTicker()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            if (running) resumeTicker()
        } else {
            pauseTicker()
        }
    }

    fun startIdle() {
        if (running) return
        running = true
        clockSec = 0f
        lastTickMs = 0L
        quietRounds = 0
        mode = Mode.STILL
        // 刚回到书架，先打个招呼。
        beginGesture(SpiritGestureId.GREETING)
        resumeTicker()
    }

    fun stopIdle() {
        running = false
        pauseTicker()
        gesture = null
        mode = Mode.STILL
        holdAmount = 0f
        leanDeg = 0f
        leanTarget = 0f
        trailX = 0f
        trailY = 0f
        squashAtSec = -1f
        peekShift = 0f
        invalidate()
    }

    private fun resumeTicker() {
        removeCallbacks(ticker)
        lastTickMs = 0L
        postOnAnimation(ticker)
    }

    private fun pauseTicker() {
        removeCallbacks(ticker)
        lastTickMs = 0L
    }

    private fun frameGap(): Long = when (mode) {
        Mode.STILL -> 33L
        else -> 16L
    }

    // ------------------------------------------------------------ 手势

    /** 手指按下：停下手上的动作，轻轻被压一下。 */
    fun onPressDown() {
        if (!running) startIdle()
        mode = Mode.HELD
        holdAmount = 0f
    }

    /** 拖动中按位移倾斜，并让上半身滞后半拍。 */
    fun onDragTilt(dxFromDown: Float, dyFromDown: Float = 0f) {
        mode = Mode.DRAGGING
        val base = width.coerceAtLeast(1)
        leanTarget = (dxFromDown / base * 52f).coerceIn(-leanLimit, leanLimit)
        trailX = (-dxFromDown / base * 5f * density).coerceIn(-9f * density, 9f * density)
        trailY = (-dyFromDown / base * 3f * density).coerceIn(-6f * density, 6f * density)
    }

    /** 松手：回正，过一会儿再开始做小动作。 */
    fun onRelease() {
        mode = Mode.STILL
        leanTarget = 0f
        holdAmount = 0f
        trailX = 0f
        trailY = 0f
        quietRounds = 0
        nextGestureSec = clockSec + pauseBeforeNext()
    }

    /** 点击时先被压扁再弹回来，然后执行动作。 */
    fun playTapBounce(onEnd: () -> Unit) {
        mode = Mode.STILL
        holdAmount = 0f
        leanTarget = 0f
        squashAtSec = clockSec
        postDelayed({
            if (!running) startIdle()
            onEnd()
        }, 260L)
    }

    /** 收起贴边时放宽触摸判定，并让它在边上探头探脑。 */
    fun setEdgePeek(dockedEdge: Boolean, side: Int = 1) {
        docked = dockedEdge
        dockSide = if (side == 0) 1 else -1
        if (!dockedEdge) peekShift = 0f
    }

    // ------------------------------------------------------------ 推进

    private fun advance(delta: Float) {
        clockSec += delta
        // 飘带的相位一直在走，就算停手不动也有一点点活气。
        hemPhase += delta * (1.5f + 1.1f * abs(sin(clockSec * 0.42f)))

        holdAmount = ease(holdAmount, if (mode == Mode.HELD) 1f else 0f, 16f, delta)
        leanDeg = ease(leanDeg, leanTarget, if (mode == Mode.DRAGGING) 22f else 11f, delta)

        when (mode) {
            Mode.GESTURE -> {
                val running_ = gesture
                if (running_ != null && (clockSec - gestureStartedSec) * 1000f >= running_.durationMs) {
                    gesture = null
                    mode = Mode.STILL
                    quietRounds++
                    nextGestureSec = clockSec + pauseBeforeNext()
                }
            }
            Mode.STILL -> {
                if (clockSec >= nextGestureSec) beginNextGesture()
            }
            else -> Unit
        }

        // 探头位移：收起时随动作探出屏幕一点点，看着像在偷看外面。
        val want = if (docked) dockSide * peekReach * currentPose().peek else 0f
        peekShift = ease(peekShift, want, 12f, delta)
    }

    private fun beginNextGesture() {
        if (docked) {
            beginGesture(SpiritGestureId.PEEK)
        } else {
            beginGesture(SpiritRepertoire.pickCasual(lastGestureId, nextRoll(1_000)))
        }
    }

    private fun beginGesture(id: SpiritGestureId) {
        gesture = SpiritRepertoire.get(id)
        gestureStartedSec = clockSec
        lastGestureId = id
        mode = Mode.GESTURE
        nextGestureSec = clockSec + (gesture?.durationMs ?: 0) / 1000f + pauseBeforeNext()
    }

    private fun pauseBeforeNext(): Float {
        // 收在边上时紧张一点，露在外面时懒散一点。
        val base = if (docked) 5.6f else 7.2f
        val drift = nextRoll(320) / 100f
        val tired = if (quietRounds > 3) minOf(quietRounds - 3, 3) * 2.4f else 0f
        return base + drift + tired
    }

    private fun nextRoll(bound: Int): Int {
        if (bound <= 0) return 0
        rollSeed = rollSeed * 1664525 + 1013904223
        return ((rollSeed ushr 8) and 0x7FFFFFFF) % bound
    }

    // ------------------------------------------------------------ 姿态

    private fun restingPose(): SpiritPose {
        val t = clockSec
        return SpiritPose(
            puff = sin(t * 1.05f) * 0.012f,
            weight = sin(t * 0.42f) * 0.75f,
            flow = 0.34f + 0.22f * sin(t * 0.63f),
        )
    }

    private fun currentPose(): SpiritPose {
        val active = gesture
        val doing = if (active == null) {
            SpiritPose.REST
        } else {
            active.poseAt(((clockSec - gestureStartedSec) * 1000f).toInt())
        }
        return restingPose() + doing + SpiritPose(press = squashNow())
    }

    /** 点击后的挤压：先快速压扁，再回弹拉长，最后归位。 */
    private fun squashNow(): Float {
        if (squashAtSec < 0f) return 0f
        val t = clockSec - squashAtSec
        if (t > 0.44f) {
            squashAtSec = -1f
            return 0f
        }
        return (0.085f * exp(-7f * t) * sin(28f * t)).toFloat()
    }

    private fun ease(current: Float, target: Float, rate: Float, delta: Float): Float {
        if (delta <= 0f) return current
        return current + (target - current) * (1f - exp(-rate * delta))
    }

    // ------------------------------------------------------------ 绘制

    override fun onDraw(canvas: Canvas) {
        val bitmap = figure ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val verts = sheet.deform(w, h, currentPose(), hemPhase, trailX, trailY)
        val axisX = w * SpiritMesh.AXIS_X

        canvas.save()
        // 整体浮动幅度很小，只负责"没有死在原地"。
        canvas.translate(peekShift, sin(clockSec * 0.92f) * bobAmplitude)
        val grip = 1f + 0.028f * holdAmount
        canvas.scale(grip, grip, axisX, h * 0.94f)
        if (leanDeg != 0f) {
            canvas.rotate(leanDeg, axisX, h * 0.86f)
        }
        canvas.drawBitmapMesh(bitmap, sheet.cols, sheet.rows, verts, 0, null, 0, ink)
        canvas.restore()
    }
}
