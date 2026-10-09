package dev.denza.apps

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.core.FeatureWords
import dev.denza.apps.feature.cluster.ClusterDisplayResolver
import dev.denza.apps.feature.cluster.ClusterDisplayDescriptor
import dev.denza.apps.feature.cluster.ClusterDisplaySelection
import dev.denza.apps.feature.cluster.ClusterMapPlacement
import dev.denza.apps.feature.cluster.ClusterSceneService
import dev.denza.apps.feature.adb.AdbAutostartRetryAction
import dev.denza.apps.feature.adb.AdbAutostartRetryPolicy
import dev.denza.apps.feature.adb.AdbPortRestore
import dev.denza.apps.feature.adb.AdbRestore
import dev.denza.apps.feature.adb.AdbRestoreSnapshot
import dev.denza.apps.feature.adb.AdbRescueCoordinator
import dev.denza.apps.feature.adb.AdbRescuePhase
import dev.denza.apps.feature.adb.AdbRescueSnapshot
import dev.denza.apps.feature.adb.AdbStartupEntryAction
import dev.denza.apps.feature.adb.AdbStartupGatePolicy
import dev.denza.apps.feature.defaultapps.DefaultAppsCatalogCache
import dev.denza.apps.feature.defaultapps.DefaultAppsRuntime
import dev.denza.apps.feature.defaultapps.DefaultAppsUiState
import dev.denza.apps.feature.fse.FseInstallApp
import dev.denza.apps.feature.fse.FseInstallRuntime
import dev.denza.apps.feature.fse.FseInstallState
import dev.denza.apps.feature.hud.HudGuidanceRider
import dev.denza.apps.feature.hud.HudGuidanceRuntime
import dev.denza.apps.feature.hud.HudGuidanceSettings
import dev.denza.apps.feature.hud.HudGuidanceStatus
import dev.denza.apps.feature.hud.HudNotificationAccess
import dev.denza.apps.feature.cloud.CloudLinkController
import dev.denza.apps.feature.cloud.CloudLinkRuntime
import dev.denza.apps.feature.cloud.CloudLinkService
import dev.denza.apps.feature.cloud.CloudLinkSettings
import dev.denza.apps.feature.cloud.CloudLinkStatus
import dev.denza.apps.feature.cloud.CloudNetwork
import dev.denza.apps.feature.locale.SystemLanguage
import dev.denza.apps.feature.locale.SystemLanguageSnapshot
import dev.denza.apps.feature.media.MediaKeyRider
import dev.denza.apps.feature.mirrors.MirrorDisplayReadiness
import dev.denza.apps.feature.mirrors.MirrorsPosition
import dev.denza.apps.feature.mirrors.MirrorsSettings
import dev.denza.apps.feature.mirrors.SideCameraMonitorService
import dev.denza.apps.feature.navigation.NavigationCoordinator
import dev.denza.apps.feature.navigation.NavigationAppPolicy
import dev.denza.apps.feature.navigation.NavigationPlacementPolicy
import dev.denza.apps.feature.navigation.NavigationSettings
import dev.denza.apps.feature.navigation.SteeringWheelNavigationAccessCoordinator
import dev.denza.apps.feature.simulcast.SimulcastAppChoices
import dev.denza.apps.feature.simulcast.SimulcastApps
import dev.denza.apps.feature.simulcast.SimulcastBlocker
import dev.denza.apps.feature.simulcast.SimulcastCoordinator
import dev.denza.apps.feature.simulcast.SimulcastIntegration
import dev.denza.apps.feature.simulcast.SimulcastOverlayService
import dev.denza.apps.feature.simulcast.SimulcastReconcileEvent
import dev.denza.apps.feature.simulcast.SimulcastScreenDiagnostics
import dev.denza.apps.feature.split.SplitDiagnostics
import dev.denza.apps.feature.split.SplitLauncherIconController
import dev.denza.apps.feature.split.SplitScreenCoordinator
import dev.denza.apps.feature.split.SplitScreenPhase
import dev.denza.apps.feature.split.SplitScreenSession
import dev.denza.apps.feature.split.SplitScreenSettings
import dev.denza.apps.feature.split.SplitScreenToggleController
import dev.denza.apps.feature.speaker.SpeakerCoverRuntime
import dev.denza.apps.feature.speaker.SpeakerCoverService
import dev.denza.apps.feature.speaker.SpeakerCoverSettings
import dev.denza.apps.feature.speaker.SpeakerCoverStatus
import dev.denza.apps.feature.split.SplitLauncherEntryActivity
import dev.denza.apps.feature.weather.WeatherAdapterScheduler
import dev.denza.apps.feature.weather.WeatherAdapterState
import dev.denza.apps.platform.accessibility.AccessibilityHealth
import dev.denza.apps.platform.accessibility.AccessibilityHost
import dev.denza.apps.platform.accessibility.AccessibilityRepair
import dev.denza.apps.platform.media.MediaSessionAccess
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How many applications the projection carries, where the screen can read it.
 *
 * [SimulcastApps] is package-private - it is the row's own storage and has no business being
 * reachable from the UI - but the chooser's header has to say the allowance out loud, and a 6
 * typed into a Russian sentence in `ui/` is a second copy of the limit waiting to disagree with
 * the first. This is the one value that crosses, and it crosses by reading the original.
 */
const val SIMULCAST_MAX_SELECTED: Int = SimulcastApps.MAX_SELECTED

/**
 * One application on «Что транслировать». No picture: the screen draws it by [packageName] from
 * [AppIcons], so two readings of the same car are equal states.
 */
data class SimulcastAppChoice(
    val packageName: String,
    val label: String,
    val selected: Boolean,
    /**
     * Whether pressing this tile would change anything.
     *
     * The projection carries six applications at most, and the picker used to accept the seventh
     * tap and answer it with a line of text over the grid. The limit is decided where the limit
     * lives, and the tile that cannot be chosen simply looks like it.
     */
    val selectable: Boolean = true,
)

/**
 * One answer on «Что показывать»: an application, or this app's own instruments.
 *
 * [instruments] is what the chooser groups by and what draws the instruments with their glyph
 * rather than an application's icon; they have none of their own worth showing - the launcher icon
 * of this app would stand for the screen doing the choosing, not for the dial it puts on the panel.
 */
data class NavigationAppChoice(
    val packageName: String,
    val label: String,
    val selected: Boolean,
    val instruments: Boolean = false,
)

