# StrictFocus — Production Android Lockdown Application

An ultra-minimalist, distraction-free Android focus-lock application built in Kotlin (`compileSdk 35`, `minSdk 26`, `targetSdk 35`).

## Architecture Overview
- **Single-Screen Setup (`MainActivity`)**: Minimalist dark UI with a countdown duration selector (`5m–480m`, quick presets + `−5/+5` steppers), primary **Start** button, subtle **Emergency** button, and an inline system-permission onboarding checklist.
- **Multi-Layer Lockdown Enforcement**:
  1. **`LockdownForegroundService`** (`foregroundServiceType="specialUse"`): Maintains a non-dismissible countdown notification, renders a full-screen `TYPE_APPLICATION_OVERLAY` touch-blocking layer, and runs a 500ms state ticker.
  2. **`LockActivity`**: `singleInstance` immersive activity (`excludeFromRecents="true"`) that hides system bars and consumes `Back`, `Home`, and `App Switch` key events.
  3. **`LockdownAccessibilityService`**: Intercepts window state changes to dismiss the notification shade (`GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE` on API 31+), block Home/Recents/other apps while `LOCKED`, and block Settings/Package Installer tampering on `StrictFocus` during both `LOCKED` and `EMERGENCY_SUSPENDED` modes.
  4. **`FocusDeviceAdminReceiver`**: Prevents uninstallation while active.
  5. **`AlarmScheduler` + `AlarmReceiver` + `BootReceiver`**: Uses `AlarmManager.setExactAndAllowWhileIdle` (`RTC_WAKEUP`) to guarantee exact session completion, 5-minute emergency re-lock, task-removal watchdog recovery, and `BOOT_COMPLETED` persistence.
- **5-Minute Emergency Override**: Tapping **Emergency** suspends the lock for strictly 5 minutes (`300,000 ms`), preserves the remaining original focus duration, grants full phone access, and forcefully re-locks the device when the 5-minute alarm/timer expires.

## Required System Permissions
The onboarding flow checks and guides the user to the exact system settings screens for:
1. **Display Over Other Apps** (`SYSTEM_ALERT_WINDOW`)
2. **Accessibility Service** (`BIND_ACCESSIBILITY_SERVICE`)
3. **Device Administrator** (`BIND_DEVICE_ADMIN`)
4. **Schedule Exact Alarms** (`SCHEDULE_EXACT_ALARM`)
5. **Battery Optimization Exemption** (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
6. **Post Notifications** (`POST_NOTIFICATIONS` on Android 13+)

## Step-by-Step: Compile & Export APK

### Option A: Android Studio (Recommended)
1. Unzip `StrictFocusLock-AndroidProject.zip` to a local folder.
2. Open **Android Studio** (Ladybug 2024.2.1 or newer) → **Open** → select the `StrictFocusLock` root folder.
3. Wait for Gradle Sync to download dependencies and configure the SDK (`compileSdk 35`).
4. **Debug APK** (for immediate testing on your phone):
   - Menu: **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
   - Locate the output at `app/build/outputs/apk/debug/app-debug.apk`.
5. **Signed Release APK** (for permanent personal use):
   - Menu: **Build → Generate Signed Bundle / APK…** → select **APK** → **Next**.
   - Click **Create new…** to generate a `.jks` keystore, fill in alias/passwords, select **release** build variant, and click **Create**.
   - Locate the output at `app/build/outputs/apk/release/app-release.apk`.

### Option B: Command Line (Gradle)
From the `StrictFocusLock` root directory (with Android SDK & JDK 17 configured):
```bash
# Build Debug APK
./gradlew assembleDebug

# Install directly to connected USB/ADB phone
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
*(Note: If `gradlew` wrapper JAR is not yet initialized, run `gradle wrapper` once or open in Android Studio which generates it automatically.)*
