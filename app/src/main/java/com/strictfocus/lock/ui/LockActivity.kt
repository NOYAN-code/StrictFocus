package com.strictfocus.lock.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.strictfocus.lock.R
import com.strictfocus.lock.data.LockMode
import com.strictfocus.lock.data.LockStateManager
import com.strictfocus.lock.databinding.ActivityLockBinding
import com.strictfocus.lock.service.LockdownForegroundService

class LockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockBinding
    private lateinit var stateManager: LockStateManager

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                LockdownForegroundService.ACTION_TICK -> {
                    val remaining = intent.getLongExtra(LockdownForegroundService.EXTRA_REMAINING_MS, 0L)
                    binding.tvLockCountdown.text = LockStateManager.formatHms(remaining)
                }
                LockdownForegroundService.ACTION_CLOSE_LOCK_ACTIVITY -> {
                    finishAndRemoveTask()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        stateManager = LockStateManager(this)
        configureImmersiveLockWindow()

        // Consume Back gesture/button completely
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Intentionally blocked during active lockdown
            }
        })

        binding.tvLockCountdown.text = LockStateManager.formatHms(stateManager.getRemainingLockMs())

        binding.btnEmergencyLock.setOnClickListener {
            showEmergencyConfirmation()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(LockdownForegroundService.ACTION_TICK)
            addAction(LockdownForegroundService.ACTION_CLOSE_LOCK_ACTIVITY)
        }
        ContextCompat.registerReceiver(
            this,
            serviceReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        if (stateManager.getMode() != LockMode.LOCKED) {
            finish()
            return
        }
        hideSystemBars()
    }

    override fun onStop() {
        runCatching { unregisterReceiver(serviceReceiver) }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun configureImmersiveLockWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun showEmergencyConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.emergency_confirm_title)
            .setMessage(R.string.emergency_confirm_message)
            .setPositiveButton(R.string.emergency_confirm_action) { _, _ ->
                val intent = Intent(this, LockdownForegroundService::class.java).apply {
                    action = LockdownForegroundService.ACTION_TRIGGER_EMERGENCY
                }
                ContextCompat.startForegroundService(this, intent)
            }
            .setNegativeButton(R.string.emergency_cancel_action, null)
            .show()
    }
}