data class DenzaUiState(
    val simulcast: FeatureSnapshot = FeatureReducer.disabled(FeatureId.SIMULCAST),
    val mirrors: FeatureSnapshot = FeatureReducer.disabled(FeatureId.MIRRORS),
    val navigation: FeatureSnapshot = FeatureSnapshot(
        id = FeatureId.NAVIGATION,
        desiredEnabled = false,
        status = FeatureStatus.READY,
    ),
    val splitScreen: FeatureSnapshot = FeatureReducer.disabled(FeatureId.SPLIT_SCREEN),
    val hudGuidance: FeatureSnapshot = FeatureReducer.disabled(FeatureId.HUD_GUIDANCE),
    val speakerCovers: FeatureSnapshot = FeatureReducer.disabled(FeatureId.SPEAKER_COVERS),
    /**
     * A cover report on the wire, for the second «Поднять» is greyed.
     *
     * Not part of the feature's status - a one-off action never is, on any tile - and read
     * separately because the button answers with the switch off too.
     */
    val speakerCoversReporting: Boolean = false,
    /** The car's link to the cloud over Wi-Fi: the driver's wish and the stock client's last reading. */
    val cloudLink: FeatureSnapshot = FeatureReducer.disabled(FeatureId.CLOUD_LINK),
    /**
     * Whether the car keeps client Wi-Fi on while it sleeps - the car's own setting, read from the
     * car, so the panel's switch can only ever say what the car will do. Null until it answers.
     */
    val cloudWifiRetained: Boolean? = null,
    /** A cloud switch is on the wire; the panel greys both until the car answers. */
    val cloudLinkBusy: Boolean = false,
    val fseInstaller: FeatureSnapshot = FeatureSnapshot(
        id = FeatureId.FSE_INSTALLER,
        desiredEnabled = false,
        status = FeatureStatus.READY,
    ),
    val navigationButtonLabel: String = "На приборку",
    val navigationSteeringWheelButton: Boolean = false,
    val navigationSteeringWheelButtonReady: Boolean = false,
    val navigationSteeringWheelButtonRepairing: Boolean = false,
    val navigationPlacement: ClusterMapPlacement = ClusterMapPlacement.FULL,
    /** Placements the current choice actually has; a single entry means there is nothing to pick. */
    val navigationPlacements: List<ClusterMapPlacement> = ClusterMapPlacement.entries,
    val navigationAppLabel: String = NavigationAppPolicy.DASHBOARD_LABEL,
    /** What is chosen, as the panel's row draws it; the page's whole list is [navigationAppChoices]. */
    val navigationAppChoice: NavigationAppChoice = NavigationAppChoices.instruments(selected = true),
    /**
     * Everything «Что показывать» offers, read when the page opens rather than on every refresh:
     * it is the car's whole launcher catalog, icons and all, and nothing but that page draws it.
     */
    val navigationAppChoices: List<NavigationAppChoice> = emptyList(),
    val selectedAppCount: Int = 0,
    val selectedAppLabels: List<String> = emptyList(),
    val selectedApps: List<SimulcastAppChoice> = emptyList(),
    val mirrorsPosition: MirrorsPosition = MirrorsPosition.SIDES,
    val mirrorsProcessing: Boolean = true,
    val setupRunning: Boolean = false,
    val adbRescue: AdbRescueSnapshot = AdbRescueSnapshot(),
    val adbRestore: AdbRestoreSnapshot = AdbRestoreSnapshot(),
    val defaultApps: DefaultAppsUiState = DefaultAppsUiState(),
    val systemLanguage: SystemLanguageSnapshot = SystemLanguageSnapshot(),
    val weatherEnabled: Boolean = true,
    val weatherTemperature: Int? = null,
    val weatherUpdatedMillis: Long = 0L,
    /**
     * «Сервис» → «Технические сведения», in the report's format ([TechnicalReadings]). Built only
     * while the service panel is open ([ServiceReport]); what it last said otherwise.
     */
    val technicalDetails: String = "",
    /**
     * The service's «Журнал работы»: the split's last operations, step by step, in the report's
     * format ([TechnicalReadings]). Read off the journal on disk on a thread of its own, and built
     * with [technicalDetails].
     */
    val splitJournal: String = "",
    val clusterCandidates: List<ClusterDisplayDescriptor> = emptyList(),
    /** Which screen the instruments are going to, said the way the service panel says it. */
    val clusterDisplayLabel: String = "Определяется автоматически",
    /** The screen chosen by hand on the service's «Приборный экран» page, or null when the app picks. */
    val clusterDisplayOverride: Int? = null,
    /** The screen the app would pick by itself, named as the page names it; null when it cannot. */
    val clusterDisplayAutomatic: String? = null,
    /**
     * Everything «Что транслировать» offers, read when its page opens rather than on every
     * recompute - the car's launcher catalog, and nothing but that page draws it.
     */
    val appChoices: List<SimulcastAppChoice> = emptyList(),
    /** What «Экран справа» offers, read when its chooser opens (`FseInstallRuntime.refreshApps`). */
    val fseInstallApps: List<FseInstallApp> = emptyList(),
)

/**
 * What a recompute publishes while local ADB is not trusted: the gate's own state, and nothing else.
 *
 * The tiles keep their last healthy state, because a feature probe that cannot reach the shell
 * would only turn one missing prerequisite into a wall of unrelated errors. The service's report
 * is not a recompute's to publish at all: it is built while the panel is open, gate or no gate
 * ([ServiceReport]) - it reads prefs, the package manager, the displays and what this process
 * holds, and none of it needs the shell. Kept the way the tiles are, it used to be blank behind the
 * gate's seven-tap door on a fresh process, exactly when an owner opens it to send a screenshot.
 */
internal fun DenzaUiState.behindAdbGate(
    adbRescue: AdbRescueSnapshot,
    adbRestore: AdbRestoreSnapshot = this.adbRestore,
): DenzaUiState = copy(adbRescue = adbRescue, adbRestore = adbRestore)

/** Android-facing state owner shared by the Compose shell and runtime services. */
object DenzaAppRepository {
    private const val TAG = "DenzaApps.Repository"

