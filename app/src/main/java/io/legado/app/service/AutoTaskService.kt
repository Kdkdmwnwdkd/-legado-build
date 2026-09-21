package io.legado.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRule
import io.legado.app.model.AutoTaskRunStatus
import io.legado.app.model.AutoTaskSchedule
import io.legado.app.utils.CronSchedule
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.startForegroundServiceCompat
import io.legado.app.utils.startService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single owner of automatic-task scheduling.
 *
 * API 35 and newer use one exact/inexact alarm per next occurrence. Older
 * releases keep one data-sync foreground service and sleep until the next
 * occurrence. Both paths use the same due-task and execution code, so behavior
 * does not diverge by Android version.
 */
class AutoTaskService : BaseService() {

    companion object {
        private const val ALARM_REQUEST_CODE = 100108
        private const val IDLE_RECHECK_MS = 60_000L
        private const val MIN_DELAY_MS = 1_000L
        private const val FIRST_RUN_GRACE_MS = 5 * 60_000L

        @Volatile
        var isRun: Boolean = false
            private set

        fun start(context: Context) {
            dispatch(context, IntentAction.start, foreground = true)
        }

        fun stop(context: Context) {
            context.startService<AutoTaskService> { action = IntentAction.stop }
        }

        fun refresh(context: Context) {
            dispatch(context, IntentAction.refreshSchedule, foreground = true)
        }

        /** Runs one saved rule through the same coordinator used by cron. */
        fun runNow(context: Context, taskId: String) {
            if (taskId.isBlank()) return
            val intent = Intent(context, AutoTaskService::class.java).apply {
                action = IntentAction.runAutoTask
                putExtra(EXTRA_TASK_ID, taskId)
            }
            context.startForegroundServiceCompat(intent)
        }

        /** Rebuilds the wake-up after boot, a clock change, or an app update. */
        fun restoreSchedule(context: Context) {
            if (!context.getPrefBoolean(PreferKey.autoTaskService)) {
                cancelAlarm(context)
                return
            }
            if (isAlarmMode()) {
                scheduleAlarm(context, System.currentTimeMillis())
            } else {
                runCatching {
                    dispatch(context, IntentAction.refreshSchedule, foreground = true)
                }.onFailure { error ->
                    AppLog.put("AutoTask restore failed\n${error.localizedMessage}", error)
                }
            }
        }

        private fun isAlarmMode(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

        private fun scheduleAlarm(context: Context, now: Long) {
            val next = AutoTask.all()
                .asSequence()
                .filter { it.enable }
                .mapNotNull { rule ->
                    CronSchedule.parse(rule.cron?.trim().orEmpty())?.nextTimeAfter(now)
                }
                .minOrNull()
            if (next == null) {
                cancelAlarm(context)
                return
            }
            val triggerAt = next.coerceAtLeast(System.currentTimeMillis() + MIN_DELAY_MS)
            val manager = context.getSystemService(AlarmManager::class.java) ?: return
            val pendingIntent = buildAlarmPendingIntent(context)
            runCatching {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        manager.canScheduleExactAlarms() -> manager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent
                    )
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> manager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent
                    )
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT -> manager.setExact(
                        AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent
                    )
                    else -> manager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
            }.onFailure { error ->
                AppLog.put("AutoTask restore alarm failed\n${error.localizedMessage}", error)
            }
        }

        private fun cancelAlarm(context: Context) {
            context.getSystemService(AlarmManager::class.java)
                ?.cancel(buildAlarmPendingIntent(context))
        }

