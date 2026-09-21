package io.legado.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Rebuilds the single automatic-task wake-up after system time changes. */
class AutoTaskBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> AutoTaskService.restoreSchedule(context)
        }
    }
}
