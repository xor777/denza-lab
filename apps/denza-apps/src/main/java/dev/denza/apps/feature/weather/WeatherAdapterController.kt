package dev.denza.apps.feature.weather

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import dev.denza.apps.adb.DenzaLocalAdb
import dev.denza.apps.platform.shell.ShellGrants

internal class WeatherAdapterController(context: Context) {
    private val appContext = context.applicationContext
    private val nativeStore = NativeWeatherStore(appContext)

    /**
     * One refresh, start to end, for as long as [licence] holds; how it went is recorded in
     * [WeatherAdapterState], not returned. The order and the fencing are [WeatherRefresh]'s.
     */
    fun refresh(licence: WeatherRefreshFence.Licence) {
        val outcome = WeatherRefresh(licence, StateRecord()).run(
            prepare = ::prepare,
            deliver = { payload ->
                ensureNativeRefreshObserver()
                nativeStore.replace(payload)
            },
            announce = ::notifyNativeConsumers,
        )
        if (outcome == WeatherRefresh.Outcome.WITHDRAWN) {
            Log.i(TAG, "refresh withdrawn: weather was switched off while it ran")
        }
    }

    private fun prepare(): PreparedWeather {
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
        val weather = NativeWeatherPayload.build(
            forecast,
            coordinates.latitude,
            coordinates.longitude,
            locationLabel = locationLabel,
        )
        return PreparedWeather(weather, coordinates.source)
    }

    private inner class StateRecord : WeatherRecord {
        override fun attempt() = WeatherAdapterState.recordAttempt(appContext)

        override fun delivered(temperature: Int, message: String) {
            WeatherAdapterState.recordDelivery(appContext, temperature, message)
            Log.i(TAG, message)
        }

        override fun failed(failure: Exception) {
            Log.i(TAG, "weather refresh failed", failure)
            WeatherAdapterState.recordFailure(
                appContext,
                failure.message ?: failure.javaClass.simpleName,
            )
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
            adb.shell(LEGACY_PROXY_CLEAR_COMMAND, SHELL_TIMEOUT_MILLIS)
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

    internal companion object {
        /** The four global proxy keys the spike wrote, removed in one shell trip. */
        val LEGACY_PROXY_CLEAR_COMMAND: String = listOf(
            "http_proxy",
            "global_http_proxy_host",
            "global_http_proxy_port",
            "global_http_proxy_exclusion_list",
        ).joinToString("; ") { key -> ShellGrants.settingsDelete("global", key) }

        private const val TAG = "DenzaWeatherAdapter"
        private const val NATIVE_PACKAGE = "com.byd.weatherdata"
        private const val NATIVE_SERVICE_CLASS = "com.byd.weatherdata.service.RequestService"
        private const val NATIVE_REFRESH_ACTION = "com.byd.weatherdata.action.THIRD_REFRESH"
        private const val SHELL_TIMEOUT_MILLIS = 5_000
        private const val OBSERVER_REGISTRATION_MILLIS = 350L
    }
}
