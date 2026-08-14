package dev.denza.apps.feature.weather

import android.content.ContentValues
import android.content.Context
import android.net.Uri

/** Writes the already-decoded JSON contract consumed by the stock weather app and launcher. */
internal class NativeWeatherStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    fun readPayload(): String? = resolver.query(
        WEATHER_URI,
        arrayOf(COLUMN_NAME),
        null,
        null,
        "_id DESC",
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    /**
     * The stock provider has no transaction API. Snapshotting and restoring its single row keeps
     * the last good forecast available if an insert or read-back verification fails.
     */
    fun replace(payload: String) {
        require(payload.isNotBlank()) { "Native weather payload is empty" }
        val previous = readPayload()
        try {
            write(payload)
            check(readPayload() == payload) { "Native weather provider did not retain the payload" }
        } catch (failure: Exception) {
            runCatching {
                resolver.delete(WEATHER_URI, null, null)
                if (previous != null) insert(previous)
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun write(payload: String) {
        resolver.delete(WEATHER_URI, null, null)
        insert(payload)
    }

    private fun insert(payload: String) {
        val values = ContentValues(3).apply {
            put(COLUMN_ID, 0)
            put(COLUMN_NAME, payload)
            putNull(COLUMN_ALTITUDE)
        }
        checkNotNull(resolver.insert(WEATHER_URI, values)) {
            "Native weather provider rejected the payload"
        }
    }

    companion object {
        val WEATHER_URI: Uri =
            Uri.parse("content://com.byd.weatherdata.utils.WeatherContentProvider/weather")

        private const val COLUMN_ID = "_id"
        private const val COLUMN_NAME = "name"
        private const val COLUMN_ALTITUDE = "altitude"
    }
}
