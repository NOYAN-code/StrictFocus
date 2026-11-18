package com.strictfocus.lock.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.strictfocus.lock.data.LockMode
import com.strictfocus.lock.data.LockStateManager
import com.strictfocus.lock.ui.LockActivity

class LockdownAccessibilityService : AccessibilityService() {

    private lateinit var stateManager: LockStateManager
    private var lastRedirectTimeMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        stateManager = LockStateManager(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!::stateManager.isInitialized) {
            stateManager = LockStateManager(this)
        }

        val mode = stateManager.getMode()
        if (mode == LockMode.IDLE) return

        val pkg = event.packageName?.toString() ?: return

        when (mode) {
            LockMode.LOCKED -> {
                // Allow our own app and system input methods only
                if (pkg == packageName) return

                // Dismiss notification shade or quick settings if pulled down
                if (pkg == "com.android.systemui") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                    }
                    enforceLockActivity()
                    return
                }

                // Block any other app, launcher, recents, or Settings
                enforceLockActivity()
            }
            LockMode.EMERGENCY_SUSPENDED -> {
                // During the 5-minute emergency window, allow normal phone use
                // but block tampering with StrictFocus in Settings or Package Installer
                if (pkg == "com.android.settings" ||
                    pkg == "com.google.android.packageinstaller" ||
                    pkg == "com.android.packageinstaller"
                ) {
                    val root = rootInActiveWindow
                    if (root != null && containsAppReference(root)) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                }
            }
            LockMode.IDLE -> Unit
        }
    }

    private fun enforceLockActivity() {
        val now = System.currentTimeMillis()
        if (now - lastRedirectTimeMs < 150L) return
        lastRedirectTimeMs = now

        val intent = Intent(this, LockActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        startActivity(intent)
    }

    private fun containsAppReference(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
        if (text.contains("strictfocus") || desc.contains("strictfocus")) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = containsAppReference(child)
            if (found) return true
        }
        return false
    }

    override fun onInterrupt() {
        // No-op
    }
}
