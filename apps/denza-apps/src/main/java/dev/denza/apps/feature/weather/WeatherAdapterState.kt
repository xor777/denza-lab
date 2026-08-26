package dev.denza.apps.feature.weather

import android.content.Context

internal object WeatherAdapterState {
    private const val PREFS_NAME = "weather_adapter_runtime"
    private const val KEY_LAST_ATTEMPT_MILLIS = "last_attempt_millis"
    private const val KEY_LAST_SUCCESS_MILLIS = "last_success_millis"
    private const val KEY_LAST_RESULT = "last_result"
    private const val KEY_NEXT_ALARM_ELAPSED = "next_alarm_elapsed"
    private const val KEY_ENABLED = "enabled"
    // Kept only so an upgrade from the proxy spike can clean up an interrupted run.
    private const val KEY_OWNED_PROXY = "owned_proxy"

    fun recordAttempt(context: Context) {
        preferences(context).edit()
            .putLong(KEY_LAST_ATTEMPT_MILLIS, System.currentTimeMillis())
            .apply()
    }

    fun recordResult(context: Context, success: Boolean, result: String) {
        preferences(context).edit().apply {
            putString(KEY_LAST_RESULT, result.take(240))
            if (success) putLong(KEY_LAST_SUCCESS_MILLIS, System.currentTimeMillis())
        }.apply()
    }

    /**
     * Whether the car is being fed weather at all.
     *
     * Defaults to on: this adapter shipped before it had a switch, and a car that has been getting
     * a forecast for months must not lose it because an update introduced a preference it has
     * never been asked about.
     */
    fun enabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        preferences(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun lastSuccessMillis(context: Context): Long =
        preferences(context).getLong(KEY_LAST_SUCCESS_MILLIS, 0L)

    fun nextAlarmElapsed(context: Context): Long =
        preferences(context).getLong(KEY_NEXT_ALARM_ELAPSED, 0L)

    fun setNextAlarmElapsed(context: Context, value: Long) {
        preferences(context).edit().putLong(KEY_NEXT_ALARM_ELAPSED, value).apply()
    }

    fun ownedProxy(context: Context): String? =
        preferences(context).getString(KEY_OWNED_PROXY, null)

    fun setOwnedProxy(context: Context, value: String?) {
        preferences(context).edit().apply {
            if (value == null) remove(KEY_OWNED_PROXY) else putString(KEY_OWNED_PROXY, value)
        }.commit()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
