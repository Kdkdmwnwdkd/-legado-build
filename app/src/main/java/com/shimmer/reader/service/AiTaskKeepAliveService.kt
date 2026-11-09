package com.shimmer.reader.service

import android.app.PendingIntent
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.shimmer.reader.R
import com.shimmer.reader.base.BaseService
import com.shimmer.reader.constant.AppConst
import com.shimmer.reader.constant.NotificationId
import com.shimmer.reader.help.ai.AiTaskKeepAlive
import com.shimmer.reader.ui.main.MainActivity
import com.shimmer.reader.utils.activityPendingIntent
import splitties.systemservices.notificationManager
import splitties.systemservices.powerManager

class AiTaskKeepAliveService : BaseService() {

    private val wakeLock: PowerManager.WakeLock by lazy {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "legado:AiTaskKeepAliveService")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releaseWakeLock()
                stopSelf()
                return super.onStartCommand(intent, flags, startId)
            }
            ACTION_REFRESH -> {
                syncWakeLock()
                upNotification()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun startForegroundNotification() {
        syncWakeLock()
        startForeground(NotificationId.AiTaskService, createNotification().build())
    }

    private fun upNotification() {
        syncWakeLock()
        notificationManager.notify(NotificationId.AiTaskService, createNotification().build())
    }

    private fun syncWakeLock() {
        if (AiTaskKeepAlive.activeCount > 0) {
            if (!wakeLock.isHeld) {
                wakeLock.acquire()
            }
        } else {
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    private fun createNotification(): NotificationCompat.Builder {
        val count = AiTaskKeepAlive.activeCount.coerceAtLeast(1)
        val tasks = AiTaskKeepAlive.activeTaskSnapshot()
        val text = if (count > 1) {
            tasks.take(3)
                .joinToString("\n") { "${it.title}：${it.displayText}" }
                .ifBlank { getString(R.string.svc_ai_tasks_running, count) }
        } else {
            AiTaskKeepAlive.content
        }
        val title = if (count > 1) getString(R.string.svc_ai_tasks_processing, count)
        else AiTaskKeepAlive.title
        return NotificationCompat.Builder(this, AppConst.channelIdAiTask)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(activityPendingIntent<MainActivity>("aiTask"))
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
        notificationManager.cancel(NotificationId.AiTaskService)
    }

    companion object {
        const val ACTION_REFRESH = "com.shimmer.reader.action.AI_TASK_REFRESH"
        const val ACTION_STOP = "com.shimmer.reader.action.AI_TASK_STOP"
    }
}
