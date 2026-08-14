package dev.denza.apps.feature.weather

import android.content.Context

internal object WeatherAdapterState {
    private const val PREFS_NAME = "weather_adapter_runtime"
    private const val KEY_LAST_ATTEMPT_MILLIS = "last_attempt_millis"
    private const val KEY_LAST_SUCCESS_MILLIS = "last_success_millis"
    private const val KEY_LAST_RESULT = "last_result"
    private const val KEY_NEXT_ALARM_ELAPSED = "next_alarm_elapsed"
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
