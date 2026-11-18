package com.strictfocus.lock.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.strictfocus.lock.R
import com.strictfocus.lock.data.LockMode
import com.strictfocus.lock.data.LockStateManager
import com.strictfocus.lock.databinding.ActivityMainBinding
import com.strictfocus.lock.service.LockdownForegroundService
import com.strictfocus.lock.util.PermissionItem
import com.strictfocus.lock.util.PermissionManager
import com.strictfocus.lock.util.RequiredPermission

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var stateManager: LockStateManager

    private var selectedMinutes = 45

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshPermissionsUi()
    }

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != LockdownForegroundService.ACTION_TICK) return
            val modeName = intent.getStringExtra(LockdownForegroundService.EXTRA_MODE)
            val remaining = intent.getLongExtra(LockdownForegroundService.EXTRA_REMAINING_MS, 0L)
            if (modeName == LockMode.EMERGENCY_SUSPENDED.name) {
                binding.tvEmergencyCountdown.text = LockStateManager.formatMs(remaining)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        stateManager = LockStateManager(this)
        setupTimerSelector()
        setupButtons()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            tickReceiver,
            IntentFilter(LockdownForegroundService.ACTION_TICK),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        syncLockStateUi()
        refreshPermissionsUi()
    }

    override fun onStop() {
        runCatching { unregisterReceiver(tickReceiver) }
        super.onStop()
    }

    private fun setupTimerSelector() {
        updateDurationDisplay()

        binding.btnDecrease.setOnClickListener {
            selectedMinutes = (selectedMinutes - 5).coerceAtLeast(5)
            binding.chipGroupDurations.clearCheck()
            updateDurationDisplay()
        }

        binding.btnIncrease.setOnClickListener {
            selectedMinutes = (selectedMinutes + 5).coerceAtMost(480)
            binding.chipGroupDurations.clearCheck()
            updateDurationDisplay()
        }

        val chipMap = mapOf(
            R.id.chip15 to 15,
            R.id.chip30 to 30,
            R.id.chip45 to 45,
            R.id.chip60 to 60,
            R.id.chip90 to 90
        )

        binding.chipGroupDurations.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            chipMap[checkedId]?.let { mins ->
                selectedMinutes = mins
                updateDurationDisplay()
            }
        }
    }

    private fun updateDurationDisplay() {
        val hours = selectedMinutes / 60
        val mins = selectedMinutes % 60
        binding.tvSelectedDuration.text = if (hours > 0) {
            String.format("%d:%02d:00", hours, mins)
        } else {
            String.format("%02d:00", mins)
        }
    }

    private fun setupButtons() {
        binding.btnStart.setOnClickListener {
            if (!PermissionManager.areAllCriticalPermissionsGranted(this)) {
                val firstMissing = PermissionManager.getPermissionChecklist(this)
                    .firstOrNull { !it.isGranted }
                if (firstMissing != null) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Permission Needed: ${firstMissing.title}")
                        .setMessage("${firstMissing.description}\n\nGrant all required permissions so the lockdown cannot be bypassed.")
                        .setPositiveButton(R.string.btn_grant) { _, _ ->
                            requestSpecificPermission(firstMissing.type)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                return@setOnClickListener
            }

            val durationMs = selectedMinutes * 60_000L
            val serviceIntent = Intent(this, LockdownForegroundService::class.java).apply {
                action = LockdownForegroundService.ACTION_START_LOCK
                putExtra(LockdownForegroundService.EXTRA_DURATION_MS, durationMs)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        }

        binding.btnEmergencyMain.setOnClickListener {
            when (stateManager.getMode()) {
                LockMode.IDLE -> {
                    Toast.makeText(
                        this,
                        "Emergency access is only used during an active focus lock.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                LockMode.EMERGENCY_SUSPENDED -> {
                    val remaining = LockStateManager.formatMs(stateManager.getRemainingEmergencyMs())
                    Toast.makeText(
                        this,
                        "Emergency window active. Re-locking in $remaining.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                LockMode.LOCKED -> {
                    startActivity(Intent(this, LockActivity::class.java))
                }
            }
        }
    }

    private fun syncLockStateUi() {
        when (stateManager.getMode()) {
            LockMode.LOCKED -> {
                if (stateManager.getRemainingLockMs() > 0L) {
                    startActivity(Intent(this, LockActivity::class.java))
                } else {
                    stateManager.clearLock()
                }
            }
            LockMode.EMERGENCY_SUSPENDED -> {
                binding.layoutEmergencyBanner.visibility = View.VISIBLE
                binding.tvEmergencyCountdown.text =
                    LockStateManager.formatMs(stateManager.getRemainingEmergencyMs())
                binding.btnStart.isEnabled = false
                binding.btnStart.alpha = 0.4f
                binding.layoutTimerSelector.alpha = 0.4f
            }
            LockMode.IDLE -> {
                binding.layoutEmergencyBanner.visibility = View.GONE
                binding.btnStart.isEnabled = true
                binding.btnStart.alpha = 1.0f
                binding.layoutTimerSelector.alpha = 1.0f
            }
        }
    }

    private fun refreshPermissionsUi() {
        val checklist = PermissionManager.getPermissionChecklist(this)
        val allGranted = checklist.all { it.isGranted }
        binding.layoutPermissions.visibility = if (allGranted) View.GONE else View.VISIBLE

        binding.containerPermissionRows.removeAllViews()
        checklist.forEach { item ->
            binding.containerPermissionRows.addView(createPermissionRow(item))
        }
    }

    private fun createPermissionRow(item: PermissionItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 12)
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 12
            }
        }

        val tvTitle = TextView(this).apply {
            text = item.title
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        }

        val tvDesc = TextView(this).apply {
            text = item.description
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        }

        textCol.addView(tvTitle)
        textCol.addView(tvDesc)

        val actionBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = if (item.isGranted) "Granted" else getString(R.string.btn_grant)
            isEnabled = !item.isGranted
            textSize = 11f
            setTextColor(
                if (item.isGranted) {
                    ContextCompat.getColor(this@MainActivity, R.color.status_granted)
                } else {
                    Color.WHITE
                }
            )
            setOnClickListener {
                requestSpecificPermission(item.type)
            }
        }

        row.addView(textCol)
        row.addView(actionBtn)
        return row
    }

    private fun requestSpecificPermission(permission: RequiredPermission) {
        if (permission == RequiredPermission.NOTIFICATIONS &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            if (!shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        val intent = PermissionManager.createSettingsIntent(this, permission)
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "Unable to open system settings for this permission.", Toast.LENGTH_SHORT).show()
        }
    }
}
