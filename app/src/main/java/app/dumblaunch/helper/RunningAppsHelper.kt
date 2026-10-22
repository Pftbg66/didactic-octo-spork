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
        launcherApps.getActivityList(null, Process.myUserHandle())
            .map { it.applicationInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .forEach { activityManager.killBackgroundProcesses(it) }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
