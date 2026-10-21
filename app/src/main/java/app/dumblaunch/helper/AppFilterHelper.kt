package app.dumblaunch.helper

import app.dumblaunch.data.AppModel

interface AppFilterHelper {
    fun onAppFiltered(items:List<AppModel>)
}