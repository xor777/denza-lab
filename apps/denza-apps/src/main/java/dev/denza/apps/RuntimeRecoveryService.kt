package dev.denza.apps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import dev.denza.apps.core.DenzaRuntimeCoordinator
import dev.denza.apps.core.RuntimeRecoveryServicePolicy
import dev.denza.apps.core.RuntimeStartCause
import java.util.concurrent.atomic.AtomicBoolean

/** Short-lived foreground owner for boot/package recovery. It never holds an ACC lock. */
class RuntimeRecoveryService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val completed = AtomicBoolean(false)
    private val timeout = Runnable { finish("timeout") }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        // A second start can race with stopSelf after a completed cycle. Do not leave a fresh
        // foreground notification behind while Android is still dispatching the old service.
        if (completed.get()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val cause = intent?.getStringExtra(EXTRA_CAUSE)
            ?.let { encoded -> RuntimeStartCause.entries.firstOrNull { it.name == encoded } }
        if (cause == null || !cause.needsForegroundBootstrap) {
            Log.w(TAG, "invalid foreground recovery cause=${intent?.getStringExtra(EXTRA_CAUSE)}")
            finish("invalid-cause")
            return START_NOT_STICKY
        }

        if (!mainHandler.hasCallbacks(timeout)) {
            mainHandler.postDelayed(timeout, RuntimeRecoveryServicePolicy.MAX_DURATION_MILLIS)
        }
        DenzaRuntimeCoordinator.bootstrap(applicationContext, cause) {
            mainHandler.post { finish("recovered") }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mainHandler.removeCallbacks(timeout)
        super.onDestroy()
    }

    private fun finish(reason: String) {
        if (!completed.compareAndSet(false, true)) return
        Log.i(TAG, "foreground recovery finished reason=$reason")
        mainHandler.removeCallbacks(timeout)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Denza Apps recovery",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "Short-lived recovery of enabled vehicle features"
                setShowBadge(false)
            },
        )
    }

    private fun notification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_denza_apps)
        .setContentTitle("Denza Apps")
        .setContentText("Восстановление фоновых функций")
        .setOngoing(true)
        .setShowWhen(false)
        .build()

    companion object {
        private const val TAG = "DenzaRuntimeService"
        private const val ACTION_RECOVER = "dev.denza.apps.action.RUNTIME_RECOVERY"
        private const val EXTRA_CAUSE = "runtime_start_cause"
        private const val CHANNEL_ID = "denza_runtime_recovery"
        private const val NOTIFICATION_ID = 18_890

        @JvmStatic
        @JvmName("start")
        internal fun start(context: Context, systemAction: String) {
            val cause = when (systemAction) {
                Intent.ACTION_BOOT_COMPLETED -> RuntimeStartCause.BOOT_COMPLETED
                Intent.ACTION_MY_PACKAGE_REPLACED -> RuntimeStartCause.PACKAGE_REPLACED
                else -> return
            }
            val intent = Intent(context, RuntimeRecoveryService::class.java)
                .setAction(ACTION_RECOVER)
                .putExtra(EXTRA_CAUSE, cause.name)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { error ->
                    Log.w(TAG, "foreground recovery start rejected; cause=$cause", error)
                }
        }
    }
}
