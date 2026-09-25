package dev.denza.apps.feature.cloud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.denza.apps.MainActivity
import dev.denza.apps.R

/**
 * Keeps the cloud link's adapter alive while the switch is on, and tells it when internet comes and
 * goes - Wi-Fi or validated mobile data ([CloudNetwork]); stock APNs are guarded separately.
 *
 * It decides nothing: [CloudLinkController] reads the car and [CloudLinkCore] says what to send.
 * What only a running component can do is here - hold the process, watch the default network, and
 * listen for the stock client's own status broadcast.
 *
 * It does not outlive parking and is not meant to. ACC-off terminates ordinary apps and clears
 * their alarms; the stock client keeps its own session through QuickBoot, and on the way back
 * `BOOT_COMPLETED` (with `from_quickboot`) brings this service up through the runtime recovery,
 * which reconciles. While parked the link is the stock client's, and what keeps it up is Wi-Fi
 * staying on - the panel's second switch.
 */
class CloudLinkService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var validated = false
    private var watching = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed()
        override fun onLost(network: Network) = changed()
    }

    /**
     * Internet that stays gone for the grace period, and only that, closes the gate: a network that
     * blinks - an access point handing over, a tunnel on mobile data, Wi-Fi giving way to mobile -
     * must not cost a disconnect and a new login.
     */
    private val lossCheck = Runnable {
        if (!CloudNetwork.usable(this)) CloudLinkController.networkGone(this)
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            CloudLinkController.hint(context)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
        validated = CloudNetwork.usable(this)
        // The default network, because that is what [CloudNetwork.kind] asks about. A callback on
        // one transport fires when that network validates, which can be a moment before it becomes
        // the default - the question then answers «no», the transition is lost, and the link waits
        // for the next five-minute reading.
        getSystemService(ConnectivityManager::class.java)
            ?.registerDefaultNetworkCallback(networkCallback)
        // Sent by system_server with no permission and no package, so it has to be exported to
        // arrive at all. Whether it does arrive at an ordinary app is unproven; nothing waits on it.
        registerReceiver(statusReceiver, IntentFilter(TCP_STATUS_ACTION), Context.RECEIVER_EXPORTED)
        watching = true
        CloudLinkController.serviceStarted(this)
        if (!validated) handler.postDelayed(lossCheck, CloudLinkCore.NETWORK_LOSS_GRACE_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!CloudLinkSettings.needsService(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (watching) {
            runCatching {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback)
            }
            runCatching { unregisterReceiver(statusReceiver) }
            watching = false
        }
        handler.removeCallbacks(lossCheck)
        CloudLinkController.serviceStopped()
        super.onDestroy()
    }

    /** Callbacks arrive on the connectivity thread and say little; the one question is asked here. */
    private fun changed() {
        handler.post {
            val now = CloudNetwork.usable(this)
            if (now == validated) return@post
            validated = now
            if (now) {
                handler.removeCallbacks(lossCheck)
                CloudLinkController.networkReturned(this)
            } else {
                handler.postDelayed(lossCheck, CloudLinkCore.NETWORK_LOSS_GRACE_MS)
            }
        }
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Облако", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Связь машины с облаком через интернет"
                setShowBadge(false)
            },
        )
    }

    private fun notification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_denza_apps)
            .setContentTitle("Denza Apps")
            .setContentText(if (CloudLinkSettings.pendingDisable(this)) "Завершается отключение облака" else "Связь с облаком поддерживается")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "denza_cloud_link"
        private const val NOTIFICATION_ID = 18_891

        /** `BYDTCPConnectService.notify_tcp_status`, extra `tcp_status`. */
        private const val TCP_STATUS_ACTION = "com.byd.tcp.cloud.server.status"

        /** The switch, read from settings: on runs the adapter, off stops it. */
        fun reconcile(context: Context) {
            val app = context.applicationContext
            if (CloudLinkSettings.needsService(app)) {
                ContextCompat.startForegroundService(app, Intent(app, CloudLinkService::class.java))
            } else {
                app.stopService(Intent(app, CloudLinkService::class.java))
            }
        }
    }
}
