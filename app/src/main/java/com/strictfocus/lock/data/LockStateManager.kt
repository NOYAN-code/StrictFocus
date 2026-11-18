package com.strictfocus.lock.data

import android.content.Context
import android.content.SharedPreferences

enum class LockMode {
    IDLE,
    LOCKED,
    EMERGENCY_SUSPENDED
}

class LockStateManager(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun getMode(): LockMode {
        val raw = prefs.getString(KEY_MODE, LockMode.IDLE.name) ?: LockMode.IDLE.name
        return runCatching { LockMode.valueOf(raw) }.getOrDefault(LockMode.IDLE)
    }

    fun startLockSession(durationMs: Long): Long {
        val now = System.currentTimeMillis()
        val endEpochMs = now + durationMs
        prefs.edit()
            .putString(KEY_MODE, LockMode.LOCKED.name)
            .putLong(KEY_SESSION_END_MS, endEpochMs)
            .putLong(KEY_PAUSED_REMAINING_MS, 0L)
            .putLong(KEY_EMERGENCY_END_MS, 0L)
            .commit()
        return endEpochMs
    }

    fun triggerEmergencyWindow(): Long {
        val now = System.currentTimeMillis()
        val remainingLockMs = getRemainingLockMs().coerceAtLeast(1_000L)
        val emergencyEndEpochMs = now + EMERGENCY_DURATION_MS
        prefs.edit()
            .putString(KEY_MODE, LockMode.EMERGENCY_SUSPENDED.name)
            .putLong(KEY_PAUSED_REMAINING_MS, remainingLockMs)
            .putLong(KEY_EMERGENCY_END_MS, emergencyEndEpochMs)
            .commit()
        return emergencyEndEpochMs
    }

    fun resumeLockFromEmergency(): Long {
        val now = System.currentTimeMillis()
        val pausedRemaining = prefs.getLong(KEY_PAUSED_REMAINING_MS, 0L).coerceAtLeast(1_000L)
        val newEndEpochMs = now + pausedRemaining
        prefs.edit()
            .putString(KEY_MODE, LockMode.LOCKED.name)
            .putLong(KEY_SESSION_END_MS, newEndEpochMs)
            .putLong(KEY_PAUSED_REMAINING_MS, 0L)
            .putLong(KEY_EMERGENCY_END_MS, 0L)
            .commit()
        return newEndEpochMs
    }

    fun clearLock() {
        prefs.edit()
            .putString(KEY_MODE, LockMode.IDLE.name)
            .putLong(KEY_SESSION_END_MS, 0L)
            .putLong(KEY_PAUSED_REMAINING_MS, 0L)
            .putLong(KEY_EMERGENCY_END_MS, 0L)
            .commit()
    }

    fun getSessionEndEpochMs(): Long = prefs.getLong(KEY_SESSION_END_MS, 0L)

    fun getEmergencyEndEpochMs(): Long = prefs.getLong(KEY_EMERGENCY_END_MS, 0L)

    fun getPausedRemainingMs(): Long = prefs.getLong(KEY_PAUSED_REMAINING_MS, 0L)

    fun getRemainingLockMs(): Long {
        return when (getMode()) {
            LockMode.LOCKED -> (getSessionEndEpochMs() - System.currentTimeMillis()).coerceAtLeast(0L)
            LockMode.EMERGENCY_SUSPENDED -> getPausedRemainingMs().coerceAtLeast(0L)
            LockMode.IDLE -> 0L
        }
    }

    fun getRemainingEmergencyMs(): Long {
        if (getMode() != LockMode.EMERGENCY_SUSPENDED) return 0L
        return (getEmergencyEndEpochMs() - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    companion object {
        private const val PREFS_NAME = "strict_focus_lock_state"
        private const val KEY_MODE = "lock_mode"
        private const val KEY_SESSION_END_MS = "session_end_ms"
        private const val KEY_PAUSED_REMAINING_MS = "paused_remaining_ms"
        private const val KEY_EMERGENCY_END_MS = "emergency_end_ms"

        const val EMERGENCY_DURATION_MS = 5 * 60 * 1000L // Strictly 5 minutes

        fun formatHms(ms: Long): String {
            val totalSeconds = (ms.coerceAtLeast(0L) + 999L) / 1000L
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return String.format("%02d:%02d:%02d", hours, minutes, seconds)
        }

        fun formatMs(ms: Long): String {
            val totalSeconds = (ms.coerceAtLeast(0L) + 999L) / 1000L
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format("%02d:%02d", minutes, seconds)
        }
    }
}