    /**
     * Where the choosers read the launcher catalog: off the main thread, and not behind a
     * passenger install, which can take minutes.
     */
    private val catalogExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "denza-catalog").apply { isDaemon = true }
    }
    private val adbRuntimeStarted = AtomicBoolean(false)
    private val adbRuntimePassRunning = AtomicBoolean(false)
    private val displaysWatched = AtomicBoolean(false)
    private val overlayGrantWatched = AtomicBoolean(false)

    /** How often an open «Технические сведения» is built again: its readings carry ages. */
    private const val SERVICE_REPORT_PERIOD_MS = 1_000L

    private val serviceReport = ServiceReport(
        clock = ServiceReport.ThreadClock("denza-report"),
        periodMs = SERVICE_REPORT_PERIOD_MS,
        build = ::buildServicePages,
        publish = { pages ->
            stateStore.update { current ->
                current.copy(
                    technicalDetails = pages.technicalDetails,
                    splitJournal = pages.splitJournal,
                )
            }
        },
    )
    private val stateStore = DenzaUiStateStore()
    val state: StateFlow<DenzaUiState> = stateStore.state

    /** The one writer of the features' slices of [state]; see [DenzaStatePublisher]. */
    private val publisher = DenzaStatePublisher(
        store = stateStore,
        executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "denza-state").apply { isDaemon = true }
        },
        read = ::readSlices,
        log = StateRecomputes.log,
        elapsedMs = android.os.SystemClock::elapsedRealtime,
        onError = { cause, error -> Log.w(TAG, "state publication failed: $cause", error) },
    )

    init {
        // Every writer outside this object marks through StateMarks; from here on they land here.
        StateMarks.connect { slices, cause -> publisher.invalidate(slices, cause) }
    }

    /**
     * The «Shortcuts» tile's runtime: the three roles, read and written on its own thread and
     * published through their one field of [state]; see [DefaultAppsRuntime].
     */
    val defaultApps = DefaultAppsRuntime(
        state = stateStore.cell(
            get = DenzaUiState::defaultApps,
            set = { state, defaultApps -> state.copy(defaultApps = defaultApps) },
        ),
        context = { appContext },
    )

    /**
     * The «Экран справа» tile's runtime: its chooser's list and one install at a time, published
     * through the tile's two fields of [state]; see [FseInstallRuntime].
     */
    val fseInstall = FseInstallRuntime(
        state = stateStore.cell(
            get = { state -> FseInstallState(install = state.fseInstaller, apps = state.fseInstallApps) },
            set = { state, fse -> state.copy(fseInstaller = fse.install, fseInstallApps = fse.apps) },
        ),
        context = { appContext },
    )

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        initializeAdbGate(context.applicationContext)
    }

    fun recoverEnabledFeatures(context: Context) {
        initializeAdbGate(context.applicationContext)
    }

    fun recoverAutostart(context: Context, onChanged: (() -> Unit)? = null) {
        val app = context.applicationContext
        appContext = app
        AdbRescueCoordinator.initialize(app)
        AdbRestore.initialize(app)
        when (AdbAutostartRetryPolicy.action(AdbRescueCoordinator.snapshot().phase)) {
            AdbAutostartRetryAction.CHECK_ACCESS -> {
                refresh("autostart")
                AdbRescueCoordinator.checkAccess(app) {
                    onAdbRescueChanged(app)
                    onChanged?.invoke()
                }
            }
            AdbAutostartRetryAction.START_RUNTIME -> {
                startAdbRuntime(app)
                onChanged?.invoke()
            }
            AdbAutostartRetryAction.NONE -> {
                refresh("autostart")
                onChanged?.invoke()
            }
        }
    }

    /**
     * Every slice of the state is read again, soon, on the publisher's thread - never on the
     * caller's, and never before this returns.
     *
     * The rare paths that cannot say what changed: the activity coming back, the runtime starting.
     * Everything else names its slice ([invalidate]).
     */
    fun refresh() {
        refresh(trigger = "refresh")
    }

    /** As [refresh], recorded under [trigger] for «Сервис» → «Технические сведения». */
    fun refresh(trigger: String) {
        publisher.invalidateAll(trigger)
    }

    /** [slice] changed because of [cause]: it alone is read again, on the publisher's thread. */
    fun invalidate(slice: StateSlice, cause: String) {
        publisher.invalidate(slice, cause)
    }

    /** The slice behind a runtime feature's tile changed; see [StateSlice.of]. */
    fun invalidate(feature: FeatureId, cause: String) {
        StateSlice.of(feature)?.let { publisher.invalidate(it, cause) }
    }

    fun invalidate(slices: Set<StateSlice>, cause: String) {
        publisher.invalidate(slices, cause)
    }

    /**
     * Reads [slices] on the publisher's thread and says what they change. The car's ADB answer is
     * read every time: behind the gate it is all that is published.
     */
    private fun readSlices(slices: Set<StateSlice>): ((DenzaUiState) -> DenzaUiState)? {
        val context = appContext ?: return null
        val access = AdbAccessReading(AdbRescueCoordinator.snapshot(), AdbRestore.snapshot())
        if (access.adbRescue.phase != AdbRescuePhase.TRUSTED || !adbRuntimeStarted.get()) {
            // Keep the last healthy dashboard (or its neutral first-launch defaults) behind the
            // startup overlay. Individual feature probes must not turn a missing global ADB
            // prerequisite into a wall of unrelated errors. The runtime's start reads every slice.
            return { current -> current.behindAdbGate(access.adbRescue, access.adbRestore) }
        }
        val readings = listOf(access) + readEach(
            slices = slices.filter { it != StateSlice.ADB_ACCESS },
            read = { slice -> readSlice(context, slice) },
            failed = { slice, error ->
                Log.w(TAG, "state slice $slice could not be read", error)
                StateRecomputes.log.recordFailure(
                    atMs = android.os.SystemClock.elapsedRealtime(),
                    what = slice.name,
                    error = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(160),
                )
            },
        )
        return { current -> current.withReadings(readings) }
    }

    /**
     * The service panel is on screen, or gone: the report and the split's journal are built while
     * it is ([ServiceReport]), behind the ADB gate too, and not at all otherwise.
     */
    fun setServiceReportOpen(open: Boolean) {
        serviceReport.setOpen(open)
    }

    private fun buildServicePages(): ServiceReport.Pages {
        val context = checkNotNull(appContext) { "the report is opened before the app is initialised" }
        // What the split's journal said when last read; if the files moved since, they are read
        // again on the journal's own thread and the pages are built once more with what they say.
        SplitDiagnostics.rereadWork { serviceReport.rebuildNow() }
        return ServiceReport.Pages(
            technicalDetails = supportDiagnostics(context),
            splitJournal = SupportDiagnostics.splitJournal(),
        )
    }

    /**
     * One slice of the state, read from the car's settings and from what the features hold -
     * nothing here waits on the shell. Plain values: two reads of the same car are equal.
     */
    private fun readSlice(context: Context, slice: StateSlice): SliceReading = when (slice) {
        StateSlice.ADB_ACCESS -> AdbAccessReading(AdbRescueCoordinator.snapshot(), AdbRestore.snapshot())
        StateSlice.SIMULCAST -> {
            val selectedApps = selectedAppChoices(context)
            SimulcastReading(
                snapshot = SimulcastCoordinator.evaluate(SimulcastCoordinator.inspect(context)),
                selectedApps = selectedApps,
                selectedAppCount = selectedApps.size,
            )
        }
        StateSlice.MIRRORS -> MirrorsReading(
            snapshot = evaluateMirrors(context),
            position = MirrorsSettings.position(context),
            processing = MirrorsSettings.processingEnabled(context),
        )
        StateSlice.NAVIGATION -> {
            val session = NavigationCoordinator.snapshot()
            val selectedPackage = NavigationCoordinator.selectedPackage()
            val steeringWheelAccess = SteeringWheelNavigationAccessCoordinator.inspect(context)
            NavigationReading(
                snapshot = session.snapshot(),
                buttonLabel = session.buttonLabel,
                steeringWheelButton = steeringWheelAccess.desired,
                steeringWheelButtonReady = steeringWheelAccess.ready,
                steeringWheelButtonRepairing = steeringWheelAccess.desired &&
                    SteeringWheelNavigationAccessCoordinator.isRepairing(),
                placement = NavigationPlacementPolicy.resolve(
                    selectedPackage,
                    NavigationCoordinator.placement(),
                ),
                placements = NavigationPlacementPolicy.offered(selectedPackage),
                appChoice = NavigationAppChoices.chosen(context, selectedPackage),
            )
        }
        StateSlice.SPLIT_SCREEN -> SplitScreenReading(
            splitScreenSnapshot(
                launcherVisible = SplitLauncherIconController.isVisible(context),
                session = SplitScreenCoordinator.snapshot(),
            ),
        )
        StateSlice.HUD_GUIDANCE -> HudGuidanceReading(evaluateHudGuidance(context))
        StateSlice.SPEAKER_COVERS -> SpeakerCoversReading(
            snapshot = SpeakerCoverStatus.snapshot(
                enabled = SpeakerCoverSettings.isEnabled(context),
                sessionsObservable = MediaSessionAccess.isEnabled(context),
            ),
            reporting = SpeakerCoverRuntime.reporting,
        )
        // The last reading, never a fresh one: the car is asked on the cloud link's own thread.
        StateSlice.CLOUD_LINK -> CloudLinkReading(
            snapshot = CloudLinkRuntime.snapshot(
                enabled = CloudLinkSettings.isEnabled(context),
                network = CloudNetwork.usable(context),
                pendingDisable = CloudLinkSettings.pendingDisable(context),
                nowMs = android.os.SystemClock.elapsedRealtime(),
            ),
            wifiRetained = CloudLinkRuntime.car?.wifiRetained,
            busy = CloudLinkRuntime.busy,
        )
        StateSlice.CLUSTER_DISPLAY -> {
            val candidates = ClusterDisplayResolver.candidates(context)
            ClusterDisplayReading(
                candidates = candidates,
                label = clusterDisplayLabel(context, candidates),
                override = ClusterDisplayResolver.overrideId(context),
                automatic = clusterDisplayName(ClusterDisplayResolver.select(candidates), candidates),
            )
        }
        StateSlice.WEATHER -> WeatherReading(
            enabled = WeatherAdapterState.enabled(context),
            temperature = WeatherAdapterState.lastTemperature(context),
            updatedMillis = WeatherAdapterState.lastSuccessMillis(context),
        )
        // A tile on the main screen, read like every other tile's state: one call to
        // [Locale.getDefault], and the car is asked nothing.
        StateSlice.SYSTEM_LANGUAGE -> SystemLanguageReading(SystemLanguage.read())
    }

    fun setSimulcastEnabled(enabled: Boolean) {
        val context = appContext ?: return
        SimulcastIntegration.setEnabled(context, enabled)
        if (!enabled) {
            SimulcastIntegration.clearLastTargetPackage()
            SimulcastOverlayService.stopCurrent(context)
            invalidate(StateSlice.SIMULCAST, "simulcast switch")
            return
        }
        publisher.publish("simulcast switch") { current ->
            current.copy(simulcast = FeatureReducer.starting(FeatureId.SIMULCAST))
        }
        reconcileSimulcast(repairMissingSetup = true)
    }

    fun repairSimulcast() {
        reconcileSimulcast(repairMissingSetup = true, forceRepair = true)
    }

    fun launchSimulcast() {
        val context = appContext ?: return
        if (!SimulcastIntegration.isEnabled(context)) {
            SimulcastIntegration.setEnabled(context, true)
        }
        reconcileSimulcast(repairMissingSetup = true)
        val launch = context.packageManager.getLaunchIntentForPackage(
            SimulcastCoordinator.DISHARE_PACKAGE,
        )
        if (launch == null) {
            val blocked = SimulcastCoordinator.blockedSnapshot(SimulcastBlocker.DISHARE_UNAVAILABLE)
            publisher.publish("simulcast launch") { current -> current.copy(simulcast = blocked) }
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
    }

    /**
     * Read the car for «Что транслировать» without opening anything, off the main thread.
     *
     * Two surfaces show the list - the page inside the projection panel and the whole-sheet window
     * a tile waiting on a choice opens - and which one appears is the screen's business: each asks
     * for this as it opens. Until the first read lands the page says it is looking; after that it
     * shows the last list while it reads.
     */
    fun refreshAppChoices() {
        val context = appContext ?: return
        catalogExecutor.execute { publishAppChoices(context) }
    }

    private fun publishAppChoices(context: Context) {
        val installed = runCatching { DefaultAppsCatalogCache.installed(context) }
            .onFailure { Log.w(TAG, "Launcher catalog unavailable for the projection", it) }
            .getOrNull() ?: return
        val choices = SimulcastAppChoices.of(
            installed = installed,
            ownPackage = context.packageName,
            selected = SimulcastApps.getSelected(context),
        )
        stateStore.update { current ->
            // A press on the page moves its marks the moment it lands, and a list read before that
            // press must not put them back: once the page holds a list, its marks are the choice.
            val marked = if (current.appChoices.isEmpty()) {
                choices
            } else {
                SimulcastAppChoices.withSelection(
                    choices,
                    SimulcastAppChoices.selected(current.appChoices),
                )
            }
            current.copy(appChoices = marked)
        }
    }

    /**
     * One tile of «Что транслировать» pressed: the stored row changes and the page's marks move.
     *
     * Nothing is read from the package manager. The page already holds what the car offers, so the
     * stored row is kept to what it offers - an entry it no longer lists has left the car - and the
     * marks are the same tiles with their flags moved, not the catalog swept again.
     */
    fun toggleAppSelection(packageName: String) {
        val context = appContext ?: return
        val offered = stateStore.snapshot().state.appChoices
            .mapTo(HashSet(), SimulcastAppChoice::packageName)
        val selected = SimulcastApps.getStored(context).filter { it in offered }.toMutableList()
        if (packageName in selected) {
            selected.remove(packageName)
        } else if (selected.size >= SimulcastApps.MAX_SELECTED) {
            // The picker has already greyed everything a full selection cannot take, so this is
            // the guard behind that and not a place to say anything: the driver did not press a
            // live tile. It used to answer with "Можно выбрать не больше 6" over the grid.
            return
        } else {
            selected.add(packageName)
        }
        SimulcastApps.setSelected(context, selected)
        stateStore.update { current ->
            current.copy(appChoices = SimulcastAppChoices.withSelection(current.appChoices, selected))
        }
        invalidate(StateSlice.SIMULCAST, "simulcast apps")
    }

    fun setMirrorsEnabled(enabled: Boolean) {
        val context = appContext ?: return
        MirrorsSettings.setEnabled(context, enabled)
        if (!enabled) {
            SideCameraMonitorService.stop(context)
            invalidate(StateSlice.MIRRORS, "mirrors switch")
            return
        }
        publisher.publish("mirrors switch") { current ->
            current.copy(mirrors = FeatureReducer.starting(FeatureId.MIRRORS))
        }
        reconcileMirrors()
    }

    fun setMirrorsPosition(position: MirrorsPosition) {
        val context = appContext ?: return
        MirrorsSettings.setPosition(context, position)
        invalidate(StateSlice.MIRRORS, "mirrors settings")
    }

    fun setMirrorsProcessing(enabled: Boolean) {
        val context = appContext ?: return
        MirrorsSettings.setProcessingEnabled(context, enabled)
        invalidate(StateSlice.MIRRORS, "mirrors settings")
    }

    fun previewMirrors() {
        val context = appContext ?: return
        when (ClusterDisplayResolver.resolveCameraOverlay(context)) {
            is ClusterDisplaySelection.Selected -> ClusterSceneService.preview(
                context,
                MirrorsSettings.position(context),
                visible = true,
                durationMs = 2_200L,
            )
            else -> Unit
        }
        if (MirrorsSettings.isEnabled(context)) {
            reconcileMirrors()
        } else {
            invalidate(StateSlice.MIRRORS, "mirrors preview")
        }
    }

    fun performNavigationAction() {
        NavigationCoordinator.performPrimaryAction()
    }

    fun performNavigationActionFromSteeringWheel(context: Context): Boolean {
        if (appContext == null) {
            initialize(context.applicationContext)
        }
        return NavigationCoordinator.performPrimaryAction()
    }

    fun setNavigationSteeringWheelButton(enabled: Boolean) {
        val context = appContext ?: return
        NavigationSettings.setSteeringWheelButtonEnabled(context, enabled)
        if (enabled) {
            reconcileNavigationSteeringWheelAccess(context)
        } else {
            invalidate(StateSlice.NAVIGATION, "wheel switch")
        }
    }

    fun recoverNavigationSteeringWheelAccess(context: Context) {
        val app = appContext ?: context.applicationContext
        if (!NavigationSettings.steeringWheelButtonEnabled(app)) return
        reconcileNavigationSteeringWheelAccess(app)
    }

    fun setNavigationPlacement(placement: ClusterMapPlacement) {
        NavigationCoordinator.selectPlacement(placement)
    }

    /**
     * Read the car for «Что показывать» without opening a window, off the main thread: the panel's
     * own page asks for this when it opens, the way the projection's page does.
     */
    fun refreshNavigationAppChoices() {
        val context = appContext ?: return
        catalogExecutor.execute {
            val choices = NavigationAppChoices.all(context, NavigationCoordinator.selectedPackage())
            stateStore.update { current -> current.copy(navigationAppChoices = choices) }
        }
    }

    /**
     * One answer chosen on «Что показывать»; false when the car no longer offers it, and a window
     * showing the choice stays open over the list it was drawn from.
     */
    fun selectNavigationApp(packageName: String): Boolean {
        val context = appContext ?: return false
        if (!NavigationSettings.isOffered(context, packageName)) return false
        NavigationCoordinator.selectPackage(packageName)
        return true
    }

    fun setSplitScreenEnabled(enabled: Boolean) {
        val context = appContext ?: return
        applySplitScreenToggle(context, enabled)
    }

    /** The toggle's one path: launcher icon, runtime and firmware signals together. */
    private fun applySplitScreenToggle(context: Context, enabled: Boolean): Boolean =
        runCatching {
            SplitScreenToggleController.setEnabled(
                enabled = enabled,
                launcherVisible = { SplitLauncherIconController.isVisible(context) },
                setLauncherVisible = { visible ->
                    SplitLauncherIconController.setVisible(context, visible)
                },
                setRuntimeEnabled = SplitScreenCoordinator::setEnabled,
            )
        }.onSuccess {
            invalidate(StateSlice.SPLIT_SCREEN, "split switch")
        }.onFailure { error ->
            // The exception used to be `error.toString()` in `details`, which is a class name and a
            // stack frame put on the driver's screen. The screen gets the fact - the switch did not
            // take - in the words of the tile it belongs to; the exception goes where exceptions
            // are read.
            Log.w(TAG, "Split screen toggle to $enabled failed", error)
            publisher.publish("split switch") { current ->
                current.copy(
                    splitScreen = FeatureReducer.failed(
                        previous = current.splitScreen.copy(desiredEnabled = enabled),
                        message = FeatureWords.refused(enabled),
                    ),
                )
            }
        }.isSuccess

    fun setHudGuidanceEnabled(enabled: Boolean) {
        val context = appContext ?: return
        HudGuidanceSettings.setEnabled(context, enabled)
        HudGuidanceRider.requestRefresh()
        if (!enabled) {
            invalidate(StateSlice.HUD_GUIDANCE, "hud switch")
            return
        }
        HudNotificationAccess.ensure(context) {
            invalidate(StateSlice.HUD_GUIDANCE, "hud access")
        }
        publisher.publish("hud switch") { current ->
            current.copy(hudGuidance = FeatureReducer.starting(FeatureId.HUD_GUIDANCE))
        }
        if (!isInstalled(context.packageManager, HudGuidanceSettings.NAVIGATOR_PACKAGE)) {
            invalidate(StateSlice.HUD_GUIDANCE, "hud switch")
            return
        }
        if (AccessibilityHealth.read(context).ready()) {
            HudGuidanceRider.requestRefresh()
            invalidate(StateSlice.HUD_GUIDANCE, "hud switch")
            return
        }
        AccessibilityRepair.repair(context) { failure ->
            if (failure == null) {
                HudGuidanceRider.requestRefresh()
                invalidate(StateSlice.HUD_GUIDANCE, "hud switch")
            } else {
                val problem = SimulcastCoordinator.setupProblem(failure)
                val hudGuidance = FeatureReducer.needsAction(
                    FeatureReducer.starting(FeatureId.HUD_GUIDANCE),
                    problem.message,
                    failure.toString(),
                    problem.resolution,
                )
                publisher.publish("hud access") { current -> current.copy(hudGuidance = hudGuidance) }
                serviceReport.rebuildNow()
            }
        }
    }

    /**
     * The switch. On, the app reports playback the car ignores; off, it is silent and the car
     * behaves as stock.
     *
     * Neither position writes anything to the car. The covers are the car's, and so is the stock
     * auto-lift setting - drawn in Settings on the N9, always on and undrawn on the Z9GT. The
     * earlier switch wrote that setting off on its way out, which hid the covers until the next
     * start of the car and made «off» mean two different things depending on when you looked.
     */
    fun setSpeakerCoversEnabled(enabled: Boolean) {
        val context = appContext ?: return
        SpeakerCoverSettings.setEnabled(context, enabled)
        SpeakerCoverService.reconcile(context)
        invalidate(StateSlice.SPEAKER_COVERS, "speakers switch")
    }

    /**
     * The panel's one button, for covers the car has retracted with no music to bring back.
     *
     * It answers whether or not the feature is switched on: the covers belong to the car, and
     * wanting them out at a standstill is a thing to want. There is deliberately no button beside
     * it - the car has no close, and pretending otherwise is what disabled the driver's stock
     * auto-lift for a whole trip every time the old one was pressed.
     */
    fun raiseSpeakerCovers() {
        appContext?.let(SpeakerCoverService::raise)
    }

    /**
     * Hold the car's cloud link over Wi-Fi, or hand it back.
     *
     * On takes the link over - the profile the stock client needs and one «ready», if it is not
     * connected already - and starts the adapter that keeps translating Wi-Fi for it. Off is the
     * only thing that closes the gate and restores the car's own profile. Neither happens at any
     * other time: a switch that has never been on leaves the car's connection as it found it.
     */
    fun setCloudLinkEnabled(enabled: Boolean) {
        val context = appContext ?: return
        if (enabled) CloudLinkController.switchOn(context) else CloudLinkController.switchOff(context)
        invalidate(StateSlice.CLOUD_LINK, "cloud switch")
    }

    /** Keep client Wi-Fi on through sleep; written to the car and read back from it. */
    fun setCloudWifiRetained(retain: Boolean) {
        val context = appContext ?: return
        CloudLinkController.setWifiRetained(context, retain)
    }

    /** Read the cloud link's state from the car for the screen. Changes nothing. */
    fun refreshCloudLink() {
        val context = appContext ?: return
        // The shell is the one thing it needs, and the startup gate owns it until it is trusted.
        if (!adbRuntimeStarted.get()) return
        CloudLinkController.refresh(context)
    }

    /**
     * The screen the instruments are on, for the service panel to say before it offers the choice.
     *
     * The panel used to show a list of unlabelled buttons and one called "Определять
     * автоматически", with nothing saying which was in use or what any of them were for.
     */
    private fun clusterDisplayLabel(
        context: Context,
        candidates: List<ClusterDisplayDescriptor>,
    ): String =
        when (val selection = ClusterDisplayResolver.resolve(context)) {
            is ClusterDisplaySelection.Selected -> {
                val name = clusterDisplayName(selection, candidates).orEmpty()
                if (ClusterDisplayResolver.hasOverride(context)) name else "Определён сам: $name"
            }
            is ClusterDisplaySelection.NeedsVerification -> "Нужно выбрать экран"
            ClusterDisplaySelection.Missing -> "Не найден"
        }

    /**
     * A selected screen named over the same list the picker numbers, so "Экран 2" here is the same
     * screen the picker calls "Экран 2". Platform display ids are neither stable across boots nor
     * written anywhere in the car, so they stay out of the name. Null for no single screen.
     */
    private fun clusterDisplayName(
        selection: ClusterDisplaySelection,
        candidates: List<ClusterDisplayDescriptor>,
    ): String? {
        val display = (selection as? ClusterDisplaySelection.Selected)?.display ?: return null
        val choices = ClusterDisplayResolver.choices(candidates)
        val index = choices.indexOfFirst { it.id == display.id }
        return if (index >= 0) {
            ClusterDisplayResolver.choiceName(index, choices[index])
        } else {
            "${display.width}×${display.height}"
        }
    }

    fun selectClusterDisplay(displayId: Int?) {
        val context = appContext ?: return
        ClusterDisplayResolver.saveOverride(context, displayId)
        NavigationCoordinator.onClusterDisplaySelected()
        if (displayId != null) {
            ClusterSceneService.previewBase(
                context,
                MirrorsSettings.position(context),
                visible = true,
                durationMs = 2_200L,
            )
        }
        invalidate(StateSlice.DISPLAYS, "cluster display")
        if (MirrorsSettings.isEnabled(context)) reconcileMirrors()
    }

    /**
     * What «Сервис» asks for when it opens: DiShare's screens, for the report, and the car's
     * displays, for its «Приборный экран» row and page.
     */
    fun refreshScreenDiagnostics() {
        val context = appContext ?: return
        searchClusterDisplays()
        SimulcastScreenDiagnostics.refresh(context) { serviceReport.rebuildNow() }
    }

    /**
     * Reads the car's displays again, and nothing else: the instruments' picker asks every 1.5 s
     * while it has nothing to offer. It used to recompute the whole dashboard for it.
     */
    fun searchClusterDisplays() {
        invalidate(StateSlice.CLUSTER_DISPLAY, "display search")
    }

    fun checkAdbAccess() {
        val context = appContext ?: return
        AdbRescueCoordinator.checkAccess(context) { onAdbRescueChanged(context) }
    }

    /**
     * A tile's press on a failed channel: the passive check, and [onTrusted] on the main thread if
     * the car still trusts this app. If it does not, the phase leaves TRUSTED and the startup gate
     * comes up over the dashboard - the choice the driver has. A check already running answers for
     * this press too, without it.
     */
    fun checkAdbAccessThen(onTrusted: () -> Unit) {
        val context = appContext ?: return
        val answered = AtomicBoolean(false)
        AdbRescueCoordinator.checkAccess(context) {
            onAdbRescueChanged(context)
            if (AdbRescueCoordinator.snapshot().phase == AdbRescuePhase.TRUSTED &&
                answered.compareAndSet(false, true)
            ) {
                Handler(Looper.getMainLooper()).post(onTrusted)
            }
        }
    }

    fun requestAdbAuthorizationOnce() {
        val context = appContext ?: return
        AdbRescueCoordinator.requestOnce(context) { onAdbRescueChanged(context) }
    }

    fun allowNewAdbAuthorizationAttempt() {
        val context = appContext ?: return
        AdbRescueCoordinator.allowNewAttempt(context) {
            invalidate(StateSlice.ADB_ACCESS, "adb attempt")
            checkAdbAccess()
        }
    }

    /**
     * What language the car is speaking, read straight.
     *
     * No coordinator and no claim: this is [Locale.getDefault], which cannot fail and cannot be
     * refused, read as the [StateSlice.SYSTEM_LANGUAGE] slice on the publisher's thread. The
     * machinery the old per-application override needed - a permission granted over ADB, a running
     * flag, an ABA-safe compare, two shapes of failure - all belonged to writing somebody else's
     * locale, and nothing here writes anything.
     */
    fun refreshSystemLanguage() {
        invalidate(StateSlice.SYSTEM_LANGUAGE, "language")
    }

    /**
     * Split the screen now, through the same door the launcher icon opens.
     *
     * Not a second way of doing it - literally the same entry activity, so the flow a driver gets
     * from the tile is the flow they get from the desktop, and there is one of it to keep working.
     *
     * A press while the toggle is off turns the function on first, by the toggle's own path, and
     * then opens (contract 1.2.8): the tile is a door the desktop icon is not, and an open behind a
     * switch that stayed off used to leave the function half on.
     */
    fun launchSplitScreen() {
        val context = appContext ?: return
        SplitScreenToggleController.launch(
            launcherVisible = runCatching { SplitLauncherIconController.isVisible(context) }
                .getOrDefault(false),
            enable = { applySplitScreenToggle(context, enabled = true) },
            open = {
                runCatching {
                    context.startActivity(
                        Intent(context, SplitLauncherEntryActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
    }

    /**
     * Whether the car is fed weather at all.
     *
     * There is no coordinator behind this and no handshake to wait for: the adapter either has a
     * standing alarm or it does not, so the press is the whole of the operation and the state can
     * be reported the moment it is written.
     */
    fun setWeatherEnabled(enabled: Boolean) {
        val context = appContext ?: return
        WeatherAdapterState.setEnabled(context, enabled)
        if (enabled) WeatherAdapterScheduler.ensureScheduled(context)
        else WeatherAdapterScheduler.cancel(context)
        publisher.publish("weather switch") { current -> current.copy(weatherEnabled = enabled) }
    }

    /**
     * What the car was last handed, copied from the adapter's own record.
     *
     * The forecast is fetched every ten minutes by [WeatherAdapterService], which writes the
     * temperature and the time of the last success as it goes; the tile and the panel read them
     * from here: the [StateSlice.WEATHER] slice, read with every [refresh] and, through
     * [WeatherAdapterState.observe], after every run as it records - until 2026-10-06 only the
     * runtime start read them, so the tile kept the temperature of the moment the process came up.
     */
    fun refreshWeather() {
        invalidate(StateSlice.WEATHER, "weather")
    }

    /**
     * Hand the language over to the car's own list.
     *
     * The whole feature. This app does not set the language, does not mirror what was chosen and
     * does not need to be told afterwards: the car applies it to the system locale, every process
     * is reconfigured, and this one comes back through [refreshSystemLanguage] like any other.
     */
    fun openSystemLanguage() {
        val context = appContext ?: return
        SystemLanguage.open(context)
    }

    private fun initializeAdbGate(context: Context) {
        appContext = context.applicationContext
        AdbRescueCoordinator.initialize(context)
        AdbRestore.initialize(context)
        // The three roles are an ordinary ContentResolver read; they owe the ADB phase nothing.
        defaultApps.refresh()
        when (AdbStartupGatePolicy.entryAction(AdbRescueCoordinator.snapshot().phase)) {
            // A passive look in every unsettled phase: it never submits the key, so an approval
            // that landed while the process was dead is found here rather than never.
            AdbStartupEntryAction.CHECK_ACCESS -> {
                refresh("start")
                checkAdbAccess()
            }
            AdbStartupEntryAction.START_RUNTIME -> startAdbRuntime(context)
            AdbStartupEntryAction.NONE -> refresh("start")
        }
    }

    private fun onAdbRescueChanged(context: Context) {
        if (AdbRescueCoordinator.snapshot().phase == AdbRescuePhase.TRUSTED) {
            AdbRestore.recordTrusted(context)
            startAdbRuntime(context)
        } else if (AdbRescueCoordinator.snapshot().phase in listOf(AdbRescuePhase.UNAVAILABLE, AdbRescuePhase.ERROR,
                AdbRescuePhase.AUTHORIZATION_REQUIRED)) {
            AdbRestore.trigger("recovery")
        }
        runtimeStep("ADB state refresh") { invalidate(StateSlice.ADB_ACCESS, "adb access") }
    }

    private fun startAdbRuntime(context: Context) {
        if (!adbRuntimePassRunning.compareAndSet(false, true)) return
        val app = context.applicationContext
        adbRuntimeStarted.set(true)
        try {
            // The sources the slices read that nothing in this app writes, each its own step:
            // one that cannot be registered leaves the others watched.
            runtimeStep("display watch") { watchDisplays(app) }
            runtimeStep("overlay grant watch") { watchOverlayGrant(app) }
            runtimeStep("split initialize") {
                // A mark, not a read: it is made on the split's actor, and on a tap on the main
                // thread before the waiting window draws.
                SplitScreenCoordinator.initialize(app) {
                    invalidate(StateSlice.SPLIT_SCREEN, "split")
                }
            }
            runtimeStep("split reconcile") { reconcileSplitScreenToggle(app) }
            runtimeStep("navigation initialize") {
                NavigationCoordinator.initialize(app) {
                    invalidate(StateSlice.NAVIGATION, "navigation")
                }
            }
            runtimeStep("weather initialize") {
                WeatherAdapterScheduler.ensureScheduled(app)
                WeatherAdapterState.observe(app) { refreshWeather() }
                refreshWeather()
            }
            runtimeStep("dashboard refresh") { refresh("runtime start") }
            runtimeStep("default apps refresh") { defaultApps.refresh() }
            runtimeStep("steering wheel reconcile") {
                reconcileNavigationSteeringWheelAccess(app)
            }
            runtimeStep("simulcast reconcile") {
                reconcileSimulcast(repairMissingSetup = true)
            }
            runtimeStep("media button access") {
                MediaSessionAccess.ensure(app) {
                    MediaKeyRider.requestRefresh()
                }
            }
            runtimeStep("mirrors reconcile") {
                if (MirrorsSettings.isEnabled(app)) reconcileMirrors()
            }
            runtimeStep("HUD reconcile") { reconcileHudNotificationAccess(app) }
            runtimeStep("speaker covers reconcile") { SpeakerCoverService.reconcile(app) }
            runtimeStep("cloud link reconcile") {
                CloudLinkService.reconcile(app)
                CloudLinkController.refresh(app)
            }
            // Off this thread, and it records a failure rather than raising it: the pass never
            // waits for it or fails because of it.
            runtimeStep("adb port restore prepare") {
                AdbPortRestore.prepare(app) { serviceReport.rebuildNow() }
                AdbRestore.trigger("watchdog")
            }
        } finally {
            adbRuntimePassRunning.set(false)
        }
    }

    /**
     * The car's displays coming and going: where the mirrors draw and the instruments' screen
     * choice ([StateSlice.DISPLAYS]). Once for the process, and counted as watched only once the
     * listener is in, so a pass that failed to register it tries again.
     */
    private fun watchDisplays(context: Context) {
        if (displaysWatched.get()) return
        val displays = context.getSystemService(DisplayManager::class.java) ?: return
        displays.registerDisplayListener(
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) =
                    invalidate(StateSlice.DISPLAYS, "display added")

                override fun onDisplayRemoved(displayId: Int) =
                    invalidate(StateSlice.DISPLAYS, "display removed")

                override fun onDisplayChanged(displayId: Int) = Unit
            },
            Handler(Looper.getMainLooper()),
        )
        displaysWatched.set(true)
    }

    /**
     * This app's own overlay grant, which the projection reads: given by its features over ADB,
     * taken away in Settings. Watching our own package's op needs no permission.
     */
    private fun watchOverlayGrant(context: Context) {
        if (overlayGrantWatched.get()) return
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return
        val ownPackage = context.packageName
        appOps.startWatchingMode(
            AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
            ownPackage,
            object : AppOpsManager.OnOpChangedListener {
                override fun onOpChanged(op: String?, packageName: String?) {
                    if (packageName == ownPackage) {
                        invalidate(StateSlice.SIMULCAST, "overlay changed")
                    }
                }
            },
        )
        overlayGrantWatched.set(true)
    }

    private inline fun runtimeStep(name: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            Log.w(TAG, "runtime startup step failed: $name", error)
        }
    }

    private fun reconcileMirrors() {
        val context = appContext ?: return
        if (!MirrorsSettings.isEnabled(context)) {
            invalidate(StateSlice.MIRRORS, "mirrors")
            return
        }
        when (val selection = ClusterDisplayResolver.resolveCameraOverlay(context)) {
            is ClusterDisplaySelection.Selected -> {
                SideCameraMonitorService.start(context)
                invalidate(StateSlice.MIRRORS, "mirrors")
            }
            else -> {
                val mirrors = MirrorDisplayReadiness.snapshot(selection, active = false)
                publisher.publish("mirrors") { current -> current.copy(mirrors = mirrors) }
                serviceReport.rebuildNow()
            }
        }
    }

    private fun reconcileHudNotificationAccess(context: Context) {
        if (!HudGuidanceSettings.isEnabled(context)) return
        HudNotificationAccess.ensure(context) {
            invalidate(StateSlice.HUD_GUIDANCE, "hud access")
        }
    }

    private fun reconcileNavigationSteeringWheelAccess(context: Context) {
        SteeringWheelNavigationAccessCoordinator.reconcile(context) {
            invalidate(StateSlice.NAVIGATION, "wheel access")
        }
        invalidate(StateSlice.NAVIGATION, "wheel access")
    }

    private fun reconcileSimulcast(
        repairMissingSetup: Boolean,
        forceRepair: Boolean = false,
    ) {
        val context = appContext ?: return
        SimulcastCoordinator.reconcile(
            context = context,
            repairMissingSetup = repairMissingSetup,
            forceRepair = forceRepair,
        ) { event ->
            when (event) {
                SimulcastReconcileEvent.Refresh -> {
                    publisher.publish("simulcast") { current ->
                        current.copy(setupRunning = event.setupRunning)
                    }
                    invalidate(StateSlice.SIMULCAST, "simulcast")
                }
                is SimulcastReconcileEvent.Blocked -> {
                    val simulcast = SimulcastCoordinator.blockedSnapshot(event.blocker)
                    publisher.publish("simulcast") { current ->
                        current.copy(
                            setupRunning = event.setupRunning,
                            simulcast = simulcast,
                            selectedAppCount = event.selectedAppCount,
                        )
                    }
                    serviceReport.rebuildNow()
                }
                SimulcastReconcileEvent.Repairing -> {
                    val simulcast = FeatureReducer.recovering(
                        FeatureReducer.starting(FeatureId.SIMULCAST),
                        "Восстанавливаю доступ",
                    )
                    publisher.publish("simulcast") { current ->
                        current.copy(
                            setupRunning = event.setupRunning,
                            simulcast = simulcast,
                        )
                    }
                }
                SimulcastReconcileEvent.Repaired -> {
                    publisher.publish("simulcast") { current ->
                        current.copy(setupRunning = event.setupRunning)
                    }
                    invalidate(StateSlice.SIMULCAST, "simulcast")
                }
                is SimulcastReconcileEvent.RepairFailed -> {
                    val simulcast = FeatureReducer.needsAction(
                        FeatureReducer.starting(FeatureId.SIMULCAST),
                        event.message,
                        event.details,
                        event.resolution,
                    )
                    publisher.publish("simulcast") { current ->
                        current.copy(
                            setupRunning = event.setupRunning,
                            simulcast = simulcast,
                        )
                    }
                    serviceReport.rebuildNow()
                }
            }
        }
    }

    private fun evaluateMirrors(context: Context): FeatureSnapshot {
        if (!MirrorsSettings.isEnabled(context)) return FeatureReducer.disabled(FeatureId.MIRRORS)
        return MirrorDisplayReadiness.snapshot(
            selection = ClusterDisplayResolver.resolveCameraOverlay(context),
            active = MirrorsSettings.observedSide(context) != null,
        )
    }

    private fun evaluateHudGuidance(context: Context): FeatureSnapshot {
        val enabled = HudGuidanceSettings.isEnabled(context)
        return HudGuidanceStatus.snapshot(
            enabled = enabled,
            navigatorInstalled = enabled &&
                isInstalled(context.packageManager, HudGuidanceSettings.NAVIGATOR_PACKAGE),
            accessibilityEnabled = enabled && AccessibilityHealth.read(context).enabled,
            accessibilityConnected = AccessibilityHost.isConnected(),
            active = HudGuidanceRuntime.isActive(),
            details = HudGuidanceRuntime::details,
        )
    }

    private fun splitScreenSnapshot(
        launcherVisible: Boolean,
        session: SplitScreenSession,
    ): FeatureSnapshot {
        val status = when (session.phase) {
            SplitScreenPhase.OFF -> FeatureStatus.OFF
            SplitScreenPhase.STARTING -> FeatureStatus.STARTING
            SplitScreenPhase.ACTIVE -> FeatureStatus.ACTIVE
        }
        // U5: the card never reports a failure of the product. It says what the feature is doing
        // right now, and the switch says whether its icon is on the launcher. «Иконка Split Screen
        // доступна / скрыта» stood in for an empty message here, was never drawn, and was English.
        return FeatureSnapshot(
            id = FeatureId.SPLIT_SCREEN,
            desiredEnabled = launcherVisible,
            status = status,
            message = session.message,
        )
    }

    /**
     * The launcher icon is the persisted user-facing toggle. Older builds changed only the
     * component state, so repair that one-time mismatch before recovering the split runtime.
     */
    private fun reconcileSplitScreenToggle(context: Context) {
        val launcherVisible = SplitLauncherIconController.isVisible(context)
        SplitScreenToggleController.reconcile(
            launcherVisible = launcherVisible,
            runtimeEnabled = SplitScreenSettings.isEnabled(context),
            setRuntimeEnabled = SplitScreenCoordinator::setEnabled,
        )
    }

    private fun supportDiagnostics(context: Context): String =
        SupportDiagnostics.build(context, stateStore.snapshot().state.fseInstaller)

    /** The panel row's applications: names read here, pictures left in [AppIcons] for the row. */
    private fun selectedAppChoices(context: Context): List<SimulcastAppChoice> =
        SimulcastApps.getSelected(context).map { packageName ->
            val info = runCatching {
                context.packageManager.getApplicationInfo(packageName, 0)
            }.getOrNull()
            if (info != null) AppIcons.load(context, packageName)
            SimulcastAppChoice(
                packageName = packageName,
                label = info?.let { context.packageManager.getApplicationLabel(it).toString() }
                    ?: packageName,
                selected = true,
            )
        }

    private fun isInstalled(packageManager: PackageManager, packageName: String): Boolean = try {
        packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
