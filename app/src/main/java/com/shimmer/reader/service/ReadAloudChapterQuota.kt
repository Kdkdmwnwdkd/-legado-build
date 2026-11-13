package com.shimmer.reader.service

/**
 * 「再读 N 章」定时的额度结算。
 *
 * 和按分钟倒计时不同，章节额度只在「一章真的读完、准备进入下一章」时扣一次。
 * 如果切章失败（已经是最后一章、加载过程被中断），调用方把额度还回去，不会白扣。
 */
object ReadAloudChapterQuota {

    /** 未启用章节定时。 */
    const val OFF = 0

    /** 单次最多允许设定的章数，防止误触输入过大。 */
    const val MAX = 50

    /**
     * @param remaining 结算后剩余的章数
     * @param stopNow 是否应当在读完当前章后停止
     */
    data class Step(val remaining: Int, val stopNow: Boolean)

    /**
     * 当前章读完，结算一次额度。
     *
     * 额度为 1 表示「读完本章就停」，此时直接把额度清零并返回停止。
     */
    fun step(quota: Int): Step = when {
        quota <= OFF -> Step(OFF, false)
        quota == 1 -> Step(OFF, true)
        else -> Step(quota - 1, false)
    }

    /** 切章失败时把扣掉的额度还回去。 */
    fun rollback(previous: Int): Int = if (previous > OFF) previous else OFF

    fun clamp(requested: Int): Int = requested.coerceIn(OFF, MAX)
}
