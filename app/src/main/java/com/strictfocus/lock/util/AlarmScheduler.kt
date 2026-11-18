package com.strictfocus.lock.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.strictfocus.lock.receiver.AlarmReceiver

object AlarmScheduler {

    private const val REQ_LOCK_EXPIRED = 1001
    private const val REQ_EMERGENCY_EXPIRED = 1002
    private const val REQ_WATCHDOG = 1003

    fun scheduleLockExpiry(context: Context, triggerAtEpochMs: Long) {
        scheduleExact(context, AlarmReceiver.ACTION_LOCK_EXPIRED, REQ_LOCK_EXPIRED, triggerAtEpochMs)
    }

    fun scheduleEmergencyExpiry(context: Context, triggerAtEpochMs: Long) {
        scheduleExact(context, AlarmReceiver.ACTION_EMERGENCY_EXPIRED, REQ_EMERGENCY_EXPIRED, triggerAtEpochMs)
    }

    fun scheduleWatchdogRestart(context: Context, delayMs: Long = 500L) {
        scheduleExact(
            context,
            AlarmReceiver.ACTION_WATCHDOG_RESTART,
            REQ_WATCHDOG,
            System.currentTimeMillis() + delayMs
        )
    }

    fun cancelAll(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        listOf(
            AlarmReceiver.ACTION_LOCK_EXPIRED to REQ_LOCK_EXPIRED,
            AlarmReceiver.ACTION_EMERGENCY_EXPIRED to REQ_EMERGENCY_EXPIRED,
            AlarmReceiver.ACTION_WATCHDOG_RESTART to REQ_WATCHDOG
        ).forEach { (action, reqCode) ->
            val pi = buildPendingIntent(context, action, reqCode)
            alarmManager.cancel(pi)
        }
    }

    private fun scheduleExact(context: Context, action: String, requestCode: Int, triggerAtEpochMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildPendingIntent(context, action, requestCode)
        val safeTrigger = triggerAtEpochMs.coerceAtLeast(System.currentTimeMillis() + 250L)

        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeTrigger, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeTrigger, pendingIntent)
        }
    }

    private fun buildPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
