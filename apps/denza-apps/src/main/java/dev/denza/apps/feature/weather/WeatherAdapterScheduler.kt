package dev.denza.apps.feature.weather

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicLong

object WeatherAdapterScheduler {
    private val lastForegroundTriggerElapsed = AtomicLong(0L)

    fun ensureScheduled(context: Context) {
        val app = context.applicationContext
        val nowElapsed = SystemClock.elapsedRealtime()
        val next = WeatherAdapterState.nextAlarmElapsed(app)
        if (next <= nowElapsed || next > nowElapsed + WeatherAdapterConfig.REFRESH_INTERVAL_MILLIS * 2) {
            scheduleNext(app, WeatherAdapterConfig.REFRESH_INTERVAL_MILLIS)
        }
        val lastSuccess = WeatherAdapterState.lastSuccessMillis(app)
        if (System.currentTimeMillis() - lastSuccess >= WeatherAdapterConfig.REFRESH_INTERVAL_MILLIS) {
            scheduleSoon(app)
        }
    }

    @JvmStatic
    fun onNativeWeatherVisible(context: Context) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastForegroundTriggerElapsed.get()
        if (previous != 0L && now - previous < FOREGROUND_DEBOUNCE_MILLIS) return
        if (lastForegroundTriggerElapsed.compareAndSet(previous, now)) {
            refreshNow(context, WeatherAdapterService.REASON_NATIVE_APP_VISIBLE)
        }
    }

    internal fun scheduleNext(
        context: Context,
        delayMillis: Long = WeatherAdapterConfig.REFRESH_INTERVAL_MILLIS,
    ) {
        val app = context.applicationContext
        val triggerAt = SystemClock.elapsedRealtime() + delayMillis
        val alarm = app.getSystemService(AlarmManager::class.java) ?: return
        alarm.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAt,
            alarmIntent(app),
        )
        WeatherAdapterState.setNextAlarmElapsed(app, triggerAt)
    }

    internal fun refreshNow(context: Context, reason: String) {
        val intent = Intent(context, WeatherAdapterService::class.java)
            .setAction(WeatherAdapterService.ACTION_REFRESH)
            .putExtra(WeatherAdapterService.EXTRA_REASON, reason)
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { failure ->
                // The already-scheduled alarm remains as the recovery path if firmware background
                // start policy rejects an opportunistic boot or foreground-app refresh.
                Log.w(TAG, "weather foreground start rejected; reason=$reason", failure)
            }
    }

    private fun scheduleSoon(context: Context) {
        val app = context.applicationContext
        val alarm = app.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = SystemClock.elapsedRealtime() + WeatherAdapterConfig.INITIAL_REFRESH_DELAY_MILLIS
        alarm.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAt,
            alarmIntent(app),
        )
        WeatherAdapterState.setNextAlarmElapsed(app, triggerAt)
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST_CODE,
        Intent(context, WeatherAdapterReceiver::class.java).setAction(WeatherAdapterReceiver.ACTION_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private const val ALARM_REQUEST_CODE = 18_888
    private const val FOREGROUND_DEBOUNCE_MILLIS = 30_000L
    private const val TAG = "DenzaWeatherScheduler"
}
