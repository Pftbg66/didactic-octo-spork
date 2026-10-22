package app.dumblaunch.helper

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.dumblaunch.data.Prefs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Current weather from Open-Meteo (free, no account or API key).
 * Only the city name you type is sent, no location permission is used.
 */
object WeatherHelper {

    private const val REFRESH_MS = 30 * 60 * 1000L

    private val waiting = mutableListOf<() -> Unit>()
    private var running = false

    /** Refreshes the cached weather if it is older than 30 minutes (or [force]). [onDone] runs on the main thread. */
    fun refresh(context: Context, force: Boolean = false, onDone: () -> Unit = {}) {
        val prefs = Prefs(context.applicationContext)

        if (prefs.weatherCity.isBlank()) {
            prefs.weatherText = ""
            onDone()
            return
        }
        val fresh = System.currentTimeMillis() - prefs.weatherUpdated < REFRESH_MS
        if (!force && fresh && prefs.weatherText.isNotBlank()) {
            onDone()
            return
        }

        synchronized(waiting) {
            waiting.add(onDone)
            if (running) return
            running = true
        }

        Thread {
            try {
                if (prefs.weatherLat.isBlank() || prefs.weatherLon.isBlank()) geocode(prefs)
                if (prefs.weatherLat.isNotBlank()) fetchCurrent(prefs)
            } catch (e: Exception) {
                e.printStackTrace() // keep showing the last known weather
            }
            val callbacks = synchronized(waiting) {
                running = false
                waiting.toList().also { waiting.clear() }
            }
            Handler(Looper.getMainLooper()).post { callbacks.forEach { it() } }
        }.start()
    }

    private fun get(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        return try {
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    // "Brampton" or "Brampton, Ontario" / "Paris, France"
    private fun geocode(prefs: Prefs) {
        val name = prefs.weatherCity.substringBefore(',').trim()
        val hint = prefs.weatherCity.substringAfter(',', "").trim().lowercase()
        val json = get(
            "https://geocoding-api.open-meteo.com/v1/search?name=" +
                    URLEncoder.encode(name, "UTF-8") + "&count=10&language=en&format=json"
        )
        val results = json.optJSONArray("results") ?: return
        var best: JSONObject? = null
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val place = (r.optString("admin1") + " " + r.optString("country")).lowercase()
            if (hint.isEmpty() || place.contains(hint)) {
                best = r
                break
            }
        }
        val pick = best ?: results.getJSONObject(0)
        prefs.weatherLat = pick.getDouble("latitude").toString()
        prefs.weatherLon = pick.getDouble("longitude").toString()
    }

    private fun fetchCurrent(prefs: Prefs) {
        val json = get(
            "https://api.open-meteo.com/v1/forecast?latitude=${prefs.weatherLat}" +
                    "&longitude=${prefs.weatherLon}&current=temperature_2m,weather_code" +
                    "&temperature_unit=celsius&timezone=auto"
        )
        val current = json.getJSONObject("current")
        val temp = Math.round(current.getDouble("temperature_2m"))
        prefs.weatherText = "$temp° · ${describe(current.getInt("weather_code"))}"
        prefs.weatherUpdated = System.currentTimeMillis()
    }

    private fun describe(code: Int): String = when (code) {
        0 -> "clear"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "fog"
        51, 53, 55, 56, 57 -> "drizzle"
        61, 63, 65, 66, 67 -> "rain"
        71, 73, 75, 77 -> "snow"
        80, 81, 82 -> "showers"
        85, 86 -> "snow showers"
        95, 96, 99 -> "thunderstorm"
        else -> ""
    }
}
