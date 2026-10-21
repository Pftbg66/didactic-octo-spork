package app.dumblaunch.helper

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import app.dumblaunch.data.Prefs
import app.dumblaunch.listener.DumbLaunchAdminReceiver

/**
 * Kiosk mode allow-list: only dumblaunch itself plus the apps currently on the
 * home screen (and swipe / clock / calendar shortcuts) can run. Everything else
 * is blocked by Android while the launcher is locked.
 */
object KioskHelper {

    // Package of the companion "micro settings" app (its applicationId)
    private const val MICRO_SETTINGS_PACKAGE = "com.example.microsettings"

    // Android's own Settings app. Only allowed to run while micro settings is on the home screen,
    // and never shown in the launcher's app list, so the only way in is micro settings' six links.
    private val SETTINGS_PACKAGES = setOf("com.android.settings")

    /** Packages the launcher must not list or launch directly while kiosk mode is active. */
    fun blockedFromList(context: Context): Set<String> {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return if (dpm.isDeviceOwnerApp(context.packageName)) SETTINGS_PACKAGES else emptySet()
    }

    fun updateAllowedApps(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        val admin = ComponentName(context, DumbLaunchAdminReceiver::class.java)
        val prefs = Prefs(context)

        val allowed = linkedSetOf(context.packageName)
        for (i in 1..prefs.homeAppsNum) allowed.add(prefs.getAppPackage(i))
        allowed.add(prefs.appPackageSwipeLeft)
        allowed.add(prefs.appPackageSwipeRight)
        allowed.add(prefs.clockAppPackage)
        allowed.add(prefs.calendarAppPackage)
        allowed.removeAll { it.isBlank() }
        if (allowed.contains(MICRO_SETTINGS_PACKAGE)) allowed.addAll(SETTINGS_PACKAGES)

        try {
            dpm.setLockTaskPackages(admin, allowed.toTypedArray())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Keep the home gesture/button, notifications and status bar usable while pinned.
                dpm.setLockTaskFeatures(
                    admin,
                    DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                            DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
                            DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
