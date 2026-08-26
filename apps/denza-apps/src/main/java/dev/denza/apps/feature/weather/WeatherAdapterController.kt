package dev.denza.apps.feature.weather

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import dev.denza.apps.adb.DenzaLocalAdb

internal class WeatherAdapterController(context: Context) {
    private val appContext = context.applicationContext
    private val nativeStore = NativeWeatherStore(appContext)

    fun refresh(): Result {
        WeatherAdapterState.recordAttempt(appContext)
        return try {
            cleanupLegacyOwnedProxy()
            val coordinates = WeatherLocationSource(appContext).resolve()
            val locationLabel = AndroidWeatherGeocoder(appContext).resolve(
                coordinates.latitude,
                coordinates.longitude,
            )
            val forecast = MetNorwayClient(appContext).forecast(
                coordinates.latitude,
                coordinates.longitude,
            )
            val payload = NativeWeatherPayload.build(
                forecast,
                coordinates.latitude,
                coordinates.longitude,
                locationLabel = locationLabel,
            )

            NativeWeatherPayload.currentTemperature(forecast)?.let {
                WeatherAdapterState.setLastTemperature(appContext, it)
            }
            ensureNativeRefreshObserver()
            nativeStore.replace(payload)
            notifyNativeConsumers()
            finish(Result(true, "updated native weather (${coordinates.source})"))
        } catch (failure: Exception) {
            Log.i(TAG, "weather refresh failed", failure)
            finish(Result(false, failure.message ?: failure.javaClass.simpleName))
        }
    }

    /**
     * Starts the exported stock service so its time-format ContentObserver is registered. The
     * later URI notification does not change the user's clock preference, but makes the stock UID
     * issue its protected APPWIDGET_UPDATE broadcast.
     */
    private fun ensureNativeRefreshObserver() {
        val started = appContext.startService(
            Intent().setComponent(
                ComponentName(NATIVE_PACKAGE, NATIVE_SERVICE_CLASS),
            ),
        )
        checkNotNull(started) { "Native WeatherData service is unavailable" }
        SystemClock.sleep(OBSERVER_REGISTRATION_MILLIS)
    }

    private fun notifyNativeConsumers() {
        // The launcher listens for this public stock-weather action and re-queries the provider.
        appContext.sendBroadcast(Intent(NATIVE_REFRESH_ACTION))
        // RequestService observes this URI and emits the protected update as its own stock UID.
        // notifyChange does not alter the clock-format value.
        appContext.contentResolver.notifyChange(
            Settings.System.getUriFor(Settings.System.TIME_12_24),
            null,
        )
    }

    /** One-time upgrade cleanup for builds that contained the earlier selective-proxy spike. */
    private fun cleanupLegacyOwnedProxy() {
        val ownedProxy = WeatherAdapterState.ownedProxy(appContext) ?: return
        val adb = DenzaLocalAdb.client(appContext)
        val currentProxy = adb.shell(
            "settings get global http_proxy",
            SHELL_TIMEOUT_MILLIS,
        ).trim()
        if (currentProxy == ownedProxy) {
            adb.shell(
                "settings delete global http_proxy; " +
                    "settings delete global global_http_proxy_host; " +
                    "settings delete global global_http_proxy_port; " +
                    "settings delete global global_http_proxy_exclusion_list",
                SHELL_TIMEOUT_MILLIS,
            )
            val after = adb.shell(
                "settings get global http_proxy",
                SHELL_TIMEOUT_MILLIS,
            ).trim()
            check(after.isBlank() || after == "null" || after == ":0") {
                "Legacy weather proxy could not be cleared: $after"
            }
        }
        WeatherAdapterState.setOwnedProxy(appContext, null)
    }

    private fun finish(result: Result): Result {
        WeatherAdapterState.recordResult(appContext, result.success, result.message)
        Log.i(TAG, result.message)
        return result
    }

    data class Result(
        val success: Boolean,
        val message: String,
    )

    private companion object {
        const val TAG = "DenzaWeatherAdapter"
        const val NATIVE_PACKAGE = "com.byd.weatherdata"
        const val NATIVE_SERVICE_CLASS = "com.byd.weatherdata.service.RequestService"
        const val NATIVE_REFRESH_ACTION = "com.byd.weatherdata.action.THIRD_REFRESH"
        const val SHELL_TIMEOUT_MILLIS = 5_000
        const val OBSERVER_REGISTRATION_MILLIS = 350L
    }
}
