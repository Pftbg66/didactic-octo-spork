package app.dumblaunch.helper

import android.app.ActivityManager
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process

/** Stops the background processes of every launchable app (except the launcher itself). */
fun clearRunningApps(context: Context) {
    try {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val activePackages = launcherApps.getActivityList(null, Process.myUserHandle())
            .map { it.applicationInfo.packageName }
            .distinct()
            .filter { it != context.packageName }

        val runningPackages = activityManager.runningAppProcesses
            ?.flatMap { it.pkgList?.toList().orEmpty() }
            ?.distinct()
            ?.filter { it != context.packageName }
            .orEmpty()

        (activePackages + runningPackages)
            .distinct()
            .filter { it != context.packageName }
            .filterNot { it.startsWith("android") || it.startsWith("com.android") }
            .forEach { activityManager.killBackgroundProcesses(it) }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
