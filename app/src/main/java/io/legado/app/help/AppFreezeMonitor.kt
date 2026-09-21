package io.legado.app.help

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.LogUtils

object AppFreezeMonitor {

    private const val TAG = "AppFreezeMonitor"

    /**
     * Watchdog that captures the main thread's own stack while it is still stuck.
     *
     * A blocked main thread produces no application logs and the system's ANR trace lands
     * in /data/anr, which an unrooted device cannot read — so the one thing needed to
     * identify the culprit is exactly the thing that never reaches us. Ping the main
     * thread on a fixed interval; when a ping has not come back in time, dump the frames
     * it is sitting on to logcat (tag AppFreezeMonitor) where any log reader can see them.
     */
    private object MainThreadStallWatchdog {

        private const val PING_INTERVAL_MS = 1000L
        private const val STALL_THRESHOLD_MS = 2000L
        private const val REPEAT_DUMP_MS = 3000L

        private val mainHandler = Handler(android.os.Looper.getMainLooper())
        @Volatile private var pingSeq = 0L
        @Volatile private var pongSeq = 0L
        @Volatile private var pingSentAt = 0L
        @Volatile private var lastDumpAt = 0L
        private var started = false

        fun start() {
            if (started) return
            started = true
            handler.post(object : Runnable {
                override fun run() {
                    check()
                    handler.postDelayed(this, PING_INTERVAL_MS)
                }
            })
        }

        private fun check() {
            val now = SystemClock.uptimeMillis()
            if (pingSeq == pongSeq) {
                pingSeq++
                pingSentAt = now
                val seq = pingSeq
                mainHandler.post { pongSeq = seq }
                return
            }
            val stalledFor = now - pingSentAt
            if (stalledFor < STALL_THRESHOLD_MS) return
            if (now - lastDumpAt < REPEAT_DUMP_MS) return
            lastDumpAt = now
            runCatching {
                val main = android.os.Looper.getMainLooper().thread
                val frames = main.stackTrace
                val header = "MAIN THREAD STALLED ${stalledFor}ms state=${main.state} frames=${frames.size}"
                LogUtils.d(TAG, header)
                android.util.Log.w(TAG, header)
                // One frame per line: logcat truncates long single messages, and the top
                // frames are exactly what identifies the blocking call.
                frames.take(45).forEachIndexed { index, frame ->
                    val line = "  #${index}  $frame"
                    LogUtils.d(TAG, line)
                    android.util.Log.w(TAG, line)
                }
            }
        }
    }

    val handler by lazy {
        Handler(HandlerThread("AppFreezeMonitor").apply { start() }.looper)
    }

    val screenStatusReceiver by lazy {
        ScreenStatusReceiver()
    }

    private var registeredReceiver = false

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun init(context: Context) {
        if (!AppConfig.recordLog) {
            if (registeredReceiver) {
                registeredReceiver = false
                context.unregisterReceiver(screenStatusReceiver)
            }
            return
        }

        if (!registeredReceiver) {
            registeredReceiver = true
            context.registerReceiver(screenStatusReceiver, screenStatusReceiver.filter)
        }
        MainThreadStallWatchdog.start()

        var previous = SystemClock.uptimeMillis()

        val runnable = object : Runnable {
            override fun run() {
                val current = SystemClock.uptimeMillis()
                val elapsed = current - previous
                val extra = elapsed - 3000

                if (extra > 300) {
                    LogUtils.d(TAG, "检测到应用被系统冻结，时长：$extra 毫秒")
                }

                previous = current

                if (AppConfig.recordLog) {
                    handler.postDelayed(this, 3000)
                }
            }
        }
        handler.postDelayed(runnable, 3000)
    }

    class ScreenStatusReceiver : BroadcastReceiver() {

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> LogUtils.d(TAG, "SCREEN_ON")
                Intent.ACTION_SCREEN_OFF -> LogUtils.d(TAG, "SCREEN_OFF")
            }
        }
    }

}
