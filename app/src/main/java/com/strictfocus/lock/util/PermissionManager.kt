package com.strictfocus.lock.util

import android.Manifest
import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.strictfocus.lock.receiver.FocusDeviceAdminReceiver
import com.strictfocus.lock.service.LockdownAccessibilityService

enum class RequiredPermission {
    OVERLAY,
    DEVICE_ADMIN,
    ACCESSIBILITY,
    EXACT_ALARM,
    BATTERY_OPTIMIZATION,
    NOTIFICATIONS
}

data class PermissionItem(
    val type: RequiredPermission,
    val title: String,
    val description: String,
    val isGranted: Boolean
)

object PermissionManager {

    fun getPermissionChecklist(context: Context): List<PermissionItem> {
        return listOf(
            PermissionItem(
                type = RequiredPermission.OVERLAY,
                title = "Display Over Other Apps",
                description = "Renders the unbypassable full-screen touch-blocking lock layer.",
                isGranted = hasOverlayPermission(context)
            ),
            PermissionItem(
                type = RequiredPermission.ACCESSIBILITY,
                title = "Accessibility Service",
                description = "Blocks Home, Recent Apps, Notification Shade, and Settings tampering.",
                isGranted = isAccessibilityEnabled(context)
            ),
            PermissionItem(
                type = RequiredPermission.DEVICE_ADMIN,
                title = "Device Administrator",
                description = "Prevents app uninstallation while a focus session is active.",
                isGranted = isDeviceAdminActive(context)
            ),
            PermissionItem(
                type = RequiredPermission.EXACT_ALARM,
                title = "Schedule Exact Alarms",
                description = "Guarantees exact auto-unlock and 5-minute emergency re-lock.",
                isGranted = canScheduleExactAlarms(context)
            ),
            PermissionItem(
                type = RequiredPermission.BATTERY_OPTIMIZATION,
                title = "Battery Optimization Exemption",
                description = "Prevents Android from killing the foreground lock service in background.",
                isGranted = isIgnoringBatteryOptimizations(context)
            ),
            PermissionItem(
                type = RequiredPermission.NOTIFICATIONS,
                title = "Post Notifications",
                description = "Displays the persistent non-cancelable countdown notification.",
                isGranted = hasNotificationPermission(context)
            )
        )
    }

    fun areAllCriticalPermissionsGranted(context: Context): Boolean {
        return hasOverlayPermission(context) &&
            isAccessibilityEnabled(context) &&
            isDeviceAdminActive(context) &&
            canScheduleExactAlarms(context) &&
            isIgnoringBatteryOptimizations(context) &&
            hasNotificationPermission(context)
    }

    fun hasOverlayPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, FocusDeviceAdminReceiver::class.java)
        return dpm.isAdminActive(adminComponent)
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val expectedComponent = ComponentName(context, LockdownAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val componentString = splitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentString)
            if (enabledComponent == expectedComponent) {
                return true
            }
        }
        return false
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun createSettingsIntent(context: Context, permission: RequiredPermission): Intent {
        return when (permission) {
            RequiredPermission.OVERLAY -> Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            RequiredPermission.DEVICE_ADMIN -> Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(
                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(context, FocusDeviceAdminReceiver::class.java)
                )
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Required to prevent uninstalling StrictFocus during an active lockdown."
                )
            }
            RequiredPermission.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            RequiredPermission.EXACT_ALARM -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:${context.packageName}")
                    )
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                }
            }
            RequiredPermission.BATTERY_OPTIMIZATION -> Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
            RequiredPermission.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        }
    }
}
