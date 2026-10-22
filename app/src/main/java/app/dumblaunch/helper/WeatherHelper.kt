package app.dumblaunch.helper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import app.dumblaunch.data.Prefs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Current weather from Open-Meteo.
 * Uses device location when available, and falls back to a manual city entry if needed.
 */
object WeatherHelper {

    private const val REFRESH_MS = 30 * 60 * 1000L

    private val waiting = mutableListOf<() -> Unit>()
    private var running = false

    fun refresh(context: Context, force: Boolean = false, onDone: () -> Unit = {}) {
        val prefs = Prefs(context.applicationContext)

        val hasLocationPermission = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasLocationPermission && prefs.weatherCity.isBlank() && prefs.weatherLat.isBlank() && prefs.weatherLon.isBlank()) {
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
                val coords = findLocation(context, prefs)
                if (coords != null) {
                    prefs.weatherLat = coords.first.toString()
                    prefs.weatherLon = coords.second.toString()
                    fetchCurrent(prefs)
                } else if (prefs.weatherCity.isNotBlank()) {
                    geocode(prefs)
                    if (prefs.weatherLat.isNotBlank()) fetchCurrent(prefs)
                    else prefs.weatherText = ""
                } else {
                    prefs.weatherText = ""
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            val callbacks = synchronized(waiting) {
                running = false
                waiting.toList().also { waiting.clear() }
            }
            Handler(Looper.getMainLooper()).post { callbacks.forEach { it() } }
        }.start()
    }

    private fun findLocation(context: Context, prefs: Prefs): Pair<Double, Double>? {
        val hasLocationPermission = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (hasLocationPermission) {
            val locationManager = context.getSystemService(LocationManager::class.java) as? LocationManager
            if (locationManager != null) {
                val knownLocations = listOfNotNull(
                    locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER),
                    locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                )
                val best = knownLocations.maxByOrNull { it.time }
                if (best != null) return best.latitude to best.longitude
            }
        }

        if (prefs.weatherLat.isNotBlank() && prefs.weatherLon.isNotBlank()) {
            return prefs.weatherLat.toDouble() to prefs.weatherLon.toDouble()
        }

        return null
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
