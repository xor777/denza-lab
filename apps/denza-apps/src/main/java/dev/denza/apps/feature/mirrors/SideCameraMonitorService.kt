package dev.denza.apps.feature.mirrors

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import dev.denza.apps.MainActivity
import dev.denza.apps.R
import dev.denza.apps.DenzaAppRepository
import dev.denza.apps.adb.DenzaLocalAdb
import dev.denza.apps.feature.cluster.ClusterDisplayResolver
import dev.denza.apps.feature.cluster.ClusterDisplaySelection
import dev.denza.apps.feature.cluster.ClusterSceneService
import dev.denza.apps.feature.cluster.CameraRuntimeSnapshot
import dev.denza.apps.feature.cluster.CameraRuntimePhase
import dev.denza.apps.feature.vehicle.signal.DenzaVehicleSignals
import dev.denza.apps.feature.vehicle.signal.TurnSwitchPhase
import dev.denza.apps.feature.vehicle.signal.VehicleSignalConsumerId
import dev.denza.apps.feature.vehicle.signal.VehicleSignalDemand
import dev.denza.apps.feature.vehicle.signal.VehicleSignalEventNotice
import dev.denza.apps.feature.vehicle.signal.VehicleSignalEventSubscription
import dev.denza.apps.feature.vehicle.signal.VehicleSignalKeys
import dev.denza.apps.feature.vehicle.signal.VehicleSignalLease
import dev.denza.apps.feature.vehicle.signal.VehicleSignalState
import dev.denza.disharebridge.LocalAdbClient
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SideCameraMonitorService : Service() {
    private var executor: ScheduledExecutorService? = null
    private var signalExecutor: ExecutorService? = null
    private lateinit var adb: LocalAdbClient.PersistentShellSession
    private var turnSignalLease: VehicleSignalLease? = null
    private var switchSubscription: VehicleSignalEventSubscription? = null
    private val transitionGate = MirrorTransitionGate()
    private var transitionState = MirrorTransitionState()
    private val preemptInFlight = AtomicBoolean()
    private var lastPublishedStatus: Pair<MirrorSide?, String>? = null
    private var clusterDisplayId: Int? = null
    private var lastDisplayResolveMs = 0L
    private var lastShadowStatus = ""

    override fun onCreate() {
        super.onCreate()
        adb = DenzaLocalAdb.client(this).openPersistentShell()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitor(disableDesired = true)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Mirrors are ready"))
        startMonitor()
        return START_STICKY
    }

    override fun onDestroy() {
        stopMonitor(disableDesired = false)
        adb.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMonitor() {
        MirrorsSettings.setEnabled(this, true)
        if (!transitionGate.start()) return
        lastShadowStatus = ""
        MirrorTurnSignalDiagnostics.reset(SystemClock.elapsedRealtime())
        val eventExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "denza-mirror-signal-guard").apply { isDaemon = true }
        }
        signalExecutor = eventExecutor
        // Both keys stay leased: the mode read feeds the bounded shadow diagnostics, the raw phase
        // read tells the reducer whether the lever is still moving. Neither can open a camera.
        turnSignalLease = runCatching {
            DenzaVehicleSignals.hub(this).acquire(
                VehicleSignalConsumerId("mirrors"),
                setOf(
                    VehicleSignalDemand(
                        VehicleSignalKeys.TurnIndicatorMode,
                        TURN_SIGNAL_MAX_AGE_MS,
                    ),
                    VehicleSignalDemand(
                        VehicleSignalKeys.TurnSwitchPhase,
                        TURN_SIGNAL_MAX_AGE_MS,
                    ),
                ),
            )
        }.onFailure { error ->
            Log.w(TAG, "turn-signal guard unavailable", error)
        }.getOrNull()
        switchSubscription = runCatching {
            turnSignalLease?.subscribeEvents(
                VehicleSignalKeys.TurnSwitchPhase,
                eventExecutor,
                ::onSwitchNotice,
            )
        }.onFailure { error ->
            Log.w(TAG, "turn-switch events unavailable", error)
        }.getOrNull()
        executor = Executors.newSingleThreadScheduledExecutor().also { scheduler ->
            scheduler.execute(::grantOverlayPermission)
            scheduler.scheduleWithFixedDelay(::poll, 0L, POLL_MS, TimeUnit.MILLISECONDS)
        }
        setStatus(null, "monitor running")
    }

    private fun stopMonitor(disableDesired: Boolean) {
        if (disableDesired) MirrorsSettings.setEnabled(this, false)
        transitionGate.stop {
            // Wait for any in-flight transition command, then close the lifecycle gate. No later
            // poll or signal callback can issue a newer Show after this final hide.
            ClusterSceneService.hideCameraSync(FINISH_SYNC_TIMEOUT_MS)
            transitionState = MirrorTransitionState(details = "monitor stopped")
            preemptInFlight.set(false)
            setStatus(null, "monitor stopped")
        }
        val signalLease = turnSignalLease
        turnSignalLease = null
        switchSubscription?.close()
        switchSubscription = null
        signalExecutor?.shutdownNow()
        signalExecutor = null
        executor?.shutdownNow()
        executor = null
        // Signal teardown cannot stand between a stop request and hiding the camera.
        signalLease?.close()
    }

    private fun grantOverlayPermission() {
        try {
            adb.shell("cmd appops set '${packageName}' SYSTEM_ALERT_WINDOW allow")
        } catch (error: Exception) {
            setStatus(null, "overlay access pending: ${shortError(error)}")
        }
    }

    private fun poll() {
        if (!transitionGate.isRunning) return
        val displayId = resolveClusterDisplay(SystemClock.elapsedRealtime()) ?: return

        try {
            val windows = adb.shell("dumpsys window visible")
            val now = SystemClock.elapsedRealtime()
            val detection = SideCameraWindowDetector.analyze(windows, displayId)
            MirrorWindowDiagnostics.record(detection)
            val ambiguous = detection.avcCandidateBlocks > 0 &&
                (
                    detection.recognizedSide == null ||
                        detection.unrecognizedCandidates > 0
                )
            if (!transitionGate.isRunning) return
            recordTurnSignalState(detection.recognizedSide, now)
            applyTransition(detection.recognizedSide, now, ambiguous)
        } catch (error: Exception) {
            setStatus(observedSide(), "ADB monitor error: ${shortError(error)}")
            updateNotification("ADB access needs attention")
        }
    }

    /** Bounded support diagnostics only. The confirmed mode never gates a camera command. */
    private fun recordTurnSignalState(windowSide: MirrorSide?, now: Long) {
        val state = runCatching {
            turnSignalLease?.read(VehicleSignalKeys.TurnIndicatorMode, now)
                ?: MirrorTurnSignalDiagnostics.unavailable("listener not active")
        }.onFailure { error ->
            Log.w(TAG, "turn-signal observation failed", error)
        }.getOrElse { MirrorTurnSignalDiagnostics.unavailable(shortError(it)) }
        val snapshot = MirrorTurnSignalDiagnostics.record(state, windowSide, now)
        val status = "${snapshot.state}/${snapshot.windowSide}/${snapshot.agreement}"
        if (status != lastShadowStatus) {
            lastShadowStatus = status
            Log.i(TAG, "turn-signal shadow: ${snapshot.compact()}")
        }
    }

    private fun onSwitchNotice(notice: VehicleSignalEventNotice<TurnSwitchPhase>) {
        if (!transitionGate.isRunning) return
        when (notice) {
            is VehicleSignalEventNotice.Event -> {
                val event = notice.event
                transitionGate.runIfRunning {
                    val activeSide = MirrorSwitchPreemption.activeCameraSide(
                        transitionState,
                        ClusterSceneService.cameraRuntimeSnapshot(),
                    )
                    when (MirrorSwitchPreemption.decide(event.value, activeSide)) {
                        MirrorSwitchPreemptionDecision.NONE -> Unit
                        MirrorSwitchPreemptionDecision.KEEP_CURRENT_SIDE -> Log.i(
                            TAG,
                            "same-side lever onset ignored; phase=${event.value.rawValue}" +
                                " side=$activeSide sequence=${event.sequence}",
                        )
                        MirrorSwitchPreemptionDecision.PREEMPT -> preemptActiveCameraLocked(
                            "live switch onset ${event.value.rawValue}",
                            event.observedAtElapsedMs,
                            MirrorQuarantineRecovery.OTHER_SIDE_AFTER_TEARDOWN,
                            activeSide,
                        )
                    }
                }
            }
            // The camera never depends on this feed, so losing it is a log line and nothing else.
            is VehicleSignalEventNotice.Unavailable -> Log.w(
                TAG,
                "switch feed ${notice.reason.name.lowercase()}; camera untouched",
            )
        }
    }

    /** Must run under [transitionGate]. */
    private fun preemptActiveCameraLocked(
        reason: String,
        observedAtMs: Long,
        recovery: MirrorQuarantineRecovery,
        preemptedSide: MirrorSide?,
    ) {
        val runtime = ClusterSceneService.cameraRuntimeSnapshot()
        val active = transitionState.phase == MirrorTransitionPhase.STARTING ||
            transitionState.phase == MirrorTransitionPhase.SHOWING ||
            runtime.phase == CameraRuntimePhase.STARTING ||
            runtime.phase == CameraRuntimePhase.READY
        if (!active || preemptInFlight.get()) return

        val acceptedAt = SystemClock.elapsedRealtime()
        transitionState = MirrorTransitionReducer.quarantine(
            transitionState,
            runtime,
            acceptedAt,
            reason,
            recovery,
            preemptedSide,
        )
        preemptInFlight.set(true)
        val commandGeneration = ClusterSceneService.preemptCamera(
            onLocalSurfaceDetached = {
                Log.i(
                    TAG,
                    "CAN preempt local surface detached; reason=$reason" +
                        " age=${SystemClock.elapsedRealtime() - observedAtMs}ms",
                )
            },
            onVendorFreeCompleted = {
                preemptInFlight.set(false)
                Log.i(
                    TAG,
                    "CAN preempt vendor free completed; reason=$reason" +
                        " age=${SystemClock.elapsedRealtime() - observedAtMs}ms",
                )
            },
        )
        Log.i(
            TAG,
            "CAN preempt accepted; reason=$reason age=${acceptedAt - observedAtMs}ms" +
                " commandGeneration=$commandGeneration runtime=${runtime.phase}",
        )
        publishTransition()
    }

    private fun resolveClusterDisplay(now: Long): Int? {
        clusterDisplayId?.let { return it }
        if (now - lastDisplayResolveMs < DISPLAY_RETRY_MS) return null
        lastDisplayResolveMs = now
        return when (val selection = ClusterDisplayResolver.resolveCameraOverlay(this)) {
            is ClusterDisplaySelection.Selected -> selection.display.id.also {
                clusterDisplayId = it
            }
            is ClusterDisplaySelection.NeedsVerification -> {
                setStatus(null, "camera overlay display is ambiguous")
                updateNotification("Camera display needs verification")
                null
            }
            ClusterDisplaySelection.Missing -> {
                setStatus(null, "camera overlay display not found")
                updateNotification("Camera display not found")
                null
            }
        }
    }

    private fun applyTransition(
        stockWindowSide: MirrorSide?,
        now: Long,
        runtimeWindowAmbiguous: Boolean,
    ) = transitionGate.runIfRunning {
        val runtime = ClusterSceneService.cameraRuntimeSnapshot()
        // The stock AVC window is the only authority for Show. The retained raw lever phase is
        // read for one narrow purpose: it tells the reducer to wait through transient stock
        // window ambiguity instead of quarantining. It can never open a camera.
        val leverEngaged = runCatching {
            turnSignalLease?.read(VehicleSignalKeys.TurnSwitchPhase, now)
        }.getOrNull()
            .let { it as? VehicleSignalState.Fresh }
            ?.value
            ?.let(MirrorSwitchPreemption::isTransitionInProgress)
            ?: false
        val result = MirrorTransitionReducer.reduce(
            transitionState,
            MirrorTransitionObservation(
                requestedSide = stockWindowSide,
                runtime = runtime,
                nowMs = now,
                runtimeWindowAmbiguous = runtimeWindowAmbiguous,
                preemptionInFlight = preemptInFlight.get(),
                leverEngaged = leverEngaged,
            ),
        )
        if (result.state.phase != transitionState.phase) {
            Log.i(
                TAG,
                "transition ${transitionState.phase} -> ${result.state.phase}" +
                    " (${result.state.details}); requested=$stockWindowSide" +
                    " ambiguous=$runtimeWindowAmbiguous runtime=${runtime.phase}",
            )
        }
        transitionState = result.state
        when (val command = result.command) {
            is MirrorTransitionCommand.Show -> {
                Log.i(TAG, "command: show ${command.side}")
                startOverlay(command.side, now, runtime)
            }
            MirrorTransitionCommand.Hide -> {
                Log.i(TAG, "command: hide")
                ClusterSceneService.preemptCamera(
                    onVendorFreeCompleted = { Log.i(TAG, "command: hide finished") },
                )
            }
            MirrorTransitionCommand.None -> Unit
        }
        publishTransition()
    }

    private fun startOverlay(
        side: MirrorSide,
        now: Long,
        runtime: CameraRuntimeSnapshot,
    ) {
        val config = MirrorCameraConfig(
            side = side,
            position = MirrorsSettings.position(this),
            processingEnabled = MirrorsSettings.processingEnabled(this),
        )
        try {
            ClusterSceneService.showCamera(this, config)
        } catch (error: RuntimeException) {
            transitionState = MirrorTransitionReducer.quarantine(
                transitionState,
                runtime,
                now,
                "camera dispatch failed: ${shortError(error)}",
            )
        }
    }

    private fun publishTransition() {
        val side = observedSide()
        val details = transitionState.details.ifBlank {
            transitionState.phase.name.lowercase()
        }
        val status = side to details
        if (lastPublishedStatus == status) return
        lastPublishedStatus = status
        setStatus(side, details)
        updateNotification(
            when (transitionState.phase) {
                MirrorTransitionPhase.STARTING -> "Starting ${transitionState.side?.name?.lowercase()} mirror"
                MirrorTransitionPhase.SHOWING -> "Showing ${transitionState.side?.name?.lowercase()} mirror"
                MirrorTransitionPhase.QUARANTINED -> "Mirror waiting for neutral"
                MirrorTransitionPhase.IDLE -> "Mirrors are ready"
            },
        )
    }

    private fun observedSide(): MirrorSide? = transitionGate.read {
        transitionState.side.takeIf { transitionState.phase == MirrorTransitionPhase.SHOWING }
    }

    private fun setStatus(side: MirrorSide?, details: String) {
        MirrorsSettings.setObserved(this, side, details)
        DenzaAppRepository.refresh()
    }

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mirrors", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_denza_apps)
            .setContentTitle("Denza Apps · Mirrors")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "DenzaMirrorMonitor"
        private const val CHANNEL_ID = "denza_mirrors"
        private const val NOTIFICATION_ID = 4203
        private const val ACTION_START = "dev.denza.apps.mirrors.START"
        private const val ACTION_STOP = "dev.denza.apps.mirrors.STOP"
        private const val POLL_MS = 100L
        private const val DISPLAY_RETRY_MS = 2_000L
        private const val FINISH_SYNC_TIMEOUT_MS = 250L
        private const val TURN_SIGNAL_MAX_AGE_MS = 8_000L

        fun start(context: Context) {
            MirrorsSettings.setEnabled(context, true)
            context.startForegroundService(
                Intent(context, SideCameraMonitorService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            MirrorsSettings.setEnabled(context, false)
            context.startService(
                Intent(context, SideCameraMonitorService::class.java).setAction(ACTION_STOP),
            )
        }

        private fun shortError(error: Throwable): String =
            error::class.java.simpleName + error.message?.let { " $it" }.orEmpty()
    }
}
