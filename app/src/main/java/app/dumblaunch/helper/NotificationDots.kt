package app.dumblaunch.helper

import androidx.lifecycle.MutableLiveData

/** Packages that currently have a (non-ongoing) notification. Filled by NotificationDotService. */
object NotificationDots {
    val packages = MutableLiveData<Set<String>>(emptySet())

    fun has(packageName: String): Boolean = packages.value?.contains(packageName) == true
}
