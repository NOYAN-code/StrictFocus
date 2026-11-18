package com.strictfocus.lock.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.strictfocus.lock.R
import com.strictfocus.lock.StrictFocusApp
import com.strictfocus.lock.data.LockMode
import com.strictfocus.lock.data.LockStateManager
import com.strictfocus.lock.ui.LockActivity
import com.strictfocus.lock.ui.MainActivity
import com.strictfocus.lock.util.AlarmScheduler

class LockdownForegroundService : Service() {

    private lateinit var stateManager: LockStateManager
    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var overlayView: View? = null
    private var tvOverlayCountdown: TextView? = null
    private var btnOverlayEmergency: Button? = null
    private var emergencyConfirmArmed = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        stateManager = LockStateManager(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification("StrictFocus", "Initializing lockdown..."))

        when (intent?.action) {
            ACTION_START_LOCK -> {
                val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 45 * 60_000L)
                val endEpochMs = stateManager.startLockSession(durationMs)
                AlarmScheduler.scheduleLockExpiry(this, endEpochMs)
                enterLockedState()
            }
            ACTION_TRIGGER_EMERGENCY -> {
                if (stateManager.getMode() == LockMode.LOCKED) {
                    val emergencyEndMs = stateManager.triggerEmergencyWindow()
                    AlarmScheduler.cancelAll(this)
                    AlarmScheduler.scheduleEmergencyExpiry(this, emergencyEndMs)
                    enterEmergencyState()
                }
            }
            ACTION_RESUME_FROM_EMERGENCY -> {
                if (stateManager.getMode() == LockMode.EMERGENCY_SUSPENDED) {
                    val newEndMs = stateManager.resumeLockFromEmergency()
                    AlarmScheduler.cancelAll(this)
                    AlarmScheduler.scheduleLockExpiry(this, newEndMs)
                    enterLockedState()
                }
            }
            ACTION_UNLOCK_COMPLETE -> {
                completeUnlock()
            }
            ACTION_RESTORE_STATE, null -> {
                restoreCurrentState()
            }
        }

        return START_STICKY
    }

    private fun restoreCurrentState() {
        when (stateManager.getMode()) {
            LockMode.LOCKED -> {
                if (stateManager.getRemainingLockMs() <= 0L) {
                    completeUnlock()
                } else {
                    AlarmScheduler.scheduleLockExpiry(this, stateManager.getSessionEndEpochMs())
                    enterLockedState()
                }
            }
            LockMode.EMERGENCY_SUSPENDED -> {
                if (stateManager.getRemainingEmergencyMs() <= 0L) {
                    val newEndMs = stateManager.resumeLockFromEmergency()
                    AlarmScheduler.scheduleLockExpiry(this, newEndMs)
                    enterLockedState()
                } else {
                    AlarmScheduler.scheduleEmergencyExpiry(this, stateManager.getEmergencyEndEpochMs())
                    enterEmergencyState()
                }
            }
            LockMode.IDLE -> {
                removeOverlay()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun enterLockedState() {
        emergencyConfirmArmed = false
        showOverlay()
        launchLockActivity()
        startTicker()
        updateNotificationNow()
    }

    private fun enterEmergencyState() {
        emergencyConfirmArmed = false
        removeOverlay()
        sendBroadcast(Intent(ACTION_CLOSE_LOCK_ACTIVITY).setPackage(packageName))
        startTicker()
        updateNotificationNow()
    }

    private fun completeUnlock() {
        handler.removeCallbacks(tickRunnable)
        stateManager.clearLock()
        AlarmScheduler.cancelAll(this)
        removeOverlay()
        sendBroadcast(Intent(ACTION_CLOSE_LOCK_ACTIVITY).setPackage(packageName))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startTicker() {
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    private fun onTick() {
        when (stateManager.getMode()) {
            LockMode.LOCKED -> {
                val remaining = stateManager.getRemainingLockMs()
                if (remaining <= 0L) {
                    completeUnlock()
                    return
                }
                val formatted = LockStateManager.formatHms(remaining)
                tvOverlayCountdown?.text = formatted
                updateNotification("Focus Lock Active", "Remaining: $formatted")
                sendBroadcast(
                    Intent(ACTION_TICK)
                        .setPackage(packageName)
                        .putExtra(EXTRA_REMAINING_MS, remaining)
                        .putExtra(EXTRA_MODE, LockMode.LOCKED.name)
                )
            }
            LockMode.EMERGENCY_SUSPENDED -> {
                val remainingEmergency = stateManager.getRemainingEmergencyMs()
                if (remainingEmergency <= 0L) {
                    val newEndMs = stateManager.resumeLockFromEmergency()
                    AlarmScheduler.cancelAll(this)
                    AlarmScheduler.scheduleLockExpiry(this, newEndMs)
                    enterLockedState()
                    return
                }
                val formatted = LockStateManager.formatMs(remainingEmergency)
                updateNotification("Emergency Access (5m)", "Re-locking in $formatted")
                sendBroadcast(
                    Intent(ACTION_TICK)
                        .setPackage(packageName)
                        .putExtra(EXTRA_REMAINING_MS, remainingEmergency)
                        .putExtra(EXTRA_MODE, LockMode.EMERGENCY_SUSPENDED.name)
                )
            }
            LockMode.IDLE -> {
                completeUnlock()
            }
        }
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        if (overlayView != null) return

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_lock_screen, null)
        tvOverlayCountdown = view.findViewById(R.id.tvOverlayCountdown)
        btnOverlayEmergency = view.findViewById(R.id.btnOverlayEmergency)

        btnOverlayEmergency?.setOnClickListener {
            if (!emergencyConfirmArmed) {
                emergencyConfirmArmed = true
                btnOverlayEmergency?.text = getString(R.string.emergency_confirm_action)
                handler.postDelayed({
                    emergencyConfirmArmed = false
                    btnOverlayEmergency?.text = getString(R.string.btn_emergency)
                }, 3500L)
            } else {
                val emergencyEndMs = stateManager.triggerEmergencyWindow()
                AlarmScheduler.cancelAll(this)
                AlarmScheduler.scheduleEmergencyExpiry(this, emergencyEndMs)
                enterEmergencyState()
            }
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        runCatching {
            windowManager.addView(view, params)
            overlayView = view
        }
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            runCatching { windowManager.removeViewImmediate(view) }
        }
        overlayView = null
        tvOverlayCountdown = null
        btnOverlayEmergency = null
    }

    private fun launchLockActivity() {
        val intent = Intent(this, LockActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        startActivity(intent)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotificationNow() {
        when (stateManager.getMode()) {
            LockMode.LOCKED -> updateNotification(
                "Focus Lock Active",
                "Remaining: ${LockStateManager.formatHms(stateManager.getRemainingLockMs())}"
            )
            LockMode.EMERGENCY_SUSPENDED -> updateNotification(
                "Emergency Access (5m)",
                "Re-locking in ${LockStateManager.formatMs(stateManager.getRemainingEmergencyMs())}"
            )
            LockMode.IDLE -> Unit
        }
    }

    private fun updateNotification(title: String, text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, StrictFocusApp.LOCKDOWN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (stateManager.getMode() != LockMode.IDLE) {
            AlarmScheduler.scheduleWatchdogRestart(this, 250L)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 7001

        const val ACTION_START_LOCK = "com.strictfocus.lock.ACTION_START_LOCK"
        const val ACTION_TRIGGER_EMERGENCY = "com.strictfocus.lock.ACTION_TRIGGER_EMERGENCY"
        const val ACTION_RESUME_FROM_EMERGENCY = "com.strictfocus.lock.ACTION_RESUME_FROM_EMERGENCY"
        const val ACTION_UNLOCK_COMPLETE = "com.strictfocus.lock.ACTION_UNLOCK_COMPLETE"
        const val ACTION_RESTORE_STATE = "com.strictfocus.lock.ACTION_RESTORE_STATE"

        const val ACTION_TICK = "com.strictfocus.lock.ACTION_TICK"
        const val ACTION_CLOSE_LOCK_ACTIVITY = "com.strictfocus.lock.ACTION_CLOSE_LOCK_ACTIVITY"

        const val EXTRA_DURATION_MS = "extra_duration_ms"
        const val EXTRA_REMAINING_MS = "extra_remaining_ms"
        const val EXTRA_MODE = "extra_mode"
    }
}
