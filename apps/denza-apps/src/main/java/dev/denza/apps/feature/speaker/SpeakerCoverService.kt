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
import dev.denza.apps.feature.trip.SpectrumSignal
import dev.denza.apps.feature.trip.SpectrumSource
import dev.denza.apps.feature.trip.TripSession
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Long-lived, fail-closed owner of the speaker-cover automation. */
class SpeakerCoverService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val motorExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val automaton = SpeakerCoverAutomaton()
    private val magnitudes = DoubleArray(SpectrumSource.BAND_COUNT)
    private val spectrumOwner = Any()

    private lateinit var spectrum: SpectrumSource
    private lateinit var mediaSessions: SpeakerMediaSessionObserver
    private var lastProcessedCaptureMs = -1L
    private var destroyed = false
    private var audioAvailable = false
    private var windingDown = false
    private var motorFailures = 0

    private val sampleLoop = object : Runnable {
        override fun run() {
            if (destroyed) return
            val now = SystemClock.uptimeMillis()
            if (windingDown) {
                automaton.onTick(now)?.let(::execute)
                handler.postDelayed(this, SAMPLE_INTERVAL_MS)
                return
            }
            val capturedAt = spectrum.snapshotAt(magnitudes)
            val request = if (capturedAt != null && capturedAt != lastProcessedCaptureMs) {
                lastProcessedCaptureMs = capturedAt
                setAudioAvailable(true)
                automaton.onAudioSample(capturedAt, SpectrumSignal.hasSignal(magnitudes))
            } else {
                if (capturedAt == null) {
                    automaton.onCaptureUnavailable()
                    setAudioAvailable(false)
                }
                automaton.onTick(now)
            }
            request?.let(::execute)
            handler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
        active = this
        publish(
            SpeakerCoverRuntimePhase.STARTING,
            "Подключаю датчик звука",
        )

        spectrum = TripSession.hub(applicationContext).spectrum
        spectrum.start(applicationContext, spectrumOwner)
        mediaSessions = SpeakerMediaSessionObserver(applicationContext) { packageName ->
            requestOpen("MediaSession: $packageName")
        }
        mediaSessions.start()
        ensureObserverAccess()
        handler.post(sampleLoop)
        // Startup is over, so say so. `setAudioAvailable` only speaks when the sensor's
        // availability *changes*, and on a car where it is unavailable from the first tick it
        // never does - so nothing ever moved the phase off the STARTING published above, and the
        // dashboard tile turned a spinner for the entire life of the process. The watchers are
        // running by this line whether or not the sensor answered.
        publishMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISABLE_AND_OPEN) {
            byHand(open = true, thenStop = true)
            return START_NOT_STICKY
        }
        // Raise and lower are the panel's own two buttons, and they answer whether or not the
        // automation is switched on - the covers are the car's, not the feature's. With it off, the
        // process exists only long enough to send the command.
        if (intent?.action == ACTION_RAISE || intent?.action == ACTION_LOWER) {
            byHand(
                open = intent.action == ACTION_RAISE,
                thenStop = !SpeakerCoverSettings.isEnabled(this),
            )
            return START_NOT_STICKY
        }
        if (!SpeakerCoverSettings.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Switched back on while the process was still winding down. `windingDown` is set by the
        // disable path and was never cleared by anything, so a service that survived being turned
        // off stayed in its stopping mode for the rest of its life: the sample loop took the
        // wind-down branch, nothing published monitoring again, and the tile turned the spinner
        // left behind by the last motor command. Off then on is exactly what a driver does.
        if (windingDown) {
            windingDown = false
            publishMonitoring()
        }
        return START_STICKY
    }

    /** A position asked for from the panel, which the automation then believes. */
    private fun byHand(open: Boolean, thenStop: Boolean) {
        windingDown = thenStop
        val request = automaton.onManualPosition(open, SystemClock.uptimeMillis())
        if (request != null) {
            execute(request)
        } else if (thenStop && automaton.pendingAction == null) {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        if (active === this) active = null
        handler.removeCallbacks(sampleLoop)
        if (::mediaSessions.isInitialized) mediaSessions.stop()
        if (::spectrum.isInitialized) spectrum.stop(spectrumOwner)
        motorExecutor.shutdownNow()
        SpeakerCoverRuntime.publish(SpeakerCoverRuntimeState())
        DenzaAppRepository.refresh()
        super.onDestroy()
    }

    private fun ensureObserverAccess() {
        if (!SimulcastCoordinator.isAccessibilityEnabled(this)) {
            SimulcastCoordinator.repairAccess(this) { failure ->
                if (failure != null) {
                    Log.i(TAG, "foreground-app observer unavailable", failure)
                }
            }
        }
        HudNotificationAccessCoordinator.ensureMediaSessionAccess(this) {
            handler.post {
                if (!destroyed) mediaSessions.restart()
            }
        }
    }

    private fun requestOpen(reason: String) {
        if (destroyed) return
        automaton.onImmediateOpen(SystemClock.uptimeMillis(), reason)?.let(::execute)
    }

    private fun execute(request: SpeakerCoverMotorRequest) {
        publish(
            SpeakerCoverRuntimePhase.COMMANDING,
            when (request.action) {
                SpeakerCoverMotorAction.OPEN -> "Открываю крышки"
                SpeakerCoverMotorAction.CLOSE -> "Закрываю крышки"
            },
            details = request.reason,
        )
        motorExecutor.execute {
            val result = runCatching {
                SpeakerCoverMotor.execute(applicationContext, request.action)
            }
            handler.post {
                if (destroyed) return@post
                val next = automaton.onMotorResult(
                    action = request.action,
                    success = result.isSuccess,
                    nowMs = SystemClock.uptimeMillis(),
                )
                if (result.isSuccess) {
                    motorFailures = 0
                    if (windingDown && next == null && automaton.pendingAction == null) {
                        stopSelf()
                        return@post
                    }
                    publishMonitoring()
                } else {
                    motorFailures += 1
                    Log.w(TAG, "motor command failed action=${request.action}", result.exceptionOrNull())
                    val giveUp = motorFailures >= MOTOR_FAILURES_BEFORE_BROKEN
                    publish(
                        if (giveUp) {
                            SpeakerCoverRuntimePhase.FAILED
                        } else {
                            SpeakerCoverRuntimePhase.DEGRADED
                        },
                        if (giveUp) "Крышки не отвечают" else "Повторю команду автоматически",
                        result.exceptionOrNull()?.toString(),
                    )
                }
                next?.let(::execute)
            }
        }
    }

    /**
     * The audio sensor coming and going.
     *
     * Losing it is not a fault to recover from: the media sessions the head unit publishes are the
     * other ear, they keep working, and on a car where the sensor is simply unavailable there is
     * nothing to recover to. Reporting DEGRADED here drew a spinner on the dashboard tile that
     * turned for as long as the app ran - a feature announcing repair work that was never going to
     * finish, on a feature that was working.
     *
     * The failure is still worth having, so it goes on the panel as the reading's detail rather
     * than as the feature's state.
     */
    private fun setAudioAvailable(available: Boolean) {
        if (audioAvailable == available) return
        audioAvailable = available
        if (automaton.pendingAction == null) publishMonitoring()
    }

    /**
     * Watching, and by what.
     *
     * The automation has two ways to know something is playing: the audio sensor, and the media
     * sessions the head unit publishes. Losing the first does not stop the second, so the feature
     * is monitoring either way and says which ear it is using.
     *
     * It used to report STARTING without the audio sensor, which drew a spinner on the dashboard
     * tile - and on a car where that sensor is simply not available, nothing ever moved it on. The
     * tile turned that spinner indefinitely, promising a startup that had already finished.
     */
    private fun publishMonitoring() {
        publish(
            SpeakerCoverRuntimePhase.MONITORING,
            if (audioAvailable) "Слежу за воспроизведением" else "Слежу за плеерами",
            if (audioAvailable) null else spectrum.lastFailure,
        )
    }

    private fun publish(
        phase: SpeakerCoverRuntimePhase,
        message: String,
        details: String? = null,
    ) {
        // Every transition, because the tile can only draw "working" or not: from the screen there
        // is no telling a motor command in flight from a watcher stuck in startup, and both look
        // like a spinner that never ends.
        Log.i(TAG, "phase=$phase message=$message details=${details ?: "—"}")
        SpeakerCoverRuntime.publish(
            SpeakerCoverRuntimeState(
                phase = phase,
                raised = automaton.raised,
                message = message,
                details = details,
            ),
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
                description = "Автоматическое управление крышками по воспроизведению"
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
            .setContentText("Автоматика крышек динамиков включена")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val TAG = "DenzaSpeakerCovers"
        private const val CHANNEL_ID = "denza_speaker_covers"
        private const val NOTIFICATION_ID = 18_889
        private const val SAMPLE_INTERVAL_MS = 200L

        /**
         * How many commands may fail before the tile stops promising a recovery.
         *
         * The automaton keeps retrying on its own cooldown either way. This only decides when the
         * screen stops drawing a spinner over it, because a spinner that turns all day is a worse
         * answer than "не отвечает".
         */
        private const val MOTOR_FAILURES_BEFORE_BROKEN = 3
        private const val ACTION_DISABLE_AND_OPEN =
            "dev.denza.apps.action.DISABLE_SPEAKER_COVERS_AND_OPEN"
        private const val ACTION_RAISE = "dev.denza.apps.action.RAISE_SPEAKER_COVERS"
        private const val ACTION_LOWER = "dev.denza.apps.action.LOWER_SPEAKER_COVERS"

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

        fun raise(context: Context) = byHand(context, ACTION_RAISE)

        fun lower(context: Context) = byHand(context, ACTION_LOWER)

        private fun byHand(context: Context, action: String) {
            val app = context.applicationContext
            ContextCompat.startForegroundService(
                app,
                Intent(app, SpeakerCoverService::class.java).setAction(action),
            )
        }

        fun disableAndOpen(context: Context) {
            val app = context.applicationContext
            ContextCompat.startForegroundService(
                app,
                Intent(app, SpeakerCoverService::class.java).setAction(ACTION_DISABLE_AND_OPEN),
            )
        }

        @JvmStatic
        fun onForegroundPackage(packageName: String?) {
            if (!SpeakerCoverApps.opensEagerly(packageName)) return
            active?.requestOpen("приложение: $packageName")
        }
    }
}
