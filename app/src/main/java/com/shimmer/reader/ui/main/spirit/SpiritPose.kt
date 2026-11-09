package com.shimmer.reader.ui.main.spirit

/**
 * 书灵此刻的姿势。
 *
 * 这里只描述"意图"——头转多少、腰弯多少、飘带多活跃，不涉及任何像素坐标。
 * 具体怎么落到网格上由 [SpiritMesh] 决定，这样加一个动作不需要动渲染代码。
 *
 * 字段刻意按水墨人物的关节来命名，而不是按贴图部件：
 * 我们没有可以单独旋转的手臂或鱼鳍，只有一条从脚底连到头顶的"身子"。
 */
data class SpiritPose(
    /** 头往左右转的角度。正数朝右。 */
    val turn: Float = 0f,
    /** 头往下沉的比例，相对身高。0.03 大约是低 3% 身高。 */
    val nod: Float = 0f,
    /** 上身前倾的角度，站着拱手时用。 */
    val bend: Float = 0f,
    /** 重心往一侧压的角度，换脚站立时用。 */
    val weight: Float = 0f,
    /** 下摆与飘带的活跃度，0 完全静止，1 像有风。 */
    val flow: Float = 0f,
    /** 呼吸时的纵向伸缩量，0.006 大约是肉眼刚好能察觉。 */
    val puff: Float = 0f,
    /** 收在屏幕边上时探头出来的程度，0 缩回去，1 探到最多。 */
    val peek: Float = 0f,
    /** 被按住时压扁的量。 */
    val press: Float = 0f,
) {

    operator fun plus(other: SpiritPose): SpiritPose = SpiritPose(
        turn = turn + other.turn,
        nod = nod + other.nod,
        bend = bend + other.bend,
        weight = weight + other.weight,
        flow = flow + other.flow,
        puff = puff + other.puff,
        peek = peek + other.peek,
        press = press + other.press,
    )

    /** 逐字段线性过渡，[t] 会被夹到 0..1。 */
    fun towards(target: SpiritPose, t: Float): SpiritPose {
        val k = t.coerceIn(0f, 1f)
        fun mix(a: Float, b: Float) = a + (b - a) * k
        return SpiritPose(
            turn = mix(turn, target.turn),
            nod = mix(nod, target.nod),
            bend = mix(bend, target.bend),
            weight = mix(weight, target.weight),
            flow = mix(flow, target.flow),
            puff = mix(puff, target.puff),
            peek = mix(peek, target.peek),
            press = mix(press, target.press),
        )
    }

    internal fun toArray(): FloatArray =
        floatArrayOf(turn, nod, bend, weight, flow, puff, peek, press)

    companion object {
        /** 站着不动的基准姿势。 */
        val REST = SpiritPose()

        val FIELDS = 8

        internal fun of(values: FloatArray): SpiritPose = SpiritPose(
            turn = values[0],
            nod = values[1],
            bend = values[2],
            weight = values[3],
            flow = values[4],
            puff = values[5],
            peek = values[6],
            press = values[7],
        )
    }
}
