package dev.denza.apps.feature.hud

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import dev.denza.apps.R
import dev.denza.apps.RuntimeRecoveryService

/**
 * The foreground service of type `mediaProjection` (32) that the map's screen capture needs, alive
 * exactly as long as [HudMapGrant] holds its token.
 *
 * This car's `MediaProjection.start` refuses a uid without a running foreground service of type 32
 * (`MediaProjectionManagerService.java:411`), and `handleForegroundServicesChanged` stops the
 * projection as soon as the uid's foreground types no longer include it. So the token is taken here,
 * after [startForeground] ([HudMapGrant.onForeground]), and the service stops when the projection
 * does. Every [startForeground] here has two arguments: the types are the manifest's, 32 among them,
 * so no later call can narrow them while the token is held.
 *
 * No notification of its own: it posts under [RuntimeRecoveryService]'s id, which the firmware's
 * `ActiveServices.cancelForegroundNotificationLocked` keeps while either of the two is still in the
 * foreground. That service lives for seconds after a boot, so in practice the id is this one's, on a
 * minimum-importance channel - no icon in the status bar.
 */
class HudMapProjectionService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(RuntimeRecoveryService.NOTIFICATION_ID, notification())
        val held = if (intent?.action == ACTION_HOLD) {
            val data = intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            data != null && HudMapGrant.onForeground(this, intent.getIntExtra(EXTRA_RESULT, 0), data)
        } else {
            HudMapGrant.isLive()
        }
        if (!held) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Карта на проекции", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Копия экрана для карты на проекции"
                setShowBadge(false)
            },
        )
    }

    private fun notification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_denza_apps)
        .setContentTitle("Denza Apps")
        .setContentText("Карта на проекции")
        .setOngoing(true)
        .setShowWhen(false)
        .build()

    companion object {
        private const val ACTION_HOLD = "dev.denza.apps.action.HUD_MAP_HOLD"
        private const val EXTRA_RESULT = "result_code"
        private const val EXTRA_DATA = "result_data"
        private const val CHANNEL_ID = "denza_hud_map"

        /** Starts in the foreground and takes the token from SystemUI's [resultCode] and [data]. */
        fun hold(context: Context, resultCode: Int, data: Intent) {
            require(resultCode == Activity.RESULT_OK)
            ContextCompat.startForegroundService(
                context,
                Intent(context, HudMapProjectionService::class.java)
                    .setAction(ACTION_HOLD)
                    .putExtra(EXTRA_RESULT, resultCode)
                    .putExtra(EXTRA_DATA, data),
            )
        }

        /** The token is gone: the service and its foreground type go too. */
        fun release(context: Context) {
            context.stopService(Intent(context, HudMapProjectionService::class.java))
        }
    }
}
