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
 * It holds no state about the covers because there is none to hold: their position is unreadable,
 * the amplifier raises and lowers them by its own rule, and a report that arrives twice costs
 * nothing. It listens, hands each trigger to [SpeakerCoverPolicy], and sends the one report the
 * policy allows. Switching the feature on starts it and switching it off stops it; neither flip
 * writes anything to the car.
 */
class SpeakerCoverService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var mediaSessions: SpeakerMediaSessionObserver
    private var watching = false
    private var destroyed = false

    /**
     * The last package reported for and when, so one starting track is one report.
     *
     * A media session answers `onPlaybackStateChanged` many times while a track plays, so the app
     * speaks once per player and not again for the same player until [REPEAT_GUARD_MS] has passed -
     * long enough that a track change inside one app is silent, short enough that coming back to
     * music after an idle retract is not.
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
        // A one-shot serving «Поднять» with the feature off has nothing to watch, and must not go
        // repairing observer access on the driver's behalf.
        if (SpeakerCoverSettings.isEnabled(this)) watch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RAISE) {
            renotify()
            send(SpeakerCoverTrigger.RaisePressed, stopWhenOff = true)
            return START_NOT_STICKY
        }
        if (!SpeakerCoverSettings.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        watch()
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

    /**
     * Start listening, once.
     *
     * Starting the observer reads every active session, so a player already going when the switch
     * is flipped on is reported right here - and silence is left as silence. That is the whole of
     * what switching on does to the covers.
     */
    private fun watch() {
        publishWatching()
        if (watching) return
        watching = true
        renotify()
        mediaSessions.start()
        ensureObserverAccess()
    }

    private fun ensureObserverAccess() {
        if (!SimulcastCoordinator.isAccessibilityEnabled(this)) {
            SimulcastCoordinator.repairAccess(this) { failure ->
                if (failure != null) Log.i(TAG, "foreground-app observer unavailable", failure)
            }
        }
        HudNotificationAccessCoordinator.ensureMediaSessionAccess(this) {
            handler.post { if (!destroyed && watching) mediaSessions.restart() }
        }
    }

    private fun onPlayback(packageName: String?) {
        if (destroyed || packageName == null) return
        if (!worthRepeating(packageName)) return
        send(SpeakerCoverTrigger.Playback(packageName), stopWhenOff = false)
    }

    private fun onPlayerOpened(packageName: String?) {
        if (destroyed || packageName == null) return
        if (!worthRepeating(packageName)) return
        send(SpeakerCoverTrigger.PlayerOpened(packageName), stopWhenOff = false)
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
     * Say it, and for playback say it again once.
     *
     * The car writes its own «paused» about 500 ms after media focus moves to a player it does not
     * know, on the same property this report lands on. The report is a trigger the amplifier acts
     * on rather than a level it holds (reporting «paused» does not retract, live 2026-09-03), so the
     * repeat is insurance against the one race the seat cannot see, not a state being kept true.
     * A button is not racing anything and is sent once.
     *
     * A report that fails is logged and that is all. What the panel shows is the second of grey
     * while the command is on the wire; whether the car can be reached at all is already the
     * «Сервис» tile's news.
     */
    private fun send(trigger: SpeakerCoverTrigger, stopWhenOff: Boolean) {
        publish(SpeakerCoverRuntimePhase.COMMANDING, describe(trigger))
        val enabled = SpeakerCoverSettings.isEnabled(this)
        executor.execute {
            val result = runCatching { SpeakerCoverTransport.run(applicationContext, trigger, enabled) }
            if (trigger is SpeakerCoverTrigger.Playback && result.getOrDefault(false)) {
                runCatching {
                    Thread.sleep(CAR_OVERWRITE_WINDOW_MS)
                    SpeakerCoverTransport.run(applicationContext, trigger, enabled)
                }
            }
            handler.post { finish(trigger, result, stopWhenOff) }
        }
    }

    private fun finish(trigger: SpeakerCoverTrigger, result: Result<Boolean>, stopWhenOff: Boolean) {
        if (destroyed) return
        result
            .onSuccess { reported -> Log.i(TAG, "trigger=$trigger reported=$reported") }
            .onFailure { failure -> Log.w(TAG, "report failed trigger=$trigger", failure) }
        // Re-read rather than remembered: the switch may have gone on while the button's report was
        // on the wire, and a service that stopped then would leave the feature on with nobody
        // listening.
        if (stopWhenOff && !SpeakerCoverSettings.isEnabled(this)) {
            stopSelf()
            return
        }
        publishWatching()
    }

    private fun describe(trigger: SpeakerCoverTrigger): String = when (trigger) {
        is SpeakerCoverTrigger.Playback -> "Музыка: ${trigger.packageName}"
        is SpeakerCoverTrigger.PlayerOpened -> "Открыт плеер: ${trigger.packageName}"
        SpeakerCoverTrigger.RaisePressed -> "Поднимаю"
    }

    private fun publishWatching() {
        publish(SpeakerCoverRuntimePhase.MONITORING, SpeakerCoverRuntime.WATCHING)
    }

    private fun publish(phase: SpeakerCoverRuntimePhase, message: String) {
        Log.i(TAG, "phase=$phase message=$message")
        SpeakerCoverRuntime.publish(SpeakerCoverRuntimeState(phase = phase, message = message))
        DenzaAppRepository.refresh()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Динамики",
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
                    "Автоуправление динамиками включено"
                } else {
                    "Поднимаю динамики"
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

        /** How long the car's own «paused» write takes to land after media focus moves. */
        private const val CAR_OVERWRITE_WINDOW_MS = 1_200L

        /** How long one player stays reported before the same player may be reported again. */
        private const val REPEAT_GUARD_MS = 60_000L

        private const val ACTION_RAISE = "dev.denza.apps.action.RAISE_SPEAKER_COVERS"

        @Volatile
        private var active: SpeakerCoverService? = null

        /** The switch, read from settings: on runs the watcher, off stops it. Nothing is written to the car. */
        fun reconcile(context: Context) {
            val app = context.applicationContext
            if (SpeakerCoverSettings.isEnabled(app)) {
                ContextCompat.startForegroundService(app, Intent(app, SpeakerCoverService::class.java))
            } else {
                app.stopService(Intent(app, SpeakerCoverService::class.java))
            }
        }

        fun raise(context: Context) {
            val app = context.applicationContext
            ContextCompat.startForegroundService(
                app,
                Intent(app, SpeakerCoverService::class.java).setAction(ACTION_RAISE),
            )
        }

        @JvmStatic
        fun onForegroundPackage(packageName: String?) {
            active?.onPlayerOpened(packageName)
        }
    }
}
