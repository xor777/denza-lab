package dev.denza.apps.feature.weather

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class WeatherAdapterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            ACTION_ALARM -> {
                WeatherAdapterScheduler.scheduleNext(context)
                WeatherAdapterScheduler.refreshNow(context, WeatherAdapterService.REASON_PERIODIC)
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                WeatherAdapterScheduler.scheduleNext(context)
                WeatherAdapterScheduler.refreshNow(context, WeatherAdapterService.REASON_RECOVERY)
            }
            else -> Log.i(TAG, "ignored action=$action")
        }
    }

    companion object {
        const val ACTION_ALARM = "dev.denza.apps.action.WEATHER_REFRESH_ALARM"
        private const val TAG = "DenzaWeatherReceiver"
    }
}
