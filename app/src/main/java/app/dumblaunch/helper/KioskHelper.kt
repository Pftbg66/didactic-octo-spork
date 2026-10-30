package app.dumblaunch.helper

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.UserManager
import android.provider.ContactsContract
import android.provider.Telephony
import android.telecom.TelecomManager
import app.dumblaunch.data.Prefs
import app.dumblaunch.listener.DumbLaunchAdminReceiver

/**
 * Kiosk mode allow-list: only dumblaunch itself plus the apps currently on the
 * home screen (and swipe / clock / calendar shortcuts) can run. Everything else
 * is blocked by Android while the launcher is locked.
 */
object KioskHelper {

    // Android's own Settings app. Allowed to run, but never shown in the launcher's app list,
    // so the only way in is the launcher's own "device" settings rows.
    private val SETTINGS_PACKAGES = setOf("com.android.settings")

    // Phone, texting and emergency plumbing. Added only if present; extra names are harmless.
    private val PHONE_SYSTEM_PACKAGES = setOf(
        "com.android.phone", "com.android.server.telecom", "com.android.incallui",
        "com.android.providers.telephony", "com.android.providers.contacts",
        "com.android.mms.service", "com.android.stk", "com.android.emergency",
        "com.android.cellbroadcastreceiver", "com.google.android.cellbroadcastreceiver",
        // runtime permission pop-ups (e.g. the first time messages asks for contacts)
        "com.android.permissioncontroller", "com.google.android.permissioncontroller"
    )

    // Android's own Files app and the storage providers behind it.
    private val FILES_PACKAGES = setOf(
        "com.android.documentsui", "com.google.android.documentsui", "com.android.externalstorage",
        "com.android.providers.media", "com.android.providers.media.module",
        "com.google.android.providers.media.module",
        "com.android.providers.downloads", "com.android.providers.downloads.ui",
        // Samsung "My Files", Google Files, and other common file managers
        "com.sec.android.app.myfiles", "com.samsung.android.app.myfiles",
        "com.google.android.apps.nbu.files", "com.google.android.storagemanager",
        "com.android.storagemanager"
    )

    /** Whoever answers the system "browse files" / "open file" intents on this phone. */
    private fun fileHandlerPackages(context: Context): Set<String> {
        val found = linkedSetOf<String>()
        val pm = context.packageManager
        val root = android.provider.DocumentsContract.buildRootUri("com.android.externalstorage.documents", "primary")
        listOf(
            Intent("android.provider.action.BROWSE").setData(root),
            Intent(Intent.ACTION_VIEW).setDataAndType(root, "vnd.android.document/root"),
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
            Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        ).forEach { intent ->
            try {
                pm.queryIntentActivities(intent, 0).forEach { found.add(it.activityInfo.packageName) }
            } catch (e: Exception) { e.printStackTrace() }
        }
        found.remove("android")
        return found
    }

    /** Lets one more package run while kiosk mode is on (e.g. the app that opens a tapped file). */
    fun allowPackage(context: Context, packageName: String) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName) || packageName.isBlank()) return
        try {
            val admin = ComponentName(context, DumbLaunchAdminReceiver::class.java)
            val current = dpm.getLockTaskPackages(admin).toMutableSet()
            if (current.add(packageName)) dpm.setLockTaskPackages(admin, current.toTypedArray())
        } catch (e: Exception) { e.printStackTrace() }
    }

    /** Default dialer, default texting app, contacts app and the phone/text system pieces. */
    private fun callAndTextPackages(context: Context): Set<String> {
        val found = linkedSetOf<String>()
        val pm = context.packageManager
        try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.defaultDialerPackage?.let { found.add(it) }
        } catch (e: Exception) { e.printStackTrace() }
        try {
            Telephony.Sms.getDefaultSmsPackage(context)?.let { found.add(it) }
        } catch (e: Exception) { e.printStackTrace() }
        listOf(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:")),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
        ).forEach { intent ->
            try {
                val pkg = pm.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo?.packageName
                if (pkg != null && pkg != "android") found.add(pkg)
            } catch (e: Exception) { e.printStackTrace() }
        }
        found.addAll(PHONE_SYSTEM_PACKAGES)
        return found
    }

    /** Packages the launcher must not list or launch directly while kiosk mode is active. */
    fun blockedFromList(context: Context): Set<String> {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return if (dpm.isDeviceOwnerApp(context.packageName)) SETTINGS_PACKAGES else emptySet()
    }

    fun updateAllowedApps(context: Context, notificationPackages: Set<String>? = null) {
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
        // The launcher's own "device" settings open Android's Settings pages, so it must be allowed to run.
        allowed.addAll(SETTINGS_PACKAGES)
        // Calling and texting always work in kiosk mode, even if they are not on the home screen.
        allowed.addAll(callAndTextPackages(context))
        allowed.addAll(FILES_PACKAGES)
        allowed.addAll(fileHandlerPackages(context))
        // Tapping a notification opens its app, so apps with a waiting notification may run too.
        if (prefs.kioskNotifications) allowed.addAll(notificationPackages ?: NotificationDots.packages.value ?: emptySet())

        // Permission pop-ups are blocked in kiosk mode, so grant location (for weather) silently.
        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION).forEach {
            try {
                dpm.setPermissionGrantState(
                    admin, context.packageName, it, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Lock the launcher in: dumblaunch is the permanent home app, can't be swapped in Android's
        // "default home app" picker, can't be uninstalled, and can't be force-stopped or cleared.
        try {
            val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(
                admin, homeFilter, ComponentName(context, app.dumblaunch.MainActivity::class.java)
            )
            dpm.setUninstallBlocked(admin, context.packageName, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dpm.setUserControlDisabledPackages(admin, listOf(context.packageName))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Block "erase all data" / factory reset from Settings. Re-applied on every start, so it
        // holds even if the one-time setup missed it.
        try {
            dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            dpm.setLockTaskPackages(admin, allowed.toTypedArray())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Only the home gesture/button stays. NOTIFICATIONS and SYSTEM_INFO are left out
                // on purpose, so the status bar (clock, battery, icons), the notification shade
                // and quick settings are hidden everywhere, not just in the launcher.
                var features = DevicePolicyManager.LOCK_TASK_FEATURE_HOME
                // Optional (launcher settings > notifications): text alerts and call banners.
                // Off keeps the clean look, but then new texts are silent and invisible.
                if (prefs.kioskNotifications) features = features or DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
                dpm.setLockTaskFeatures(admin, features)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
