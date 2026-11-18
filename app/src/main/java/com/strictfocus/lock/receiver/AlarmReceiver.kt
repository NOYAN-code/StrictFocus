package com.strictfocus.lock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.strictfocus.lock.data.LockMode
import com.strictfocus.lock.data.LockStateManager
import com.strictfocus.lock.service.LockdownForegroundService

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val stateManager = LockStateManager(context)
        val serviceIntent = Intent(context, LockdownForegroundService::class.java)

        when (action) {
            ACTION_LOCK_EXPIRED -> {
                if (stateManager.getMode() == LockMode.LOCKED && stateManager.getRemainingLockMs() <= 1_000L) {
                    serviceIntent.action = LockdownForegroundService.ACTION_UNLOCK_COMPLETE
                    ContextCompat.startForegroundService(context, serviceIntent)
                }
            }
            ACTION_EMERGENCY_EXPIRED -> {
                if (stateManager.getMode() == LockMode.EMERGENCY_SUSPENDED) {
                    serviceIntent.action = LockdownForegroundService.ACTION_RESUME_FROM_EMERGENCY
                    ContextCompat.startForegroundService(context, serviceIntent)
                }
            }
            ACTION_WATCHDOG_RESTART -> {
                if (stateManager.getMode() != LockMode.IDLE) {
                    serviceIntent.action = LockdownForegroundService.ACTION_RESTORE_STATE
                    ContextCompat.startForegroundService(context, serviceIntent)
                }
            }
        }
    }

    companion object {
        const val ACTION_LOCK_EXPIRED = "com.strictfocus.lock.ACTION_LOCK_EXPIRED"
        const val ACTION_EMERGENCY_EXPIRED = "com.strictfocus.lock.ACTION_EMERGENCY_EXPIRED"
        const val ACTION_WATCHDOG_RESTART = "com.strictfocus.lock.ACTION_WATCHDOG_RESTART"
    }
}
