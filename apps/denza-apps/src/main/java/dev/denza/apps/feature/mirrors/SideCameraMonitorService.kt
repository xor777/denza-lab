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
import dev.denza.apps.feature.vehicle.signal.TurnIndicatorMode
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
import java.util.concurrent.atomic.AtomicReference

class SideCameraMonitorService : Service() {
    private var executor: ScheduledExecutorService? = null
    private var signalExecutor: ExecutorService? = null
    private lateinit var adb: LocalAdbClient.PersistentShellSession
    private var turnSignalLease: VehicleSignalLease? = null
    private var switchSubscription: VehicleSignalEventSubscription? = null
    private var lampSubscription: VehicleSignalEventSubscription? = null
    private var avcStock: AvcStockClient? = null
    private var stockChoice: AvcTurnCameraChoice? = null
    private var stockCardVisible = false
    @Volatile private var lastStockMode: Int? = null
    private val transitionGate = MirrorTransitionGate()
    private var transitionState = MirrorTransitionState()
    private val preemptInFlight = AtomicBoolean()
    private var lastPublishedStatus: Pair<MirrorSide?, String>? = null
    private val pendingPublication = AtomicReference<StatusPublication?>()
    private var clusterDisplayId: Int? = null
    private var lastDisplayResolveMs = 0L
    private var lastShadowStatus = ""
    // Timing only; these observations never participate in the reducer or CAN policy.
    private var timingLastWindowSide: MirrorSide? = null
    private var lastLoggedStockSide: MirrorSide? = null
    private var timingLastAmbiguous = false
    private var timingPreviousReadStartedMs = -1L

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
        avcStock = AvcStockClient(this)
        // The lamps (flash mode) are what the stock camera itself follows; the raw lever phase
        // only warns of a side change early enough to tear our surface down first. Neither can
        // open a camera without AVC's own card of that side.
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
        lampSubscription = runCatching {
            turnSignalLease?.subscribeEvents(
                VehicleSignalKeys.TurnIndicatorMode,
                eventExecutor,
                ::onLampNotice,
            )
        }.onFailure { error ->
            Log.w(TAG, "turn-lamp events unavailable", error)
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
        lampSubscription?.close()
        lampSubscription = null
        signalExecutor?.shutdownNow()
        signalExecutor = null
        executor?.shutdownNow()
        executor = null
        avcStock?.close()
        avcStock = null
        stockChoice = null
        stockCardVisible = false
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
            val readStartedMs = SystemClock.elapsedRealtime()
            val windows = adb.shell("dumpsys window visible")
            val now = SystemClock.elapsedRealtime()
            val detection = SideCameraWindowDetector.analyze(windows, displayId)
            MirrorWindowDiagnostics.record(detection)
            val ambiguous = detection.avcCandidateBlocks > 0 &&
                (
                    detection.recognizedSide == null ||
                        detection.unrecognizedCandidates > 0
                )
            if (detection.recognizedSide != timingLastWindowSide || ambiguous != timingLastAmbiguous) {
                Log.i(
                    TAG,
                    "window timing; side=${detection.recognizedSide} ambiguous=$ambiguous" +
                        " read_started_ms=$readStartedMs observed_ms=$now" +
                        " previous_read_started_ms=$timingPreviousReadStartedMs",
                )
                timingLastWindowSide = detection.recognizedSide
                timingLastAmbiguous = ambiguous
            }
            timingPreviousReadStartedMs = readStartedMs
            if (!transitionGate.isRunning) return
            val stockSide = observeStock(detection, now)
            val mode = recordTurnSignalState(stockSide, now)
            applyTransition(stockSide, now, mode)
            releaseStaleClaimWhenIdle(detection)
        } catch (error: Exception) {
            setStatus(observedSide(), "ADB monitor error: ${shortError(error)}")
            updateNotification("ADB access needs attention")
        }
    }

    /**
     * The stock card a camera may take over. AVC is asked only while one of its cards is up or a
     * camera of ours is active: its Messenger runs on the thread that rebuilds the card.
     */
    private fun observeStock(detection: SideCameraDetection, now: Long): MirrorSide? {
        // Only a turn card's own windows count: the reverse and full-screen views are AVC windows
        // too, and asking AVC ten times a second while it draws the reverse view is the worst time.
        val cardVisible = detection.meterPipWindow || detection.hostPipWindow
        val active = transitionGate.read { transitionState.phase != MirrorTransitionPhase.IDLE } ||
            ClusterSceneService.cameraRuntimeSnapshot().phase.let {
                it == CameraRuntimePhase.STARTING || it == CameraRuntimePhase.READY
            }
        val stock = avcStock
        if (stockChoice == null || (cardVisible && !stockCardVisible)) {
            // Where the owner put the card: once connected, then again at each new stock episode.
            stock?.turnCameraChoice()?.let { choice ->
                if (choice != stockChoice) Log.i(TAG, "stock turn camera choice=$choice")
                stockChoice = choice
            }
        }
        stockCardVisible = cardVisible
        val mode = if (cardVisible || active) stock?.mode() else AvcStockMode.IDLE
        lastStockMode = mode
        val side = MirrorStockPip.side(detection, mode, stockChoice)
        if (side != lastLoggedStockSide) {
            Log.i(
                TAG,
                "stock card; side=$side mode=$mode choice=$stockChoice meter=${detection.meterPipWindow}" +
                    " host=${detection.hostPipWindow} observed_ms=$now",
            )
            lastLoggedStockSide = side
        }
        return side
    }

    /**
     * A surface of ours may still sit in AVC's owner field (we skipped a free because AVC had taken
     * its renderer back, or this process died holding it). Free it only when AVC answers idle and
     * shows no card: then nothing of AVC's can freeze.
     */
    private fun releaseStaleClaimWhenIdle(detection: SideCameraDetection) {
        if (!AvcDisplayClaim.isClaimed(this) || detection.avcCandidateBlocks > 0) return
        val runtime = ClusterSceneService.cameraRuntimeSnapshot().phase
        if (runtime != CameraRuntimePhase.IDLE && runtime != CameraRuntimePhase.FAILED) return
        if (transitionGate.read { transitionState.phase != MirrorTransitionPhase.IDLE }) return
        if (avcStock?.mode() != AvcStockMode.IDLE) return
        AvcIdleRelease.release(this)
    }

    /** One cached read feeds diagnostics and the lamps the reducer follows. */
    private fun recordTurnSignalState(stockSide: MirrorSide?, now: Long): VehicleSignalState<TurnIndicatorMode> {
        val state = runCatching {
            turnSignalLease?.read(VehicleSignalKeys.TurnIndicatorMode, now)
                ?: MirrorTurnSignalDiagnostics.unavailable("listener not active")
        }.onFailure { error ->
            Log.w(TAG, "turn-signal observation failed", error)
        }.getOrElse { MirrorTurnSignalDiagnostics.unavailable(shortError(it)) }
        val snapshot = MirrorTurnSignalDiagnostics.record(state, stockSide, now)
        val status = "${snapshot.state}/${snapshot.windowSide}/${snapshot.agreement}"
        if (status != lastShadowStatus) {
            lastShadowStatus = status
            Log.i(TAG, "turn-signal shadow: ${snapshot.compact()}")
        }
        return state
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
                        MirrorSwitchPreemptionDecision.PREEMPT -> teardownActiveCameraLocked(
                            "lever moved " +
                                MirrorSwitchPreemption.onsetSide(event.value)?.name?.lowercase(),
                            event.observedAtElapsedMs,
                            blockSide = activeSide,
                        )
                    }
                }
                publishPending()
            }
            // The camera never depends on this feed, so losing it is a log line and nothing else.
            is VehicleSignalEventNotice.Unavailable -> Log.w(
                TAG,
                "switch feed ${notice.reason.name.lowercase()}; camera untouched",
            )
        }
    }

    /**
     * The lamps are the stock camera's own trigger. When they leave the side on screen (off,
     * hazard, the other side) our camera closes at once instead of waiting for AVC's two-second
     * tail: it never outlives the lamps, and never holds AVC's renderer into AVC's idle.
     */
    private fun onLampNotice(notice: VehicleSignalEventNotice<TurnIndicatorMode>) {
        if (!transitionGate.isRunning) return
        val event = (notice as? VehicleSignalEventNotice.Event)?.event ?: return
        val (lamp, _) = MirrorLamp.of(
            VehicleSignalState.Fresh(event.value, event.observedAtElapsedMs, event.observedAtElapsedMs),
        )
        transitionGate.runIfRunning {
            val activeSide = MirrorSwitchPreemption.activeCameraSide(
                transitionState,
                ClusterSceneService.cameraRuntimeSnapshot(),
            )
            if (MirrorTransitionReducer.lampsLeftSide(lamp, activeSide)) {
                teardownActiveCameraLocked(
                    "lamps ${lamp.name.lowercase()}",
                    event.observedAtElapsedMs,
                    blockSide = null,
                )
            }
        }
        publishPending()
    }

    /**
     * Must run under [transitionGate]. [blockSide] is set for a lever onset: the torn-down side's
     * stock card outlives a cancellation and is not a new request.
     */
    private fun teardownActiveCameraLocked(
        reason: String,
        observedAtMs: Long,
        blockSide: MirrorSide?,
    ) {
        val runtime = ClusterSceneService.cameraRuntimeSnapshot()
        val active = transitionState.phase == MirrorTransitionPhase.STARTING ||
            transitionState.phase == MirrorTransitionPhase.SHOWING ||
            runtime.phase == CameraRuntimePhase.STARTING ||
            runtime.phase == CameraRuntimePhase.READY
        if (!active || preemptInFlight.get()) return

        val acceptedAt = SystemClock.elapsedRealtime()
        transitionState = if (blockSide != null) {
            MirrorTransitionReducer.preempted(transitionState, runtime, acceptedAt, blockSide, reason)
        } else {
            MirrorTransitionReducer.lampsLeft(transitionState, runtime, acceptedAt, reason)
        }
        preemptInFlight.set(true)
        leaveRendererToStockIfItBindsIt()
        val commandGeneration = ClusterSceneService.preemptCamera(
            onLocalSurfaceDetached = {
                Log.i(
                    TAG,
                    "early teardown local surface detached; reason=$reason" +
                        " age=${SystemClock.elapsedRealtime() - observedAtMs}ms",
                )
            },
            onVendorFreeCompleted = {
                preemptInFlight.set(false)
                Log.i(
                    TAG,
                    "early teardown vendor free completed; reason=$reason" +
                        " age=${SystemClock.elapsedRealtime() - observedAtMs}ms",
                )
            },
        )
        Log.i(
            TAG,
            "early teardown accepted; reason=$reason age=${acceptedAt - observedAtMs}ms" +
                " commandGeneration=$commandGeneration runtime=${runtime.phase}",
        )
        queuePublicationLocked()
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
        stockSide: MirrorSide?,
        now: Long,
        mode: VehicleSignalState<TurnIndicatorMode>,
    ) {
        transitionGate.runIfRunning { applyTransitionLocked(stockSide, now, mode) }
        publishPending()
    }

    private fun applyTransitionLocked(
        stockSide: MirrorSide?,
        now: Long,
        mode: VehicleSignalState<TurnIndicatorMode>,
    ) {
        val runtime = ClusterSceneService.cameraRuntimeSnapshot()
        val (lamp, lampObservedAt) = MirrorLamp.of(mode)
        val result = MirrorTransitionReducer.reduce(
            transitionState,
            MirrorTransitionObservation(
                stockSide = stockSide,
                lamp = lamp,
                lampObservedAtMs = lampObservedAt,
                runtime = runtime,
                nowMs = now,
                preemptionInFlight = preemptInFlight.get(),
                frameAgeMs = MirrorFrameWatch.ageMs(now),
            ),
        )
        if (result.state.phase != transitionState.phase || result.command != MirrorTransitionCommand.None) {
            Log.i(
                TAG,
                "transition ${transitionState.phase} -> ${result.state.phase}" +
                    " (${result.state.details}); stock=$stockSide lamp=$lamp runtime=${runtime.phase}",
            )
        }
        transitionState = result.state
        when (val command = result.command) {
            is MirrorTransitionCommand.Show -> {
                Log.i(TAG, "command: show ${command.side}; observed_ms=$now command_ms=${SystemClock.elapsedRealtime()}")
                startOverlay(command.side, now, runtime)
            }
            MirrorTransitionCommand.Hide -> {
                leaveRendererToStockIfItBindsIt()
                Log.i(TAG, "command: hide")
                ClusterSceneService.preemptCamera(
                    onVendorFreeCompleted = { Log.i(TAG, "command: hide finished") },
                )
            }
            MirrorTransitionCommand.None -> Unit
        }
        queuePublicationLocked()
    }

    /** See [MirrorFrameWatch.stockTakesOver]: the last mode AVC reported decides it. */
    private fun leaveRendererToStockIfItBindsIt() {
        val mode = lastStockMode ?: return
        if (!AvcStockMode.bindsRendererItself(mode)) return
        MirrorFrameWatch.stockTakesOver()
        Log.i(TAG, "AVC mode $mode binds its renderer itself; our surface is detached, not freed")
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
            transitionState = MirrorTransitionReducer.failed(
                transitionState,
                runtime,
                now,
                side,
                "camera dispatch failed: ${shortError(error)}",
            )
        }
    }

    private class StatusPublication(
        val side: MirrorSide?,
        val details: String,
        val notification: String,
    )

    /** Must run under [transitionGate]. Records the status to publish once the gate is released. */
    private fun queuePublicationLocked() {
        val side = transitionState.side.takeIf { transitionState.phase == MirrorTransitionPhase.SHOWING }
        val details = transitionState.details.ifBlank {
            transitionState.phase.name.lowercase()
        }
        val status = side to details
        if (lastPublishedStatus == status) return
        lastPublishedStatus = status
        val mirror = transitionState.side?.name?.lowercase()
        val notification = when (transitionState.phase) {
            MirrorTransitionPhase.STARTING -> "Starting $mirror mirror"
            MirrorTransitionPhase.SHOWING -> "Showing $mirror mirror"
            MirrorTransitionPhase.IDLE -> "Mirrors are ready"
        }
        pendingPublication.set(StatusPublication(side, details, notification))
    }

    /**
     * Publishing refreshes the whole app repository, which is far too slow to do under the gate:
     * a lever onset takes that gate to detach a surface within milliseconds. So it runs after the
     * gate is released, on whichever thread queued it last.
     */
    private fun publishPending() {
        val publication = pendingPublication.getAndSet(null) ?: return
        setStatus(publication.side, publication.details)
        updateNotification(publication.notification)
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
