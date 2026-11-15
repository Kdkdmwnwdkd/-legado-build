package com.shimmer.reader.ui.main.spirit

/**
 * 一个待机小动作。
 *
 * 动作不是用公式拼出来的，而是一张关键帧表：每一行是"第几毫秒，摆成什么样"，
 * 中间用 Catmull-Rom 切线做三次 Hermite 插值。
 *
 * 这么做的原因很实际：动作要调好看，改的是数字；如果写成公式，
 * 想让它"低头再停一下"就得重推一遍三角函数，改一次要半天。
 */
data class SpiritKey(val atMs: Int, val pose: SpiritPose)

data class SpiritGesture(
    val id: SpiritGestureId,
    val keys: List<SpiritKey>,
) {
    init {
        require(keys.size >= 2) { "动作至少要有两个关键帧" }
        require(keys.first().atMs == 0) { "第一个关键帧必须落在 0ms" }
    }

    /** 关键帧的时间与字段值在构造时摊平，算帧时只做乘加，不碰分配。 */
    private val times: IntArray = IntArray(keys.size) { keys[it].atMs }
    private val values: Array<FloatArray> = Array(keys.size) { keys[it].pose.toArray() }
    private val slopeIn = FloatArray(SpiritPose.FIELDS)
    private val slopeOut = FloatArray(SpiritPose.FIELDS)

    val durationMs: Int = keys.last().atMs

    /** 取动作进行到 [elapsedMs] 时的姿势，超出范围返回静止。 */
    fun poseAt(elapsedMs: Int): SpiritPose {
        val out = FloatArray(SpiritPose.FIELDS)
        evaluate(elapsedMs, out)
        return SpiritPose.of(out)
    }

    /**
     * 把 [elapsedMs] 时刻的姿势写进 [dst]，不产生任何临时对象。
     *
     * 待机动画常驻在首页，60 帧下每秒要算几十次，这里省下的分配
     * 直接决定了低端机上会不会隔一会儿卡一下。
     */
    fun evaluate(elapsedMs: Int, dst: FloatArray) {
        if (elapsedMs <= 0) {
            values[0].copyInto(dst, 0, 0, SpiritPose.FIELDS)
            return
        }
        if (elapsedMs >= durationMs) {
            java.util.Arrays.fill(dst, 0, SpiritPose.FIELDS, 0f)
            return
        }
        var index = 0
        while (index < times.size - 2 && times[index + 1] <= elapsedMs) index++
        val a = values[index]
        val b = values[index + 1]
        // 前一个点与后一个点决定两端切线，端点外侧用自身补齐，避免起手就抖。
        val previous = values[if (index > 0) index - 1 else index]
        val following = values[if (index + 2 < values.size) index + 2 else index + 1]
        for (i in 0 until SpiritPose.FIELDS) {
            slopeIn[i] = (b[i] - previous[i]) * 0.5f
            slopeOut[i] = (following[i] - a[i]) * 0.5f
        }

        val span = (times[index + 1] - times[index]).coerceAtLeast(1)
        val t = (elapsedMs - times[index]).toFloat() / span
        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2f * t3 - 3f * t2 + 1f
        val h10 = t3 - 2f * t2 + t
        val h01 = -2f * t3 + 3f * t2
        val h11 = t3 - t2

        for (i in 0 until SpiritPose.FIELDS) {
            dst[i] = h00 * a[i] + h10 * slopeIn[i] + h01 * b[i] + h11 * slopeOut[i]
        }
    }
}

enum class SpiritGestureId {
    /** 刚回到书架时打招呼：抬头，左右各看一眼。 */
    GREETING,

    /** 拱手作揖。 */
    SALUTE,

    /** 抖一下袖子，带起身上的飘带。 */
    SLEEVE,

    /** 歪着头看一会儿。 */
    TILT,

    /** 打盹：头慢慢沉下去，停一会儿再直起来。 */
    DOZE,

    /** 朝两边张望。 */
    GAZE,

    /** 收在屏幕边上时探头出来看一眼，只有贴边时才会用。 */
    PEEK,
}

/**
 * 书灵会做的全部动作。
 *
 * 关键帧的数字是按立绘的构图调的：人物颈部在 44% 高度，头部权重到 58% 才完全消失，
 * 所以小幅度的 [SpiritPose.turn] 看起来是"头在转"，再大就会变成"整张纸在歪"。
 *
 * 幅度按"小人在屏幕上只有一百来像素高"来定。立绘从 84dp 缩到 68dp 之后，
 * 同样转 9 度少掉两成位移，看着就像是没动，所以这里整体放大了一成多：
 * turn 仍守在 14 度以内不越"纸不歪"的边界，加的主要是 bend / nod / weight
 * 这些真能看出身子在动的地方。
 */
object SpiritRepertoire {

