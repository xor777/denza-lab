package dev.denza.apps.feature.weather

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicLong

/** The alarm an app start registers: when it fires, and whether it is the prompt first run. */
internal data class WeatherAlarm(val triggerAtElapsed: Long, val soon: Boolean)

internal object WeatherAlarmPlan {
    /**
     * The alarm to register when the app starts - always one.
     *
     * A stored next time proves nothing about the alarm manager: a reboot discards every alarm
     * and keeps the record, and an elapsed time from the boot before can fall inside the window
     * of the boot after (a success at three minutes of uptime stores thirteen, and a reboot finds
     * it ahead of its thirty seconds). Until 2026-11-19 a stored time inside the window was
     * taken as a standing alarm and nothing was registered, so after a quick reboot the car got
     * no weather until something else woke the adapter. Registering the same pending intent again
     * replaces it, so re-arming at the stored time costs nothing when the alarm did stand, and
     * after a reboot it fires within two intervals at most.
     *
     * A last success in the future means the clock went back since; it is as stale as one long
     * past.
     */
    fun onStart(
        storedNextElapsed: Long,
        nowElapsed: Long,
        lastSuccessMillis: Long,
        nowMillis: Long,
    ): WeatherAlarm {
        val interval = WeatherAdapterConfig.REFRESH_INTERVAL_MILLIS
        val sinceSuccess = nowMillis - lastSuccessMillis
        if (sinceSuccess < 0L || sinceSuccess >= interval) {
            return WeatherAlarm(nowElapsed + WeatherAdapterConfig.INITIAL_REFRESH_DELAY_MILLIS, soon = true)
        }
        val stored = storedNextElapsed.takeIf { it > nowElapsed && it <= nowElapsed + interval * 2 }
        return WeatherAlarm(stored ?: (nowElapsed + interval), soon = false)
    }
}

object WeatherAdapterScheduler {
    private val lastForegroundTriggerElapsed = AtomicLong(0L)

    fun ensureScheduled(context: Context) {
        val app = context.applicationContext
        val licence = WeatherRefreshFence.process.issue()
        if (!WeatherAdapterState.enabled(app)) {
            cancel(app)
            return
        }
        val alarm = WeatherAlarmPlan.onStart(
            storedNextElapsed = WeatherAdapterState.nextAlarmElapsed(app),
            nowElapsed = SystemClock.elapsedRealtime(),
            lastSuccessMillis = WeatherAdapterState.lastSuccessMillis(app),
            nowMillis = System.currentTimeMillis(),
        )
        licence.runIfValid { arm(app, alarm) }
    }

    /**
     * Stop feeding the car: every refresh licence is revoked, so a run already under way writes,
     * notifies and re-arms nothing more, and the pending alarm goes.
     */
    fun cancel(context: Context) {
        val app = context.applicationContext
        WeatherRefreshFence.process.revoke()
        app.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(app))
        WeatherAdapterState.setNextAlarmElapsed(app, 0L)
    }

    @JvmStatic
    fun onNativeWeatherVisible(context: Context) {
        if (!WeatherAdapterState.enabled(context.applicationContext)) return
        val now = SystemClock.elapsedRealtime()
        val previous = lastForegroundTriggerElapsed.get()
        if (previous != 0L && now - previous < FOREGROUND_DEBOUNCE_MILLIS) return
        if (lastForegroundTriggerElapsed.compareAndSet(previous, now)) {
            refreshNow(context, WeatherAdapterService.REASON_NATIVE_APP_VISIBLE)
        }
    }

    /**
     * The next periodic run, ten minutes from now. With a [licence] - a refresh re-arming as it
     * ends - only if the switch has not gone off since that refresh began.
     */
    internal fun scheduleNext(context: Context, licence: WeatherRefreshFence.Licence? = null) {
        val app = context.applicationContext
        val next = WeatherAlarm(
            SystemClock.elapsedRealtime() + WeatherAdapterConfig.REFRESH_INTERVAL_MILLIS,
            soon = false,
        )
        if (licence == null) arm(app, next) else licence.runIfValid { arm(app, next) }
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

    /** The prompt first run uses a plain alarm; the periodic one may fire while idle. */
    private fun arm(app: Context, alarm: WeatherAlarm) {
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        if (alarm.soon) {
            manager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.triggerAtElapsed, alarmIntent(app))
        } else {
            manager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                alarm.triggerAtElapsed,
                alarmIntent(app),
            )
        }
        WeatherAdapterState.setNextAlarmElapsed(app, alarm.triggerAtElapsed)
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
