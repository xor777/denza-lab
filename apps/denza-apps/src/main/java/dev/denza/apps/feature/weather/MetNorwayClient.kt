package dev.denza.apps.feature.weather

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

internal object WeatherForecastCachePolicy {
    fun canServeAfterNetworkFailure(
        cachedForecastAvailable: Boolean,
        savedAtMillis: Long,
        nowMillis: Long,
    ): Boolean = cachedForecastAvailable &&
        savedAtMillis > 0L &&
        abs(nowMillis - savedAtMillis) <= WeatherAdapterConfig.MAX_STALE_FORECAST_MILLIS
}

internal class MetNorwayClient(context: Context) {
    private val cacheFile = AtomicFile(File(context.filesDir, "weather/met-forecast.json"))
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun forecast(latitude: Double, longitude: Double): JSONObject {
        require(latitude in -90.0..90.0) { "Invalid latitude" }
        require(longitude in -180.0..180.0) { "Invalid longitude" }

        val now = System.currentTimeMillis()
        val cached = readCache(latitude, longitude)
        if (cached != null && now < preferences.getLong(KEY_EXPIRES_AT, 0L)) {
            return cached
        }

        return try {
            request(latitude, longitude, cached, now)
        } catch (failure: Exception) {
            val savedAt = preferences.getLong(KEY_SAVED_AT, 0L)
            if (WeatherForecastCachePolicy.canServeAfterNetworkFailure(cached != null, savedAt, now)) {
                Log.i(TAG, "MET request failed; using bounded stale cache", failure)
                requireNotNull(cached)
            } else {
                throw failure
            }
        }
    }

    private fun request(
        latitude: Double,
        longitude: Double,
        cached: JSONObject?,
        now: Long,
    ): JSONObject {
        val endpoint = buildString {
            append(WeatherAdapterConfig.MET_ENDPOINT)
            append("?lat=")
            append(String.format(Locale.US, "%.4f", latitude))
            append("&lon=")
            append(String.format(Locale.US, "%.4f", longitude))
        }
        val connection = URL(endpoint).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.connectTimeout = 7_000
        connection.readTimeout = 9_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", WeatherAdapterConfig.MET_USER_AGENT)
        if (cached != null) {
            preferences.getString(KEY_LAST_MODIFIED, null)?.let { value ->
                connection.setRequestProperty("If-Modified-Since", value)
            }
        }

        return try {
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val parsed = JSONObject(body)
                    require(parsed.optJSONObject("properties")?.optJSONArray("timeseries")?.length() ?: 0 > 0) {
                        "MET response has no forecast timeseries"
                    }
                    writeCache(latitude, longitude, body)
                    preferences.edit()
                        .putLong(KEY_SAVED_AT, now)
                        .putLong(KEY_EXPIRES_AT, responseExpiry(connection, now))
                        .putString(KEY_LAST_MODIFIED, connection.getHeaderField("Last-Modified"))
                        .apply()
                    parsed
                }
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    requireNotNull(cached) { "MET returned 304 without a local cache" }
                    preferences.edit()
                        .putLong(KEY_EXPIRES_AT, responseExpiry(connection, now))
                        .apply()
                    cached
                }
                else -> error("MET request failed with HTTP $status")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readCache(latitude: Double, longitude: Double): JSONObject? {
        val cachedLatitude = preferences.getString(KEY_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val cachedLongitude = preferences.getString(KEY_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        if (kotlin.math.abs(cachedLatitude - latitude) > CACHE_COORDINATE_DELTA ||
            kotlin.math.abs(cachedLongitude - longitude) > CACHE_COORDINATE_DELTA
        ) {
            return null
        }
        return runCatching {
            cacheFile.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        }.getOrNull()
    }

    private fun writeCache(latitude: Double, longitude: Double, body: String) {
        cacheFile.baseFile.parentFile?.mkdirs()
        val stream = cacheFile.startWrite()
        try {
            stream.write(body.toByteArray(Charsets.UTF_8))
            stream.flush()
            cacheFile.finishWrite(stream)
            preferences.edit()
                .putString(KEY_LATITUDE, latitude.toString())
                .putString(KEY_LONGITUDE, longitude.toString())
                .apply()
        } catch (failure: Exception) {
            cacheFile.failWrite(stream)
            throw failure
        }
    }

    private fun responseExpiry(connection: HttpURLConnection, now: Long): Long {
        val expires = connection.getHeaderField("Expires")?.let { value ->
            runCatching {
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
        }
        return expires?.takeIf { it > now } ?: (now + DEFAULT_CACHE_MILLIS)
    }

    private companion object {
        const val TAG = "DenzaWeatherMet"
        const val PREFS_NAME = "weather_adapter_source"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_SAVED_AT = "saved_at"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_LAST_MODIFIED = "last_modified"
        const val CACHE_COORDINATE_DELTA = 0.02
        const val DEFAULT_CACHE_MILLIS = 30L * 60L * 1_000L
    }
}
