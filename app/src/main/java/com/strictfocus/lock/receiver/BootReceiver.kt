package com.strictfocus.lock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.strictfocus.lock.data.LockMode
import com.strictfocus.lock.data.LockStateManager
import com.strictfocus.lock.service.LockdownForegroundService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val stateManager = LockStateManager(context)
            if (stateManager.getMode() != LockMode.IDLE) {
                val serviceIntent = Intent(context, LockdownForegroundService::class.java).apply {
                    this.action = LockdownForegroundService.ACTION_RESTORE_STATE
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