    private val greeting = SpiritGesture(
        SpiritGestureId.GREETING,
        listOf(
            SpiritKey(0, SpiritPose.REST),
            SpiritKey(260, SpiritPose(turn = -8.8f, nod = -0.0190f, puff = 0.007f)),
            SpiritKey(760, SpiritPose(turn = -8.8f, nod = -0.0162f, puff = 0.005f)),
            SpiritKey(1_180, SpiritPose(turn = 9.2f, nod = -0.0190f, puff = 0.007f)),
            SpiritKey(1_760, SpiritPose(turn = 9.2f, nod = -0.0124f)),
            SpiritKey(2_240, SpiritPose(turn = 1.9f, nod = 0.0028f)),
            SpiritKey(2_800, SpiritPose.REST),
        ),
    )

    private val salute = SpiritGesture(
        SpiritGestureId.SALUTE,
        listOf(
            SpiritKey(0, SpiritPose.REST),
            SpiritKey(300, SpiritPose(bend = 14.2f, nod = 0.0310f, flow = 0.62f, puff = -0.006f)),
            SpiritKey(940, SpiritPose(bend = 15.4f, nod = 0.0344f, flow = 0.58f, puff = -0.007f)),
            SpiritKey(1_620, SpiritPose(bend = 5.0f, nod = 0.0096f, flow = 0.26f)),
            SpiritKey(2_400, SpiritPose.REST),
        ),
    )

    private val sleeve = SpiritGesture(
        SpiritGestureId.SLEEVE,
        listOf(
            SpiritKey(0, SpiritPose.REST),
            SpiritKey(360, SpiritPose(weight = -6.4f, bend = 3.0f, flow = 1f)),
            SpiritKey(920, SpiritPose(weight = 6.4f, bend = -2.0f, flow = 1f)),
            SpiritKey(1_520, SpiritPose(weight = -4.2f, flow = 0.72f)),
            SpiritKey(2_020, SpiritPose(weight = 1.8f, flow = 0.46f)),
            SpiritKey(2_600, SpiritPose.REST),
        ),
    )

    private val tilt = SpiritGesture(
        SpiritGestureId.TILT,
        listOf(
            SpiritKey(0, SpiritPose.REST),
            SpiritKey(420, SpiritPose(turn = 13.6f, nod = -0.0096f, flow = 0.46f)),
            SpiritKey(1_400, SpiritPose(turn = 13.6f, nod = -0.0076f, flow = 0.38f)),
            SpiritKey(1_820, SpiritPose(turn = 4.0f, flow = 0.22f)),
            SpiritKey(2_240, SpiritPose.REST),
        ),
    )

    private val doze = SpiritGesture(
        SpiritGestureId.DOZE,
        listOf(
            SpiritKey(0, SpiritPose.REST),
            SpiritKey(920, SpiritPose(nod = 0.0336f, bend = 6.6f, puff = -0.009f, flow = 0.18f)),
            SpiritKey(2_640, SpiritPose(nod = 0.0384f, bend = 8.2f, puff = -0.012f, flow = 0.15f)),
            SpiritKey(3_420, SpiritPose(nod = 0.0144f, bend = 3.3f, puff = -0.005f)),
            SpiritKey(4_200, SpiritPose.REST),
        ),
    )

    private val gaze = SpiritGesture(
        SpiritGestureId.GAZE,
        listOf(
            SpiritKey(0, SpiritPose.REST),
            SpiritKey(320, SpiritPose(turn = -10.6f, flow = 0.36f)),
            SpiritKey(1_060, SpiritPose(turn = -10.6f, nod = -0.0058f, flow = 0.32f)),
            SpiritKey(1_560, SpiritPose(turn = 10.2f, nod = -0.0058f, flow = 0.36f)),
            SpiritKey(2_240, SpiritPose(turn = 10.2f, flow = 0.30f)),
            SpiritKey(2_640, SpiritPose(turn = 1.6f)),
            SpiritKey(3_020, SpiritPose.REST),
        ),
    )

    private val peek = SpiritGesture(
        SpiritGestureId.PEEK,
        listOf(
            SpiritKey(0, SpiritPose.REST),
            SpiritKey(420, SpiritPose(peek = 1f, turn = -5.7f)),
            SpiritKey(1_120, SpiritPose(peek = 1f, turn = -5.7f, nod = -0.0056f)),
            SpiritKey(1_820, SpiritPose(peek = 1f, turn = 4.6f, nod = -0.0056f)),
            SpiritKey(2_620, SpiritPose(peek = 1f, turn = 0.8f)),
            SpiritKey(3_240, SpiritPose(peek = 0.18f)),
            SpiritKey(4_000, SpiritPose.REST),
        ),
    )

    private val gestures: Map<SpiritGestureId, SpiritGesture> = listOf(
        greeting, salute, sleeve, tilt, doze, gaze, peek,
    ).associateBy { it.id }

    fun get(id: SpiritGestureId): SpiritGesture = gestures.getValue(id)

    /** 平时会随机做的动作，打招呼只出现一次，探头只在贴边时用。 */
    private val casual = listOf(
        SpiritGestureId.GAZE,
        SpiritGestureId.TILT,
        SpiritGestureId.SALUTE,
        SpiritGestureId.SLEEVE,
        SpiritGestureId.DOZE,
    )

    fun pickCasual(exclude: SpiritGestureId?, roll: Int): SpiritGestureId {
        val pool = if (exclude == null) casual else casual.filter { it != exclude }
        return pool[roll.mod(pool.size)]
    }
}
