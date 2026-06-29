package dev.denza.gateway

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class GatewayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        GatewayRepository.initialize(this)
        ensureNotificationChannel()
        scope.launch {
            GatewayRepository.state.collectLatest { state ->
                if (foreground) {
                    notificationManager().notify(NOTIFICATION_ID, buildNotification(state))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                moveToForeground()
                scope.launch {
                    GatewayRepository.startGateway(this@GatewayService)
                    if (!GatewayRepository.state.value.isRunning) {
                        stopForegroundCompat()
                        stopSelf(startId)
                    }
                }
            }
            ACTION_STOP -> {
                scope.launch {
                    GatewayRepository.stopGateway()
                    stopForegroundCompat()
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runBlocking(Dispatchers.IO) { GatewayRepository.onServiceDestroyed() }
        scope.cancel()
        super.onDestroy()
    }

    private fun moveToForeground() {
        if (foreground) return
        val notification = buildNotification(GatewayRepository.state.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foreground = true
    }

    private fun stopForegroundCompat() {
        if (!foreground) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        foreground = false
    }

    private fun buildNotification(state: GatewayUiState) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Denza ADB Gateway")
            .setContentText(notificationText(state))
            .setOngoing(state.isRunning)
            .setContentIntent(openAppIntent())
            .addAction(0, "Stop", stopIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun notificationText(state: GatewayUiState): String {
        val ip = state.wifiBinding?.hostAddress ?: "no Wi-Fi"
        val endpoint = state.activeEndpoint?.let { "${it.host}:${it.port}" } ?: "ADB not selected"
        return "${state.status.title} - $ip -> $endpoint"
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            2,
            Intent(this, GatewayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ADB Gateway",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Foreground service for the local ADB SSH gateway"
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "denza_adb_gateway"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_START = "dev.denza.gateway.START"
        private const val ACTION_STOP = "dev.denza.gateway.STOP"

        fun start(context: Context) {
            val intent = Intent(context, GatewayService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, GatewayService::class.java).setAction(ACTION_STOP))
        }
    }
}
