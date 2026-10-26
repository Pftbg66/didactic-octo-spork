package app.dumblaunch.listener

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.dumblaunch.helper.NotificationDots

/**
 * Tells the launcher which apps have waiting notifications, so it can draw a small dot next to them.
 * Needs "notification access" for dumblaunch (launcher settings > notification dots).
 */
class NotificationDotService : NotificationListenerService() {

    override fun onListenerConnected() {
        refresh()
    }

    override fun onListenerDisconnected() {
        NotificationDots.packages.postValue(emptySet())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refresh()
    }

    private fun refresh() {
        val active = try {
            activeNotifications
                ?.filter { !it.isOngoing && it.packageName != packageName }
                ?.map { it.packageName }
                ?.toSet() ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
        NotificationDots.packages.postValue(active)
    }
}
