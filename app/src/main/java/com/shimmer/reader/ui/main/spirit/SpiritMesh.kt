package com.shimmer.reader.ui.main.spirit

import kotlin.math.cos
import kotlin.math.sin

/**
 * 把一张静止的立绘变成一条能弯曲的"身子"。
 *
 * 做法是顺序蒙皮：立绘先铺成一张规则网格，每个顶点按高度绑定到四段——
 * 脚底、腰、颈、下摆，每段给一个 0~1 的权重。算一帧时从脚底往上依次施加变换，
 * 每一步只按该段的权重把顶点"拉过去一部分"，所以腰弯的时候颈会跟着走，
 * 颈转的时候肩膀又只跟一半，接缝自然是连续的。
 *
 * 这不是把图切开再拼回去：所有顶点始终来自同一张位图，只是位置被挪动了，
 * 因此不会出现部件接缝或缺口。
 */
class SpiritMesh(val cols: Int = 10, val rows: Int = 28) {

    private val verts = FloatArray((cols + 1) * (rows + 1) * 2)
    private val uAxis = FloatArray(cols + 1) { it.toFloat() / cols }
    private val vAxis = FloatArray(rows + 1) { it.toFloat() / rows }

    /** 每行的四段权重，只跟高度有关，构造时算一次。 */
    private val neckWeight = FloatArray(rows + 1) { row ->
        (1f - ramp((vAxis[row] - NECK_FADE_START) / (NECK_FADE_END - NECK_FADE_START))).toFloat()
    }
    private val waistWeight = FloatArray(rows + 1) { row ->
        bell(vAxis[row], WAIST_CENTER, WAIST_SPREAD).toFloat()
    }
    private val footWeight = FloatArray(rows + 1) { row ->
        ramp((vAxis[row] - FOOT_FADE_START) / (FOOT_FADE_END - FOOT_FADE_START)).toFloat()
    }
    private val hemWeight = FloatArray(rows + 1) { row ->
        val v = vAxis[row]
        (v * v).toFloat()
    }

    /**
     * 求一帧的顶点坐标，单位 px，原点在控件左上角。
     *
     * [hemPhase] 由调用方按时间推进，停下不动时也应该缓慢变化，
     * 否则人物会像被冻住；[dragX]/[dragY] 是拖拽时上半身的滞后量。
     */
    fun deform(
        width: Float,
        height: Float,
        pose: SpiritPose,
        hemPhase: Float,
        dragX: Float = 0f,
        dragY: Float = 0f,
    ): FloatArray {
        val axisX = width * AXIS_X
        val neckY = height * NECK_LINE
        val waistY = height * WAIST_LINE
        val footY = height * FOOT_LINE

        // 三个转角一次算好，循环里只做乘加。
        val footSin = sin(pose.weight * DEG)
        val footCos = cos(pose.weight * DEG)
        val waistSin = sin(pose.bend * DEG)
        val waistCos = cos(pose.bend * DEG)
        val neckSin = sin(pose.turn * DEG)
        val neckCos = cos(pose.turn * DEG)

        val stretch = 1f + pose.puff - pose.press
        val nodShift = pose.nod * height
        val hemAmp = width * HEM_AMPLITUDE * pose.flow

        var index = 0
        for (row in 0..rows) {
            val v = vAxis[row]
            val wNeck = neckWeight[row]
            val wWaist = waistWeight[row]
            val wFoot = footWeight[row]
            val wHem = hemWeight[row]
            val ripple = sin(hemPhase + v * HEM_WAVE_SPAN) * hemAmp * wHem
            val lag = 1f - v

            for (col in 0..cols) {
                var x = uAxis[col] * width
                var y = v * height

                // 呼吸以脚底为锚，头顶才会上浮而不是整块平移。
                y = footY + (y - footY) * stretch

                // ① 重心：整个人绕脚底倾一点，下半身几乎全量，上半身基本不动。
                if (wFoot > 0f) {
                    val dx = x - axisX
                    val dy = y - footY
                    x += ((axisX + footCos * dx - footSin * dy) - x) * wFoot
                    y += ((footY + footSin * dx + footCos * dy) - y) * wFoot
                }

                // ② 腰：只影响腰部附近，钟形权重，越往上下越弱。
                if (wWaist > 0f) {
                    val dx = x - axisX
                    val dy = y - waistY
                    x += ((axisX + waistCos * dx - waistSin * dy) - x) * wWaist
                    y += ((waistY + waistSin * dx + waistCos * dy) - y) * wWaist
                }

                // ③ 颈：头顶全量，到胸口衰减完。低头时整块往下沉。
                if (wNeck > 0f) {
                    val dx = x - axisX
                    val dy = y - neckY
                    x += ((axisX + neckCos * dx - neckSin * dy) - x) * wNeck
                    y += ((neckY + neckSin * dx + neckCos * dy) - y) * wNeck + nodShift * wNeck
                }

                // ④ 下摆与飘带，越靠下波动越大。
                x += ripple
                // ⑤ 拖拽时上半身滞后，做出被拎起来甩的感觉。
                x += dragX * lag
                y += dragY * lag

                verts[index++] = x
                verts[index++] = y
            }
        }
        return verts
    }

    /** 0→1 的平滑爬升，两端导数为 0。 */
    private fun ramp(value: Double): Double {
        val t = value.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /** 中心最强、两侧平滑衰减到 0 的钟形。 */
    private fun bell(value: Float, center: Float, spread: Float): Double {
        val d = ((value - center) / spread).toDouble()
        if (d <= -1.0 || d >= 1.0) return 0.0
        val triangle = 1.0 - kotlin.math.abs(2.0 * ((d + 1.0) * 0.5) - 1.0)
        return ramp(triangle)
    }

    companion object {
        /** 人物在图中的水平中线。 */
        const val AXIS_X = 0.531f

        /** 颈部位置，头部权重从这里开始衰减。 */
        const val NECK_LINE = 0.44f

        /** 腰部，弯身时绕它转。 */
        const val WAIST_LINE = 0.70f

        /** 脚底，所有旋转的最终锚点。 */
        const val FOOT_LINE = 0.99f

        private const val NECK_FADE_START = 0.33
        private const val NECK_FADE_END = 0.56
        private const val WAIST_CENTER = 0.64f
        private const val WAIST_SPREAD = 0.30f
        private const val FOOT_FADE_START = 0.30
        private const val FOOT_FADE_END = 0.86
        private const val HEM_AMPLITUDE = 0.022f
        private const val HEM_WAVE_SPAN = 2.6f
        private const val DEG = Math.PI.toFloat() / 180f
    }
}
