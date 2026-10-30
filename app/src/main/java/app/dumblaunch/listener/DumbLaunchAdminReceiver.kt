package app.dumblaunch.listener

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager

class DumbLaunchAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // No toast — the system (and previous toasts) were the "admin notification" that kept popping up.

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, DumbLaunchAdminReceiver::class.java)

        // Lockdown only applies when set up as device owner (kiosk mode).
        // A plain device admin (double-tap to lock) just needs the policy; no UI noise.
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        try {
            // 1. Lock down app management and system settings access
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_APPS_CONTROL)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_TETHERING)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)

            // 2. Prevent uninstallation of your custom launcher
            dpm.setUninstallBlocked(adminComponent, context.packageName, true)

            // 3. Optional: Permit your launcher to enter Kiosk (LockTask) mode
            dpm.setLockTaskPackages(adminComponent, arrayOf(context.packageName))
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        // No toast
    }
}
