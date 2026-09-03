package dev.denza.apps.feature.speaker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dev.denza.apps.DenzaAppRepository
import dev.denza.apps.MainActivity
import dev.denza.apps.R
import dev.denza.apps.SimulcastCoordinator
import dev.denza.apps.feature.hud.HudNotificationAccessCoordinator
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Watches for playback the car will not report, and reports it.
 *
 * There is no automaton left here and no motor to keep in step with. The service listens, hands
 * each trigger to [SpeakerCoverPolicy], and sends whatever that returns. It holds no state about
 * the covers because there is none to hold: their position is unreadable, the amplifier raises and
 * lowers them by its own rule, and a report that arrives twice costs nothing.
 */
class SpeakerCoverService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var mediaSessions: SpeakerMediaSessionObserver
    private var destroyed = false
    private var failures = 0

    /**
     * The last package reported for and when, so one starting track is one report.
     *
     * A media session answers `onPlaybackStateChanged` many times while a track plays, and the car
     * re-asserts its own `paused` about half a second after media focus moves. So the app speaks
     * once per player, waits out that window, and does not speak again for the same player until
     * [REPEAT_GUARD_MS] has passed - which is long enough that a track change inside one app is
     * silent and short enough that coming back to music after an idle retract is not.
     */
    private var lastReportedPackage: String? = null
    private var lastReportedAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
        active = this
        mediaSessions = SpeakerMediaSessionObserver(applicationContext) { packageName ->
            onPlayback(packageName)
        }
        mediaSessions.start()
        ensureObserverAccess()
        publishWatching()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RAISE -> {
                renotify()
                // The button answers with the feature off too, and then the process exists only
                // for the seconds the command takes.
                send(SpeakerCoverTrigger.RaisePressed, thenStop = !SpeakerCoverSettings.isEnabled(this))
                return START_NOT_STICKY
            }

            ACTION_ENABLED -> {
                renotify()
                send(SpeakerCoverTrigger.FeatureEnabled, thenStop = false)
                return START_STICKY
            }

            ACTION_DISABLED -> {
                renotify()
                send(SpeakerCoverTrigger.FeatureDisabled, thenStop = true)
                return START_NOT_STICKY
            }
        }
        if (!SpeakerCoverSettings.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        publishWatching()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        if (active === this) active = null
        if (::mediaSessions.isInitialized) mediaSessions.stop()
        executor.shutdownNow()
        SpeakerCoverRuntime.publish(SpeakerCoverRuntimeState())
        DenzaAppRepository.refresh()
        super.onDestroy()
    }

    private fun ensureObserverAccess() {
        if (!SimulcastCoordinator.isAccessibilityEnabled(this)) {
            SimulcastCoordinator.repairAccess(this) { failure ->
                if (failure != null) Log.i(TAG, "foreground-app observer unavailable", failure)
            }
        }
        HudNotificationAccessCoordinator.ensureMediaSessionAccess(this) {
            handler.post { if (!destroyed) mediaSessions.restart() }
        }
    }

    private fun onPlayback(packageName: String?) {
        if (destroyed || packageName == null) return
        if (!worthRepeating(packageName)) return
        send(SpeakerCoverTrigger.Playback(packageName), thenStop = false)
    }

    private fun onPlayerOpened(packageName: String?) {
        if (destroyed || packageName == null) return
        if (!worthRepeating(packageName)) return
        send(SpeakerCoverTrigger.PlayerOpened(packageName), thenStop = false)
    }

    private fun worthRepeating(packageName: String): Boolean {
        val now = SystemClock.uptimeMillis()
        if (packageName == lastReportedPackage && now - lastReportedAtMs < REPEAT_GUARD_MS) {
            return false
        }
        lastReportedPackage = packageName
        lastReportedAtMs = now
        return true
    }

    /**
     * Say it, and then say it again once.
     *
     * The car writes its own `paused` about 500 ms after media focus moves to a player it does not
     * know, and that write lands on the same property this one does. A single report sent inside
     * that window is simply overwritten, and from the seat the feature looks intermittent. So a
     * playback report is repeated once after the car has had its say. Nothing else repeats: a
     * button and a switch are not racing anything.
     */
    private fun send(trigger: SpeakerCoverTrigger, thenStop: Boolean) {
        publish(SpeakerCoverRuntimePhase.COMMANDING, describe(trigger))
        val enabled = SpeakerCoverSettings.isEnabled(this)
        executor.execute {
            val result = runCatching {
                SpeakerCoverTransport.run(applicationContext, trigger, enabled)
            }
            if (trigger is SpeakerCoverTrigger.Playback && result.isSuccess) {
                runCatching {
                    Thread.sleep(CAR_OVERWRITE_WINDOW_MS)
                    SpeakerCoverTransport.run(applicationContext, trigger, enabled)
                }
            }
            handler.post { finish(trigger, result, thenStop) }
        }
    }

    private fun finish(
        trigger: SpeakerCoverTrigger,
        result: Result<SpeakerCoverTransport.Outcome>,
        thenStop: Boolean,
    ) {
        if (destroyed) return
        result.onSuccess { outcome ->
            failures = 0
            Log.i(TAG, "trigger=$trigger steps=${outcome.steps} autoLift=${outcome.autoLift}")
            if (thenStop) {
                stopSelf()
                return
            }
            publishWatching()
        }.onFailure { failure ->
            failures += 1
            Log.w(TAG, "cover command failed trigger=$trigger", failure)
            publish(
                if (failures >= FAILURES_BEFORE_BROKEN) {
                    SpeakerCoverRuntimePhase.FAILED
                } else {
                    SpeakerCoverRuntimePhase.DEGRADED
                },
                if (failures >= FAILURES_BEFORE_BROKEN) "Крышки не отвечают" else "Повторю при следующем запуске музыки",
                failure.toString(),
            )
            if (thenStop) stopSelf()
        }
    }

    private fun describe(trigger: SpeakerCoverTrigger): String = when (trigger) {
        is SpeakerCoverTrigger.Playback -> "Музыка: ${trigger.packageName}"
        is SpeakerCoverTrigger.PlayerOpened -> "Открыт плеер: ${trigger.packageName}"
        SpeakerCoverTrigger.RaisePressed -> "Поднимаю"
        SpeakerCoverTrigger.FeatureEnabled -> "Включаю автоматику"
        SpeakerCoverTrigger.FeatureDisabled -> "Убираю до конца поездки"
    }

    private fun publishWatching() {
        publish(SpeakerCoverRuntimePhase.MONITORING, "Динамики выедут под музыку")
    }

    private fun publish(
        phase: SpeakerCoverRuntimePhase,
        message: String,
        details: String? = null,
    ) {
        Log.i(TAG, "phase=$phase message=$message details=${details ?: "—"}")
        SpeakerCoverRuntime.publish(
            SpeakerCoverRuntimeState(phase = phase, message = message, details = details),
        )
        DenzaAppRepository.refresh()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Крышки динамиков",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "Динамики выезжают, когда играет музыка"
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
            .setContentText(
                if (SpeakerCoverSettings.isEnabled(this)) {
                    "Динамики выезжают под музыку"
                } else {
                    "Выполняю команду крышек динамиков"
                },
            )
            .setContentIntent(openApp)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun renotify() {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification())
    }

    companion object {
        private const val TAG = "DenzaSpeakerCovers"
        private const val CHANNEL_ID = "denza_speaker_covers"
        private const val NOTIFICATION_ID = 18_889

        /** How long the car's own `paused` write takes to land after media focus moves. */
        private const val CAR_OVERWRITE_WINDOW_MS = 1_200L

        /** How long one player stays reported before the same player may be reported again. */
        private const val REPEAT_GUARD_MS = 60_000L

        private const val FAILURES_BEFORE_BROKEN = 3

        private const val ACTION_RAISE = "dev.denza.apps.action.RAISE_SPEAKER_COVERS"
        private const val ACTION_ENABLED = "dev.denza.apps.action.SPEAKER_COVERS_ENABLED"
        private const val ACTION_DISABLED = "dev.denza.apps.action.SPEAKER_COVERS_DISABLED"

        @Volatile
        private var active: SpeakerCoverService? = null

        fun reconcile(context: Context) {
            val app = context.applicationContext
            if (SpeakerCoverSettings.isEnabled(app)) {
                ContextCompat.startForegroundService(app, Intent(app, SpeakerCoverService::class.java))
            } else {
                app.stopService(Intent(app, SpeakerCoverService::class.java))
            }
        }

        fun raise(context: Context) = start(context, ACTION_RAISE)

        fun enabled(context: Context) = start(context, ACTION_ENABLED)

        fun disabled(context: Context) = start(context, ACTION_DISABLED)

        private fun start(context: Context, action: String) {
            val app = context.applicationContext
            ContextCompat.startForegroundService(
                app,
                Intent(app, SpeakerCoverService::class.java).setAction(action),
            )
        }

        @JvmStatic
        fun onForegroundPackage(packageName: String?) {
            active?.onPlayerOpened(packageName)
        }
    }
}
