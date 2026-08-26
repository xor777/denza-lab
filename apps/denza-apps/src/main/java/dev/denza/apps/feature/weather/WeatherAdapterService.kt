package dev.denza.apps.feature.weather

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dev.denza.apps.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class WeatherAdapterService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var refreshJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        if (!running.compareAndSet(false, true)) return START_NOT_STICKY
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: REASON_PERIODIC
        // Switched off is switched off whoever woke us - a stale alarm, a boot receiver, the
        // native app coming to the front. Nothing is fetched and nothing is rescheduled.
        if (!WeatherAdapterState.enabled(applicationContext)) {
            Log.i(TAG, "refresh skipped: weather is switched off")
            WeatherAdapterScheduler.cancel(applicationContext)
            running.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        refreshJob = scope.launch {
            Log.i(TAG, "refresh started reason=$reason")
            try {
                WeatherAdapterController(applicationContext).refresh()
            } finally {
                WeatherAdapterScheduler.scheduleNext(applicationContext)
                running.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        refreshJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Weather updates",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "Updates the native vehicle weather widget"
                setShowBadge(false)
            },
        )
    }

    private fun notification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_denza_apps)
        .setContentTitle("Denza Apps")
        .setContentText("Обновление штатной погоды")
        .setOngoing(true)
        .setShowWhen(false)
        .build()

    companion object {
        const val ACTION_REFRESH = "dev.denza.apps.action.WEATHER_REFRESH"
        const val EXTRA_REASON = "reason"
        const val REASON_PERIODIC = "periodic"
        const val REASON_RECOVERY = "recovery"
        const val REASON_NATIVE_APP_VISIBLE = "native-app-visible"

        private const val TAG = "DenzaWeatherService"
        private const val CHANNEL_ID = "denza_weather_adapter"
        private const val NOTIFICATION_ID = 18_888
    }
}
