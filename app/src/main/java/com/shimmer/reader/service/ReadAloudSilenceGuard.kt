package com.shimmer.reader.service

/**
 * 朗读静默看门狗。
 *
 * 有些 TTS 引擎会"接活但不干活"：utterance 交出去了，既不回调开始，也不回调结束，
 * 朗读就停在那儿，用户只能暂停再播放。这里负责判断某一段是不是卡住了，
 * 判断完只给结论，重播由调用方执行。
 *
 * 判定分两种情形，避免误伤本来就慢的长段：
 * - 连「开始播报」都没回调：引擎大概是没接活，容忍时间短。
 * - 已经开播但一直没有进展：按文本长度放宽，长段允许读很久。
 *
 * 时间由调用方传入，因此可以脱离 Android 单测。
 */
internal class ReadAloudSilenceGuard(
    private val notStartedTimeoutMillis: Long = DEFAULT_NOT_STARTED_TIMEOUT_MILLIS,
    private val minTimeoutMillis: Long = DEFAULT_MIN_TIMEOUT_MILLIS,
    private val perCharMillis: Long = DEFAULT_PER_CHAR_MILLIS,
    private val maxTimeoutMillis: Long = DEFAULT_MAX_TIMEOUT_MILLIS,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES
) {

    enum class Decision {
        /** 一切正常，或当前没有待播内容。 */
        NONE,

        /** 卡住了，重播本段。 */
        RETRY,

        /** 本段重试次数用尽，跳过。 */
        GIVE_UP
    }

    private var cueIndex: Int = -1
    private var textLength: Int = 0
    private var started: Boolean = false
    private var lastActivityAt: Long = 0L
    private var retries: Int = 0

    val retriedCount: Int
        get() = retries

    /**
     * 记录"这一段交出去了"。段号变了说明进入了新段，重试次数归零。
     */
    fun onUtterance(cueIndex: Int, text: CharSequence?, now: Long) {
        if (this.cueIndex != cueIndex) {
            this.cueIndex = cueIndex
            this.retries = 0
        }
        textLength = text?.length ?: 0
        started = false
        lastActivityAt = now
    }

    /** 引擎回了「开始播报」。 */
    fun onStarted(now: Long) {
        started = true
        lastActivityAt = now
    }

    /** 引擎有任何动静（逐字回调、出错）都刷新计时。 */
    fun onActivity(now: Long) {
        lastActivityAt = now
    }

    /** 一段播完了，正常收尾。 */
    fun onCompleted(now: Long) {
        lastActivityAt = now
        retries = 0
    }

    /** 当前该等多久。短句给足底线，长段按时长放宽，但要封顶。 */
    fun timeoutMillis(): Long {
        if (!started) return notStartedTimeoutMillis
        val estimated = textLength.toLong() * perCharMillis
        return estimated.coerceIn(minTimeoutMillis, maxTimeoutMillis)
    }

    fun check(now: Long, playing: Boolean, hasPendingUtterance: Boolean): Decision {
        if (!playing || !hasPendingUtterance || cueIndex < 0) return Decision.NONE
        if (now - lastActivityAt < timeoutMillis()) return Decision.NONE
        lastActivityAt = now
        return if (retries < maxRetries) {
            retries++
            Decision.RETRY
        } else {
            Decision.GIVE_UP
        }
    }

    fun reset() {
        cueIndex = -1
        textLength = 0
        started = false
        lastActivityAt = 0L
        retries = 0
    }

    companion object {
        const val DEFAULT_NOT_STARTED_TIMEOUT_MILLIS = 12_000L
        const val DEFAULT_MIN_TIMEOUT_MILLIS = 30_000L
        const val DEFAULT_PER_CHAR_MILLIS = 450L
        const val DEFAULT_MAX_TIMEOUT_MILLIS = 600_000L
        const val DEFAULT_MAX_RETRIES = 2
    }
}