        private fun buildAlarmPendingIntent(context: Context): PendingIntent {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                }
            return PendingIntent.getService(
                context,
                ALARM_REQUEST_CODE,
                Intent(context, AutoTaskService::class.java).apply {
                    action = IntentAction.refreshSchedule
                },
                flags
            )
        }

        private const val EXTRA_TASK_ID = "autoTaskId"

        private fun dispatch(context: Context, action: String, foreground: Boolean) {
            val intent = Intent(context, AutoTaskService::class.java).apply {
                this.action = action
            }
            if (foreground) {
                context.startForegroundServiceCompat(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val taskLock = Mutex()
    private val runInProgress = AtomicBoolean(false)
    private var oneShotJob: Job? = null
    private var loopJob: Job? = null
    private var notificationText: String = ""
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val executionCoordinator by lazy {
        AutoTask.executionCoordinator(this)
    }

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdWeb)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.auto_task_service))
            .setContentText(notificationText.ifBlank { getString(R.string.service_starting) })
            .addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<AutoTaskService>(IntentAction.stop)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == IntentAction.stop) {
            disableAndStop()
            return START_NOT_STICKY
        }

        super.onStartCommand(intent, flags, startId)
        val manualTaskId = intent?.takeIf { it.action == IntentAction.runAutoTask }
            ?.getStringExtra(EXTRA_TASK_ID)
        if (!getPrefBoolean(PreferKey.autoTaskService) && manualTaskId.isNullOrBlank()) {
            cancelNextAlarm()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        if (useAlarmMode()) {
            launchOneShot(startId, manualTaskId = manualTaskId)
        } else {
            ensureLegacyLoop()
            // Run immediately for an explicit start/refresh. The loop itself
            // remains the only long-lived scheduler on pre-35 devices.
            if (manualTaskId != null || intent?.action == IntentAction.start ||
                intent?.action == IntentAction.refreshSchedule || intent == null
            ) {
                launchOneShot(startId, stopAfter = false, manualTaskId = manualTaskId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        oneShotJob?.cancel()
        loopJob?.cancel()
        oneShotJob = null
        loopJob = null
        isRun = false
        runInProgress.set(false)
        notificationManager.cancel(NotificationId.AutoTaskService)
        postServiceEvent()
        super.onDestroy()
    }

    override fun startForegroundNotification() {
        val notification = notificationBuilder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Tasks may legitimately run for several minutes. The short-service
            // type is platform-limited and would terminate valid work.
            startForeground(
                NotificationId.AutoTaskService,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NotificationId.AutoTaskService, notification)
        }
    }

    private fun launchOneShot(
        startId: Int,
        stopAfter: Boolean = useAlarmMode(),
        manualTaskId: String? = null
    ) {
        if (oneShotJob?.isActive == true) return
        oneShotJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                taskLock.withLock {
                    if (!getPrefBoolean(PreferKey.autoTaskService) && manualTaskId.isNullOrBlank()) {
                        return@withLock
                    }
                    if (useAlarmMode() && manualTaskId.isNullOrBlank() &&
                        getPrefBoolean(PreferKey.autoTaskService)
                    ) {
                        // Schedule before automatic execution so a service
                        // restart cannot lose the next wake-up. A manual run
                        // with the global switch off must remain one-shot.
                        scheduleNextFutureAlarm(System.currentTimeMillis())
                    }
                    if (!manualTaskId.isNullOrBlank()) {
                        AutoTask.get(manualTaskId)?.let { runTask(it) }
                    } else {
                        processDueTasks()
                    }
                    if (useAlarmMode() && getPrefBoolean(PreferKey.autoTaskService)) {
                        // A task may have changed lastRunAt; recompute from the
                        // fresh state and replace the one-shot alarm.
                        scheduleNextFutureAlarm(System.currentTimeMillis())
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLog.put("AutoTask scheduler failed\n${error.localizedMessage}", error)
            } finally {
                if (stopAfter && useAlarmMode()) stopSelfResult(startId)
                oneShotJob = null
            }
        }
    }

    private fun ensureLegacyLoop() {
        if (loopJob?.isActive == true) return
        loopJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                while (isActive && getPrefBoolean(PreferKey.autoTaskService)) {
                    taskLock.withLock { processDueTasks() }
                    val delayMs = nextDelayMs()
                    delay(delayMs)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLog.put("AutoTask loop failed\n${error.localizedMessage}", error)
            } finally {
                loopJob = null
                if (!getPrefBoolean(PreferKey.autoTaskService)) {
                    stopSelf()
                }
            }
        }
    }

    private suspend fun processDueTasks() {
        val rules = AutoTask.all()
        if (rules.isEmpty()) {
            updateNotification(getString(R.string.auto_task_no_task))
            if (useAlarmMode()) cancelNextAlarm()
            return
        }
        val enabled = rules.filter { it.enable }
        if (enabled.isEmpty()) {
            updateNotification(getString(R.string.auto_task_no_enabled))
            if (useAlarmMode()) cancelNextAlarm()
            return
        }

        var ran = false
        val now = System.currentTimeMillis()
        enabled.forEach { rule ->
            val expression = rule.cron?.trim().orEmpty()
            val schedule = CronSchedule.parse(expression)
            if (schedule == null) {
                markCronError(rule)
                return@forEach
            }
            val next = AutoTaskSchedule.nextFor(
                rule = rule,
                nowEpochMs = now,
                firstRunGraceMs = FIRST_RUN_GRACE_MS
            )
            if (next != null && next <= now) {
                ran = true
                runTask(rule)
            }
        }
        if (!ran) updateNotification(getString(R.string.auto_task_running_state))
    }

    private suspend fun runTask(rule: AutoTaskRule) {
        if (!runInProgress.compareAndSet(false, true)) return
        isRun = true
        postServiceEvent()
        updateNotification(getString(R.string.auto_task_running, rule.name))
        val startedAt = System.currentTimeMillis()
        val logLines = ArrayList<String>()
        try {
            val result = executionCoordinator.run(
                rule = rule,
                logger = { line ->
                    if (logLines.size < 64) logLines += line
                    AppLog.put("AutoTask[${rule.id}] ${rule.name}: $line")
                }
            )
            val finishedAt = result.finishedAt.coerceAtLeast(startedAt)
            val detail = result.detail ?: result.summaries.joinToString(" | ")
            val error = result.error
            val lastLog = if (result.status == AutoTaskRunStatus.SUCCESS ||
                result.status == AutoTaskRunStatus.NO_ACTION
            ) {
                AutoTask.buildLastLog(logLines, detail, result.durationMs, finishedAt)
            } else {
                AutoTask.buildErrorLog(
                    error ?: result.status.name,
                    null,
                    finishedAt
                )
            }
            AutoTask.updateRuntime(rule.id) {
                it.copy(
                    lastRunAt = finishedAt,
                    lastResult = detail.take(4_000),
                    lastError = error,
                    lastLog = lastLog
                )
            }
            if (error.isNullOrBlank()) {
                updateNotification(getString(R.string.auto_task_last_run, timeFormat.format(Date(finishedAt))))
            } else {
                updateNotification(getString(R.string.auto_task_failed, error))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val finishedAt = System.currentTimeMillis()
            val message = error.localizedMessage ?: error.toString()
            AutoTask.updateRuntime(rule.id) {
                it.copy(
                    lastRunAt = finishedAt,
                    lastError = message,
                    lastLog = AutoTask.buildErrorLog(message, error, finishedAt)
                )
            }
            updateNotification(getString(R.string.auto_task_failed, message))
            AppLog.put("AutoTask[${rule.id}] ${rule.name} failed: $message", error)
        } finally {
            isRun = false
            runInProgress.set(false)
            postServiceEvent()
        }
    }

    private fun markCronError(rule: AutoTaskRule) {
        val message = getString(R.string.auto_task_cron_invalid)
        if (rule.lastError == message) return
        val now = System.currentTimeMillis()
        AutoTask.updateRuntime(rule.id) {
            it.copy(lastError = message, lastLog = AutoTask.buildErrorLog(message, null, now))
        }
    }

    private fun nextDelayMs(): Long {
        val now = System.currentTimeMillis()
        val next = AutoTask.all()
            .asSequence()
            .filter { it.enable }
            .mapNotNull { rule ->
                CronSchedule.parse(rule.cron?.trim().orEmpty())?.nextTimeAfter(now)
            }
            .minOrNull()
        return (next?.minus(now) ?: IDLE_RECHECK_MS).coerceAtLeast(MIN_DELAY_MS)
    }

    private fun scheduleNextFutureAlarm(now: Long) {
        if (!useAlarmMode() || !getPrefBoolean(PreferKey.autoTaskService)) return
        val next = AutoTask.all()
            .asSequence()
            .filter { it.enable }
            .mapNotNull { rule ->
                CronSchedule.parse(rule.cron?.trim().orEmpty())?.nextTimeAfter(now)
            }
            .minOrNull()
        if (next == null) {
            cancelNextAlarm()
            return
        }
        val triggerAt = next.coerceAtLeast(System.currentTimeMillis() + MIN_DELAY_MS)
        val manager = getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = buildAlarmPendingIntent()
        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    manager.canScheduleExactAlarms() -> manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT -> manager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
                else -> manager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
            AppLog.put("AutoTask next run at ${timeFormat.format(Date(triggerAt))}")
        }.onFailure { error ->
            AppLog.put("AutoTask schedule alarm failed\n${error.localizedMessage}", error)
        }
    }

    private fun cancelNextAlarm() {
        getSystemService(AlarmManager::class.java)?.cancel(buildAlarmPendingIntent())
    }

    private fun buildAlarmPendingIntent(): PendingIntent {
        return buildAlarmPendingIntent(this)
    }

    private fun disableAndStop() {
        putPrefBoolean(PreferKey.autoTaskService, false)
        cancelNextAlarm()
        oneShotJob?.cancel()
        loopJob?.cancel()
        oneShotJob = null
        loopJob = null
        stopSelf()
    }

    private fun useAlarmMode(): Boolean =
        isAlarmMode()

    private fun updateNotification(text: String) {
        notificationText = text
        notificationBuilder.setContentText(text)
        notificationManager.notify(NotificationId.AutoTaskService, notificationBuilder.build())
    }

    private fun postServiceEvent() {
        // EventBus is deliberately best-effort; the Room Flow remains the
        // source of truth for the management screen.
        io.legado.app.utils.postEvent(EventBus.AUTO_TASK_SERVICE, isRun.toString())
    }
}
