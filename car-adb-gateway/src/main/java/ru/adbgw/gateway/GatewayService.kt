package ru.adbgw.gateway

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class GatewayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foreground = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        GatewayRepository.initialize(this)
        createNotificationChannel()
        registerNetworkCallback()
        scope.launch {
            GatewayRepository.state.collectLatest { state ->
                if (foreground) notificationManager().notify(NOTIFICATION_ID, notification(state))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_DISABLE -> scope.launch { GatewayRepository.disableRemoteAccess() }
            ACTION_STOP_SERVICE -> {
                GatewayRepository.stopSupervisor()
                stopForegroundCompat()
                stopSelf(startId)
            }
            else -> {
                moveToForeground()
                GatewayRepository.startSupervisor()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        networkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
        }
        GatewayRepository.stopSupervisor()
        scope.cancel()
        super.onDestroy()
    }

    private fun registerNetworkCallback() {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = GatewayRepository.networkChanged()
            override fun onLost(network: Network) = GatewayRepository.networkChanged()
        }
        connectivity.registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    private fun moveToForeground() {
        if (foreground) return
        val current = notification(GatewayRepository.state.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            startForeground(NOTIFICATION_ID, current, type)
        } else {
            startForeground(NOTIFICATION_ID, current)
        }
        foreground = true
    }

    private fun notification(state: GatewayUiState) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("Car ADB Gateway")
        .setContentText(state.headline)
        .setOngoing(state.enabled)
        .setContentIntent(openAppIntent())
        .addAction(0, "Отключить", disableIntent())
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager().createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Удалённый доступ", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Надёжное соединение автомобиля с relay"
            },
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        1,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun disableIntent(): PendingIntent = PendingIntent.getService(
        this,
        2,
        Intent(this, GatewayService::class.java).setAction(ACTION_DISABLE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

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

    private fun notificationManager(): NotificationManager = getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "car_adb_gateway"
        private const val NOTIFICATION_ID = 73
        private const val ACTION_START = "ru.adbgw.gateway.START"
        private const val ACTION_DISABLE = "ru.adbgw.gateway.DISABLE"
        private const val ACTION_STOP_SERVICE = "ru.adbgw.gateway.STOP_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, GatewayService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, GatewayService::class.java).setAction(ACTION_STOP_SERVICE),
            )
        }
    }
}
