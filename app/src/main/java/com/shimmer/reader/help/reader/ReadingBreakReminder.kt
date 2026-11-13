package com.shimmer.reader.help.reader

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import com.shimmer.reader.R
import com.shimmer.reader.constant.PreferKey
import com.shimmer.reader.help.config.AppConfig
import com.shimmer.reader.lib.dialogs.alert
import com.shimmer.reader.service.BaseReadAloudService
import com.shimmer.reader.utils.getPrefString
import com.shimmer.reader.utils.putPrefString
import splitties.init.appCtx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 连续阅读计时。
 *
 * 只看「阅读界面在前台」的时长：锁屏、切到别的应用、去设置页都会暂停累计，
 * 听书时不计时（眼睛本来就离开了屏幕）。时间单独放在一个纯类里，方便用假时钟做测试。
 */
internal class ReadingBreakTimer(private var limitMs: Long) {

    private var resumedAt = 0L
    private var accumulatedMs = 0L

    fun setLimit(limit: Long) {
        limitMs = limit
    }

    fun onResumed(now: Long) {
        if (resumedAt == 0L) {
            resumedAt = now
        }
    }

    fun onPaused(now: Long) {
        if (resumedAt != 0L) {
            accumulatedMs += (now - resumedAt).coerceAtLeast(0L)
            resumedAt = 0L
        }
    }

    fun elapsed(now: Long): Long {
        val running = if (resumedAt != 0L) (now - resumedAt).coerceAtLeast(0L) else 0L
        return accumulatedMs + running
    }

    fun remaining(now: Long): Long = (limitMs - elapsed(now)).coerceAtLeast(0L)

    /** 提醒过之后重新从头计时，接着读的那段不再算进上一轮 */
    fun reset(now: Long) {
        accumulatedMs = 0L
        if (resumedAt != 0L) {
            resumedAt = now
        }
    }
}

/**
 * 阅读休息提醒：连续看够设定时长就弹一次，让眼睛歇一歇。
 *
 * 默认关闭，需要在「其它设置 → 阅读休息提醒」里选一个时长才会生效。
 */
object ReadingBreakReminder {

    private const val CHECK_DELAY_MS = 1_000L
    private const val BUSY_RETRY_MS = 60_000L

    private val handler = Handler(Looper.getMainLooper())
    private val timer = ReadingBreakTimer(0L)
    private var host: FragmentActivity? = null
    private val checkTask = Runnable { check() }

    fun onReaderResumed(activity: FragmentActivity) {
        val limit = limitMs()
        handler.removeCallbacks(checkTask)
        if (limit <= 0L || isSkippedToday()) {
            host = null
            return
        }
        host = activity
        timer.setLimit(limit)
        timer.onResumed(SystemClock.elapsedRealtime())
        schedule()
    }

    fun onReaderPaused() {
        timer.onPaused(SystemClock.elapsedRealtime())
        handler.removeCallbacks(checkTask)
        host = null
    }

    private fun schedule() {
        val remaining = timer.remaining(SystemClock.elapsedRealtime())
        handler.postDelayed(checkTask, remaining.coerceAtLeast(CHECK_DELAY_MS))
    }

    private fun check() {
        val activity = host ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        if (limitMs() <= 0L || isSkippedToday()) return
        // 听书时眼睛不在屏幕上，不算连续阅读
        if (BaseReadAloudService.isRun) {
            handler.postDelayed(checkTask, BUSY_RETRY_MS)
            return
        }
        // 界面上正开着别的对话框就不插队，过一分钟再看
        if (activity.supportFragmentManager.fragments.any { it is DialogFragment }) {
            handler.postDelayed(checkTask, BUSY_RETRY_MS)
            return
        }
        val minutes = (limitMs() / 60_000L).toInt()
        runCatching {
            activity.alert(
                activity.getString(R.string.reading_break_title),
                activity.getString(R.string.reading_break_message, minutes)
            ) {
                // 点外面或者返回键都等同于「接着读」，不强行拦着
                positiveButton(R.string.reading_break_continue)
                neutralButton(R.string.reading_break_skip_today) { skipToday() }
            }
        }
        // 不论用户点了哪个按钮，下一轮都从头计时
        timer.reset(SystemClock.elapsedRealtime())
        schedule()
    }

    private fun limitMs(): Long = AppConfig.readingBreakMinutes * 60_000L

    private fun isSkippedToday(): Boolean = appCtx.getPrefString(PreferKey.readingBreakSkipDate) == today()

    private fun skipToday() {
        appCtx.putPrefString(PreferKey.readingBreakSkipDate, today())
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
}
