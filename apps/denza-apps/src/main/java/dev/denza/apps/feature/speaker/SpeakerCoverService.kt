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
    private val magnitudes = DoubleArray(SpectrumSource.BAND_COUNT)
    private val spectrumOwner = Any()

    private lateinit var automaton: SpeakerCoverAutomaton
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
        seedAutomaton()
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
            partingOpen()
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
            // And off-then-on is also the driver taking the wheel back, which the preferences have
            // already been told - but this object read them once, in onCreate, and would go on
            // believing what they said then: one-shot spent, wheel with the driver, a freshly
            // enabled feature announcing that it intends to do nothing. So it is rebuilt from what
            // they say now, which after the enable path is a clean boot's worth of intent.
            seedAutomaton()
            publishMonitoring()
        }
        return START_STICKY
    }

    /**
     * The automaton as this trip has left it, which is not the same as a fresh one.
     *
     * The two persisted flags are the whole memory of the feature, and they exist because the
     * process is restartable and the trip is not: without them a restart would hand the automation
     * a second opening and forget that the driver had taken the covers over. What counts as the
     * same trip is [SpeakerCoverFactScope]'s to decide, and it is not the kernel boot - this head
     * unit suspends at ignition off, so a boot outlives any number of trips.
     *
     * Replacing the object mid-flight is safe, and only because of how the result comes back:
     * [execute] posts a callback that reads the `automaton` field at the time it runs, so a result
     * for a command started by the discarded instance lands on the new one, finds no pending action
     * of that kind, and returns null. Nothing is lost with the old object - the command was already
     * sent, and the only record that outlives any of this is the value [SpeakerCoverSettings]
     * remembered when the write was acknowledged.
     */
    private fun seedAutomaton() {
        automaton = SpeakerCoverAutomaton(
            armed = !SpeakerCoverSettings.autoOpened(this),
            driverHasTheWheel = SpeakerCoverSettings.driverTookOver(this),
        )
    }

    /**
     * A position asked for from the panel, after which the covers are the driver's for this boot.
     *
     * The takeover is written before the command rather than after it: the press is the fact, not
     * its outcome, and a motor call that fails and retries for a minute must not leave a window in
     * which a restarted service would decide the driver had never touched anything.
     *
     * A null request here can only mean a command is already in flight. The desire is queued inside
     * the automaton and comes back out of [SpeakerCoverAutomaton.onMotorResult], so a wind-down
     * waits for it in [execute] rather than stopping the service out from under it.
     */
    private fun byHand(open: Boolean, thenStop: Boolean) {
        windingDown = thenStop
        SpeakerCoverSettings.rememberDriverTookOver(this)
        automaton.onManualPosition(open, SystemClock.uptimeMillis())?.let(::execute)
    }

    /**
     * Switching the automation off, which is not the same as asking for the covers.
     *
     * Leaving them open matters: a close suppresses the amplifier's own auto-lift for the ignition
     * cycle, so a user who turns the feature off with the covers shut would be left with covers
     * that nothing raises. But it is only worth doing when the covers are not already out, and it
     * must never overrule the driver: under this contract a remembered close can only have come
     * from the «Опустить» button, and answering that with an open would be the automation getting
     * the last word on its way out.
     */
    private fun partingOpen() {
        windingDown = true
        val closedByHand =
            SpeakerCoverSettings.lastCommandValue(this) == SpeakerCoverMotorProtocol.CLOSE
        val request = if (closedByHand) {
            null
        } else {
            automaton.onBestEffortOpen(SystemClock.uptimeMillis(), "автоматика выключена")
        }
        if (request != null) {
            execute(request)
        } else if (automaton.pendingAction == null) {
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
                SpeakerCoverMotor.execute(applicationContext, request)
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
                    // Only an opening that actually reached the motor spends the boot's one shot.
                    // Recorded here rather than at the wish, because a start whose command never
                    // landed is still owed its try - and a service restarted in between would read
                    // this flag as the automation having had its turn.
                    if (request.source == SpeakerCoverCommandSource.AUTOMATIC) {
                        SpeakerCoverSettings.rememberAutoOpened(applicationContext)
                    }
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
     * What is still going to happen by itself, which is at most one thing per boot.
     *
     * The tile used to name the ear the automation was listening with - "Слежу за воспроизведением"
     * or "Слежу за плеерами" - which was true and answered a question nobody in a car is asking.
     * What a driver needs from this line is whether the covers are still going to move on their
     * own, and there are exactly three answers now: not yet, already done, or not any more because
     * you took over. The sensor's failure keeps its place in the details, where a person looking
     * for it will find it and nobody else has to read it.
     *
     * It used to report STARTING without the audio sensor, which drew a spinner on the dashboard
     * tile - and on a car where that sensor is simply not available, nothing ever moved it on. The
     * tile turned that spinner indefinitely, promising a startup that had already finished.
     */
    private fun publishMonitoring() {
        publish(
            SpeakerCoverRuntimePhase.MONITORING,
            when {
                automaton.driverHasTheWheel -> "Управление у водителя до перезапуска машины"
                automaton.armed -> "Открою при первом воспроизведении"
                else -> "Автоматика отработала — дальше кнопками"
            },
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
                description = "Крышки открываются при первом воспроизведении, дальше — кнопками"
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
