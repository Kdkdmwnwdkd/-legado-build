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

    val durationMs: Int get() = keys.last().atMs

    /** 取动作进行到 [elapsedMs] 时的姿势，超出范围返回静止。 */
    fun poseAt(elapsedMs: Int): SpiritPose {
        if (elapsedMs <= 0) return keys.first().pose
        if (elapsedMs >= durationMs) return SpiritPose.REST
        var index = 0
        while (index < keys.size - 2 && keys[index + 1].atMs <= elapsedMs) index++
        val current = keys[index]
        val next = keys[index + 1]
        val span = (next.atMs - current.atMs).coerceAtLeast(1)
        val t = (elapsedMs - current.atMs).toFloat() / span

        // 前一个点与后一个点决定两端切线，端点外侧用自身补齐，避免起手就抖。
        val before = keys.getOrElse(index - 1) { current }
        val after = keys.getOrElse(index + 2) { next }
        val a = current.pose.toArray()
        val b = next.pose.toArray()
        val previous = before.pose.toArray()
        val following = after.pose.toArray()
        val slopeIn = FloatArray(SpiritPose.FIELDS) { i -> (b[i] - previous[i]) * 0.5f }
        val slopeOut = FloatArray(SpiritPose.FIELDS) { i -> (following[i] - a[i]) * 0.5f }

        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2f * t3 - 3f * t2 + 1f
        val h10 = t3 - 2f * t2 + t
        val h01 = -2f * t3 + 3f * t2
        val h11 = t3 - t2

        val out = FloatArray(SpiritPose.FIELDS) { i ->
            h00 * a[i] + h10 * slopeIn[i] + h01 * b[i] + h11 * slopeOut[i]
        }
        return SpiritPose.of(out)
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
 */
object SpiritRepertoire {

    private val greeting = SpiritGesture(
        SpiritGestureId.GREETING,
        listOf(
            SpiritKey(0, SpiritPose.REST),
            SpiritKey(260, SpiritPose(turn = -7.5f, nod = -0.0160f, puff = 0.006f)),
            SpiritKey(760, SpiritPose(turn = -7.5f, nod = -0.0136f, puff = 0.004f)),
            SpiritKey(1_180, SpiritPose(turn = 7.9f, nod = -0.0160f, puff = 0.006f)),
            SpiritKey(1_760, SpiritPose(turn = 7.9f, nod = -0.0104f)),
            SpiritKey(2_240, SpiritPose(turn = 1.6f, nod = 0.0024f)),
            SpiritKey(2_800, SpiritPose.REST),
        ),
    )

    private val salute = SpiritGesture(
        SpiritGestureId.SALUTE,
        listOf(
            SpiritKey(0, SpiritPose.REST),
            SpiritKey(300, SpiritPose(bend = 11.9f, nod = 0.0260f, flow = 0.55f, puff = -0.005f)),
            SpiritKey(940, SpiritPose(bend = 12.9f, nod = 0.0288f, flow = 0.5f, puff = -0.006f)),
            SpiritKey(1_620, SpiritPose(bend = 4.2f, nod = 0.0080f, flow = 0.22f)),
            SpiritKey(2_400, SpiritPose.REST),
        ),
    )

    private val sleeve = SpiritGesture(
        SpiritGestureId.SLEEVE,
        listOf(
            SpiritKey(0, SpiritPose.REST),
            SpiritKey(360, SpiritPose(weight = -5.0f, bend = 2.5f, flow = 1f)),
            SpiritKey(920, SpiritPose(weight = 5.0f, bend = -1.7f, flow = 1f)),
            SpiritKey(1_520, SpiritPose(weight = -3.3f, flow = 0.7f)),
            SpiritKey(2_020, SpiritPose(weight = 1.4f, flow = 0.42f)),
            SpiritKey(2_600, SpiritPose.REST),
        ),
    )

    private val tilt = SpiritGesture(
        SpiritGestureId.TILT,
        listOf(
            SpiritKey(0, SpiritPose.REST),
            SpiritKey(420, SpiritPose(turn = 12.0f, nod = -0.0080f, flow = 0.4f)),
            SpiritKey(1_400, SpiritPose(turn = 12.0f, nod = -0.0064f, flow = 0.32f)),
            SpiritKey(1_820, SpiritPose(turn = 3.5f, flow = 0.18f)),
            SpiritKey(2_240, SpiritPose.REST),
        ),
    )

    private val doze = SpiritGesture(
        SpiritGestureId.DOZE,
        listOf(
            SpiritKey(0, SpiritPose.REST),
            SpiritKey(920, SpiritPose(nod = 0.0280f, bend = 5.6f, puff = -0.008f, flow = 0.15f)),
            SpiritKey(2_640, SpiritPose(nod = 0.0320f, bend = 7.0f, puff = -0.011f, flow = 0.12f)),
            SpiritKey(3_420, SpiritPose(nod = 0.0120f, bend = 2.8f, puff = -0.004f)),
            SpiritKey(4_200, SpiritPose.REST),
        ),
    )

    private val gaze = SpiritGesture(
        SpiritGestureId.GAZE,
        listOf(
            SpiritKey(0, SpiritPose.REST),
            SpiritKey(320, SpiritPose(turn = -9.2f, flow = 0.3f)),
            SpiritKey(1_060, SpiritPose(turn = -9.2f, nod = -0.0048f, flow = 0.26f)),
            SpiritKey(1_560, SpiritPose(turn = 8.7f, nod = -0.0048f, flow = 0.3f)),
            SpiritKey(2_240, SpiritPose(turn = 8.7f, flow = 0.24f)),
            SpiritKey(2_640, SpiritPose(turn = 1.4f)),
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
