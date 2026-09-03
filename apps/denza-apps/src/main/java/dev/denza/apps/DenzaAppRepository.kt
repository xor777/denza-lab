package dev.denza.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.Log
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.feature.cluster.ClusterDisplayResolver
import dev.denza.apps.feature.cluster.ClusterDisplayDescriptor
import dev.denza.apps.feature.cluster.ClusterDisplaySelection
import dev.denza.apps.feature.cluster.ClusterMapPlacement
import dev.denza.apps.feature.cluster.ClusterSceneService
import dev.denza.apps.feature.adb.AdbAutostartRetryAction
import dev.denza.apps.feature.adb.AdbAutostartRetryPolicy
import dev.denza.apps.feature.adb.AdbRescueCoordinator
import dev.denza.apps.feature.adb.AdbRescuePhase
import dev.denza.apps.feature.adb.AdbRescueSnapshot
import dev.denza.apps.feature.adb.AdbStartupEntryAction
import dev.denza.apps.feature.adb.AdbStartupGatePolicy
import dev.denza.apps.feature.defaultapps.DefaultAppChoice
import dev.denza.apps.feature.defaultapps.DefaultAppRole
import dev.denza.apps.feature.defaultapps.DefaultAppRoleRepository
import dev.denza.apps.feature.defaultapps.DefaultAppRoleStatus
import dev.denza.apps.feature.defaultapps.DefaultAppRoleUiState
import dev.denza.apps.feature.defaultapps.DefaultAppsCatalog
import dev.denza.apps.feature.defaultapps.DefaultAppsPolicy
import dev.denza.apps.feature.defaultapps.DefaultAppsSettings
import dev.denza.apps.feature.defaultapps.DefaultAppsUiState
import dev.denza.apps.feature.defaultapps.InstalledDefaultApp
import dev.denza.apps.feature.fse.FseAppInstaller
import dev.denza.apps.feature.fse.FseInstallApp
import dev.denza.apps.feature.fse.FseInstallResult
import dev.denza.apps.feature.hud.HudGuidanceRuntime
import dev.denza.apps.feature.hud.HudGuidanceSettings
import dev.denza.apps.feature.hud.HudNotificationAccessCoordinator
import dev.denza.apps.feature.locale.StockRussianLocaleChange
import dev.denza.apps.feature.locale.StockRussianLocaleCoordinator
import dev.denza.apps.feature.locale.StockRussianLocaleSnapshot
import dev.denza.apps.feature.locale.StockRussianLocaleStatus
import dev.denza.apps.feature.mirrors.MirrorDisplayReadiness
import dev.denza.apps.feature.mirrors.MirrorsPosition
import dev.denza.apps.feature.mirrors.MirrorsSettings
import dev.denza.apps.feature.mirrors.SideCameraMonitorService
import dev.denza.apps.feature.navigation.NavigationCoordinator
import dev.denza.apps.feature.navigation.NavigationAppPolicy
import dev.denza.apps.feature.navigation.NavigationPhase
import dev.denza.apps.feature.navigation.NavigationPlacementPolicy
import dev.denza.apps.feature.navigation.NavigationSettings
import dev.denza.apps.feature.navigation.SteeringWheelNavigationAccessCoordinator
import dev.denza.apps.feature.split.SplitLauncherIconController
import dev.denza.apps.feature.split.SplitScreenCoordinator
import dev.denza.apps.feature.split.SplitScreenPhase
import dev.denza.apps.feature.split.SplitScreenSession
import dev.denza.apps.feature.split.SplitScreenSettings
import dev.denza.apps.feature.split.SplitScreenToggleController
import dev.denza.apps.feature.speaker.SpeakerCoverRuntime
import dev.denza.apps.feature.speaker.SpeakerCoverService
import dev.denza.apps.feature.speaker.SpeakerCoverSettings
import dev.denza.apps.feature.split.SplitLauncherEntryActivity
import dev.denza.apps.feature.weather.WeatherAdapterScheduler
import dev.denza.apps.feature.weather.WeatherAdapterState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class SimulcastAppChoice(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
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

data class NavigationAppChoice(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val selected: Boolean,
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
     * A cover command in flight, which the snapshot above cannot say while the automation is off.
     *
     * The «Поднять» / «Опустить» buttons answer with the toggle either way, so their in-progress
     * state has to be read from the runtime rather than from the feature's own status.
     */
    val speakerCoversCommanding: Boolean = false,
    val fseInstaller: FeatureSnapshot = FeatureSnapshot(
        id = FeatureId.FSE_INSTALLER,
        desiredEnabled = false,
        status = FeatureStatus.READY,
    ),
    val navigationButtonLabel: String = "Открыть",
    val navigationSteeringWheelButton: Boolean = false,
    val navigationSteeringWheelButtonReady: Boolean = false,
    val navigationSteeringWheelButtonRepairing: Boolean = false,
    val navigationPlacement: ClusterMapPlacement = ClusterMapPlacement.FULL,
    /** Placements the current choice actually has; a single entry means there is nothing to pick. */
    val navigationPlacements: List<ClusterMapPlacement> = ClusterMapPlacement.entries,
    val navigationAppLabel: String = "Яндекс Навигатор",
    val navigationAppChoices: List<NavigationAppChoice> = emptyList(),
    val navigationPickerVisible: Boolean = false,
    val selectedAppCount: Int = 0,
    val selectedAppLabels: List<String> = emptyList(),
    val selectedApps: List<SimulcastAppChoice> = emptyList(),
    val mirrorsPosition: MirrorsPosition = MirrorsPosition.SIDES,
    val mirrorsProcessing: Boolean = true,
    val setupRunning: Boolean = false,
    val adbRescue: AdbRescueSnapshot = AdbRescueSnapshot(),
    val defaultApps: DefaultAppsUiState = DefaultAppsUiState(),
    val stockRussianLocale: StockRussianLocaleSnapshot = StockRussianLocaleSnapshot(),
    val weatherEnabled: Boolean = true,
    val weatherTemperature: Int? = null,
    val weatherUpdatedMillis: Long = 0L,
    val technicalDetails: String = "",
    val clusterCandidates: List<ClusterDisplayDescriptor> = emptyList(),
    /** Which screen the instruments are going to, said the way the service panel says it. */
    val clusterDisplayLabel: String = "Определяется автоматически",
    val appPickerVisible: Boolean = false,
    val appChoices: List<SimulcastAppChoice> = emptyList(),
    val fseInstallerPickerVisible: Boolean = false,
    val fseInstallApps: List<FseInstallApp> = emptyList(),
)

/** Android-facing state owner shared by the Compose shell and runtime services. */
object DenzaAppRepository {
    private const val TAG = "DenzaApps.Repository"
    private val executor = Executors.newSingleThreadExecutor()
    private val localeExecutor = Executors.newSingleThreadExecutor()
    private val defaultAppsExecutor = Executors.newSingleThreadExecutor()
    private val adbRuntimeStarted = AtomicBoolean(false)
    private val adbRuntimePassRunning = AtomicBoolean(false)
    private val defaultAppsRefreshRequested = AtomicBoolean(false)
    private val defaultAppsRefreshRunning = AtomicBoolean(false)
    private val stateStore = DenzaUiStateStore()
    val state: StateFlow<DenzaUiState> = stateStore.state

    private val defaultAppsRepositoryLock = Any()

    @Volatile
    private var defaultAppsRepository: DefaultAppRoleRepository? = null

    /** The last installed-launcher sweep, kept so storing a choice does not repeat it. */
    @Volatile
    private var defaultAppsCatalog: List<InstalledDefaultApp>? = null

    /** `elapsedRealtime` of the last refresh that ended with all three roles read. */
    @Volatile
    private var defaultAppsReadAtElapsed = 0L

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        initializeAdbGate(context.applicationContext)
    }

    fun recoverEnabledFeatures(context: Context) {
        initializeAdbGate(context.applicationContext)
    }

    fun recoverAutostart(context: Context) {
        val app = context.applicationContext
        appContext = app
        AdbRescueCoordinator.initialize(app)
        when (AdbAutostartRetryPolicy.action(AdbRescueCoordinator.snapshot().phase)) {
            AdbAutostartRetryAction.CHECK_ACCESS -> {
                refresh()
                checkAdbAccess()
            }
            AdbAutostartRetryAction.START_RUNTIME -> startAdbRuntime(app)
            AdbAutostartRetryAction.NONE -> refresh()
        }
    }

    fun refresh() {
        val context = appContext ?: return
        val adbRescue = AdbRescueCoordinator.snapshot()
        if (adbRescue.phase != AdbRescuePhase.TRUSTED || !adbRuntimeStarted.get()) {
            // Keep the last healthy dashboard (or its neutral first-launch defaults) behind the
            // startup overlay. Individual feature probes must not turn a missing global ADB
            // prerequisite into a wall of unrelated errors.
            stateStore.update { current -> current.copy(adbRescue = adbRescue) }
            return
        }
        val snapshot = SimulcastCoordinator.evaluate(SimulcastCoordinator.inspect(context))
        val navigationSession = NavigationCoordinator.snapshot()
        val navigationPackage = NavigationCoordinator.selectedPackage()
        val steeringWheelAccess = SteeringWheelNavigationAccessCoordinator.inspect(context)
        val splitLauncherVisible = SplitLauncherIconController.isVisible(context)
        val splitScreenSession = SplitScreenCoordinator.snapshot()
        val selectedApps = selectedAppChoices(context)
        val mirrors = evaluateMirrors(context)
        val selectedAppCount = SimulcastApps.selectedCount(context)
        val mirrorsPosition = MirrorsSettings.position(context)
        val mirrorsProcessing = MirrorsSettings.processingEnabled(context)
        val navigationSteeringWheelButtonRepairing =
            steeringWheelAccess.desired && SteeringWheelNavigationAccessCoordinator.isRepairing()
        val navigationPlacement = NavigationPlacementPolicy.resolve(
            navigationPackage,
            NavigationCoordinator.placement(),
        )
        val navigationPlacements = NavigationPlacementPolicy.offered(navigationPackage)
        val navigationAppLabel = NavigationAppPolicy.fallbackLabel(navigationPackage)
        val navigationAppChoices = navigationAppChoices(context, navigationPackage)
        val appChoices = loadAppChoices(context)
        val splitScreen = splitScreenSnapshot(
            launcherVisible = splitLauncherVisible,
            session = splitScreenSession,
        )
        val hudGuidance = evaluateHudGuidance(context)
        val speakerCovers = SpeakerCoverRuntime.featureSnapshot(context)
        val speakerCoversCommanding = SpeakerCoverRuntime.commanding()
        val technicalDetails = supportDiagnostics(context)
        val clusterCandidates = ClusterDisplayResolver.candidates(context)
        val clusterDisplayLabel = clusterDisplayLabel(context, clusterCandidates)
        stateStore.update { current ->
            current.copy(
                simulcast = snapshot,
                mirrors = mirrors,
                selectedAppCount = selectedAppCount,
                selectedAppLabels = selectedApps.map(SimulcastAppChoice::label),
                selectedApps = selectedApps,
                mirrorsPosition = mirrorsPosition,
                mirrorsProcessing = mirrorsProcessing,
                navigation = navigationSnapshot(
                    navigationSession.phase,
                    navigationSession.message,
                    navigationSession.details,
                    navigationSession.resolution,
                ),
                navigationButtonLabel = navigationSession.buttonLabel,
                navigationSteeringWheelButton = steeringWheelAccess.desired,
                navigationSteeringWheelButtonReady = steeringWheelAccess.ready,
                navigationSteeringWheelButtonRepairing = navigationSteeringWheelButtonRepairing,
                navigationPlacement = navigationPlacement,
                navigationPlacements = navigationPlacements,
                navigationAppLabel = navigationAppLabel,
                navigationAppChoices = navigationAppChoices,
                // Loaded here rather than when a picker asks for it. The projection panel offers
                // this list inline, and a panel that says "which applications" over an empty space
                // until some other flow happens to have run is a panel that lies about what it is
                // for.
                appChoices = appChoices,
                splitScreen = splitScreen,
                hudGuidance = hudGuidance,
                speakerCovers = speakerCovers,
                speakerCoversCommanding = speakerCoversCommanding,
                adbRescue = adbRescue,
                technicalDetails = technicalDetails,
                clusterCandidates = clusterCandidates,
                clusterDisplayLabel = clusterDisplayLabel,
            )
        }
        // The stock locale is a tile on the main screen now, so it has to be read like every other
        // tile's state. It used to be probed only when the diagnostics dialog opened, which was
        // fine while it lived behind seven taps and is not fine on a tile that would otherwise sit
        // there saying "не проверено" until somebody happened to open service.
        refreshStockRussianLocale()
    }

    fun setSimulcastEnabled(enabled: Boolean) {
        val context = appContext ?: return
        SimulcastIntegration.setEnabled(context, enabled)
        if (!enabled) {
            SimulcastIntegration.clearLastTargetPackage(context)
            SimulcastOverlayService.stopCurrent(context)
            refresh()
            return
        }
        stateStore.update { current ->
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
            stateStore.update { current -> current.copy(simulcast = blocked) }
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
    }

    fun showAppPicker() {
        val context = appContext ?: return
        val appChoices = loadAppChoices(context)
        stateStore.update { current ->
            current.copy(
                appPickerVisible = true,
                appChoices = appChoices,
            )
        }
    }

    fun hideAppPicker() {
        stateStore.update { current -> current.copy(appPickerVisible = false) }
    }

    fun showFseInstallerPicker() {
        val context = appContext ?: return
        val installedApps = FseAppInstaller.installedApps(context)
        stateStore.update { current ->
            current.copy(
                fseInstallerPickerVisible = true,
                fseInstallApps = installedApps,
            )
        }
    }

    fun hideFseInstallerPicker() {
        stateStore.update { current -> current.copy(fseInstallerPickerVisible = false) }
    }

    fun installOnPassengerScreen(packageName: String) {
        val context = appContext ?: return
        when (claimFseInstall(packageName)) {
            FseInstallClaim.BUSY -> return
            // The list the picker was drawn from no longer matches the car. Re-read it and leave
            // the picker standing: a working chooser is the answer, not a note about the old one.
            FseInstallClaim.STALE -> {
                showFseInstallerPicker()
                return
            }
            FseInstallClaim.START -> Unit
        }
        executor.execute {
            val result = FseAppInstaller.install(context, packageName) { message ->
                val progress = FeatureSnapshot(
                    id = FeatureId.FSE_INSTALLER,
                    desiredEnabled = false,
                    status = FeatureStatus.STARTING,
                    message = message,
                )
                stateStore.update { current -> current.copy(fseInstaller = progress) }
            }
            val completed = when (result) {
                is FseInstallResult.Installed -> FeatureSnapshot(
                    id = FeatureId.FSE_INSTALLER,
                    desiredEnabled = false,
                    status = FeatureStatus.READY,
                    message = result.app.label,
                )
                is FseInstallResult.Failed -> FeatureSnapshot(
                    id = FeatureId.FSE_INSTALLER,
                    desiredEnabled = false,
                    status = FeatureStatus.ERROR,
                    message = result.message,
                    details = result.details,
                )
            }
            stateStore.update { current -> current.copy(fseInstaller = completed) }
        }
    }

    fun toggleAppSelection(packageName: String) {
        val context = appContext ?: return
        val selected = SimulcastApps.getSelected(context).toMutableList()
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
        refresh()
        val appChoices = loadAppChoices(context)
        stateStore.update { current ->
            current.copy(appChoices = appChoices)
        }
    }

    fun setMirrorsEnabled(enabled: Boolean) {
        val context = appContext ?: return
        MirrorsSettings.setEnabled(context, enabled)
        if (!enabled) {
            SideCameraMonitorService.stop(context)
            refresh()
            return
        }
        stateStore.update { current ->
            current.copy(mirrors = FeatureReducer.starting(FeatureId.MIRRORS))
        }
        reconcileMirrors()
    }

    fun setMirrorsPosition(position: MirrorsPosition) {
        val context = appContext ?: return
        MirrorsSettings.setPosition(context, position)
        refresh()
    }

    fun setMirrorsProcessing(enabled: Boolean) {
        val context = appContext ?: return
        MirrorsSettings.setProcessingEnabled(context, enabled)
        refresh()
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
        if (MirrorsSettings.isEnabled(context)) reconcileMirrors() else refresh()
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
            refresh()
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

    fun showNavigationAppPicker() {
        val context = appContext ?: return
        val selected = NavigationCoordinator.selectedPackage()
        val choices = navigationAppChoices(context, selected)
        stateStore.update { current ->
            current.copy(
                navigationAppChoices = choices,
                navigationPickerVisible = true,
            )
        }
    }

    fun hideNavigationAppPicker() {
        stateStore.update { current -> current.copy(navigationPickerVisible = false) }
    }

    fun selectNavigationApp(packageName: String) {
        val context = appContext ?: return
        if (!NavigationAppPolicy.isAllowed(packageName)) return
        if (!NavigationSettings.isInstalled(context, packageName)) return
        stateStore.update { current -> current.copy(navigationPickerVisible = false) }
        NavigationCoordinator.selectPackage(packageName)
    }

    fun setSplitScreenEnabled(enabled: Boolean) {
        val context = appContext ?: return
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
            refresh()
        }.onFailure { error ->
            // The exception used to be `error.toString()` in `details`, which is a class name and a
            // stack frame put on the driver's screen. The screen gets the fact - the switch did not
            // take - in the words of the tile it belongs to; the exception goes where exceptions
            // are read.
            Log.w(TAG, "Split screen toggle to $enabled failed", error)
            stateStore.update { current ->
                current.copy(
                    splitScreen = FeatureReducer.failed(
                        previous = current.splitScreen.copy(desiredEnabled = enabled),
                        message = if (enabled) "Не включилось" else "Не выключилось",
                    ),
                )
            }
        }
    }

    fun setHudGuidanceEnabled(enabled: Boolean) {
        val context = appContext ?: return
        HudGuidanceSettings.setEnabled(context, enabled)
        SimulcastAccessibilityService.requestHudGuidanceRefresh()
        if (!enabled) {
            refresh()
            return
        }
        HudNotificationAccessCoordinator.ensureAccess(context) { refresh() }
        stateStore.update { current ->
            current.copy(hudGuidance = FeatureReducer.starting(FeatureId.HUD_GUIDANCE))
        }
        if (!isInstalled(context.packageManager, NavigationAppPolicy.DEFAULT_PACKAGE)) {
            refresh()
            return
        }
        if (
            SimulcastCoordinator.isAccessibilityEnabled(context) &&
            SimulcastAccessibilityService.isConnected()
        ) {
            SimulcastAccessibilityService.requestHudGuidanceRefresh()
            refresh()
            return
        }
        SimulcastCoordinator.repairAccess(context) { failure ->
            if (failure == null) {
                SimulcastAccessibilityService.requestHudGuidanceRefresh()
                refresh()
            } else {
                val problem = SimulcastCoordinator.setupProblem(failure)
                val hudGuidance = FeatureReducer.needsAction(
                    FeatureReducer.starting(FeatureId.HUD_GUIDANCE),
                    problem.message,
                    failure.toString(),
                    problem.resolution,
                )
                val technicalDetails = supportDiagnostics(context)
                stateStore.update { current ->
                    current.copy(
                        hudGuidance = hudGuidance,
                        technicalDetails = technicalDetails,
                    )
                }
            }
        }
    }

    fun setSpeakerCoversEnabled(enabled: Boolean) {
        val context = appContext ?: return
        SpeakerCoverSettings.setEnabled(context, enabled)
        if (enabled) {
            // Switching it on outranks anything the driver pressed earlier in this trip: the two
            // flags that keep the automation quiet are cleared before the service reads them, so a
            // toggle flipped while music is playing opens the covers there and then.
            SpeakerCoverSettings.rearm(context)
            stateStore.update { current ->
                current.copy(speakerCovers = FeatureReducer.starting(FeatureId.SPEAKER_COVERS))
            }
            SpeakerCoverService.reconcile(context)
        } else {
            // A close command suppresses the amplifier's stock auto-lift for this ignition
            // cycle, so switching our automation off tries to leave the covers physically open -
            // best effort only, and never over a close the driver asked for by hand.
            SpeakerCoverService.disableAndOpen(context)
            refresh()
        }
    }

    /**
     * The panel's two buttons.
     *
     * They answer whether or not the automation is switched on: the covers belong to the car, and
     * "поднять" is a thing to want at a standstill with nothing playing. With the automation on,
     * the command goes through it so its idea of where the covers are stays true.
     */
    fun raiseSpeakerCovers() {
        appContext?.let(SpeakerCoverService::raise)
    }

    fun lowerSpeakerCovers() {
        appContext?.let(SpeakerCoverService::lower)
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
                // Named over the same list the picker numbers, so "Экран 2" here is the same
                // screen the picker calls "Экран 2". Platform display ids are neither stable
                // across boots nor written anywhere in the car, so they stay out of the label.
                val choices = ClusterDisplayResolver.choices(candidates)
                val index = choices.indexOfFirst { it.id == selection.display.id }
                val name = if (index >= 0) {
                    ClusterDisplayResolver.choiceName(index, choices[index])
                } else {
                    "${selection.display.width}×${selection.display.height}"
                }
                if (ClusterDisplayResolver.hasOverride(context)) name else "Определён сам: $name"
            }
            is ClusterDisplaySelection.NeedsVerification -> "Нужно выбрать экран"
            ClusterDisplaySelection.Missing -> "Не найден"
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
        refresh()
        if (MirrorsSettings.isEnabled(context)) reconcileMirrors()
    }

    fun refreshScreenDiagnostics() {
        val context = appContext ?: return
        SimulcastScreenDiagnostics.refresh(context) { refresh() }
    }

    fun checkAdbAccess() {
        val context = appContext ?: return
        AdbRescueCoordinator.checkAccess(context) { onAdbRescueChanged(context) }
    }

    fun requestAdbAuthorizationOnce() {
        val context = appContext ?: return
        AdbRescueCoordinator.requestOnce(context) { onAdbRescueChanged(context) }
    }

    fun allowNewAdbAuthorizationAttempt() {
        val context = appContext ?: return
        AdbRescueCoordinator.allowNewAttempt(context) {
            refresh()
            checkAdbAccess()
        }
    }

    /**
     * Explicit, coalesced provider refresh used on Activity resume and by the settings sheet.
     *
     * A read that has just succeeded is not repeated. Resuming the Activity and then opening the
     * tile are two gestures a second apart, and each used to send its own three provider queries
     * and put all three roles back into "Читаю настройку…" - which is the whole of what the driver
     * saw the tile doing. [force] is the "Обновить список" button, which always asks the car.
     */
    fun refreshDefaultApps(force: Boolean = false) {
        val context = appContext ?: return
        if (!force && defaultAppsReadIsFresh()) return
        if (!defaultAppsRuntimeReady()) {
            val phase = AdbRescueCoordinator.snapshot().phase
            if (
                phase == AdbRescuePhase.UNKNOWN ||
                phase == AdbRescuePhase.CHECKING ||
                phase == AdbRescuePhase.TRUSTED
            ) {
                publishDefaultAppsWaitingForRuntime()
            } else {
                publishDefaultAppsUnavailable("Нужен доверенный локальный ADB")
            }
            return
        }

        stateStore.update { current ->
            current.copy(
                defaultApps = current.defaultApps.copy(
                    refreshing = true,
                    roles = current.defaultApps.roles.map { roleState ->
                        if (roleState.status == DefaultAppRoleStatus.APPLYING) {
                            roleState
                        } else {
                            roleState.copy(
                                status = DefaultAppRoleStatus.LOADING,
                                message = "Читаю настройку…",
                            )
                        }
                    },
                ),
            )
        }

        defaultAppsRefreshRequested.set(true)
        scheduleDefaultAppsRefresh(context)
    }

    /**
     * The tile's switch: run the driver's applications, or hand every role back to the car.
     *
     * Off writes the car's own application into all three roles - including one that was chosen in
     * the stock settings rather than here. The narrower rule the cold start follows is right for a
     * read nobody asked for and wrong for this: the driver has just asked for the stock
     * applications, and a press that quietly left one of the three alone would be a press with
     * nothing to show for it. What each role was holding is already remembered, so on puts it back.
     */
    fun setDefaultAppsEnabled(enabled: Boolean) {
        val context = appContext ?: return
        if (!defaultAppsRuntimeReady()) {
            publishDefaultAppsUnavailable("Нужен доверенный локальный ADB")
            return
        }
        val targets = claimDefaultAppsSwitch(context, enabled) ?: return
        defaultAppsExecutor.execute {
            applyDefaultAppTargets(context, targets)
        }
    }

    /** Writes one role. Package admission is the current installed-launcher catalog, not a list. */
    fun selectDefaultApp(
        role: DefaultAppRole,
        packageName: String,
    ) {
        val context = appContext ?: return
        if (!defaultAppsRuntimeReady()) {
            publishDefaultAppRoleError(role, "Нужен доверенный локальный ADB")
            return
        }
        if (!claimDefaultAppSelection(role, packageName)) return

        defaultAppsExecutor.execute {
            applyDefaultAppSelection(context, role, packageName)
        }
    }

    fun refreshStockRussianLocale() {
        val context = appContext ?: return
        val expected = stateStore.snapshot().state.stockRussianLocale
        if (expected.running) return
        val result = runCatching { StockRussianLocaleCoordinator.inspect(context) }
        val permissionReadyOnFailure = result.exceptionOrNull()?.let {
            StockRussianLocaleCoordinator.hasPermission(context)
        }
        stateStore.updateIf(
            // Unrelated state copies keep this exact slice instance. A locale operation replaces
            // it, even when it eventually returns to structurally identical values (ABA).
            predicate = { current -> current.stockRussianLocale === expected },
            transform = { current ->
                current.copy(
                    stockRussianLocale = result.fold(
                        onSuccess = { status -> localeSnapshot(status) },
                        onFailure = { error ->
                            localeFailure(
                                error = error,
                                previousEnabled = current.stockRussianLocale.enabled,
                                permissionReady = permissionReadyOnFailure == true,
                            )
                        },
                    ),
                )
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
    /**
     * Split the screen now, through the same door the launcher icon opens.
     *
     * Not a second way of doing it - literally the same entry activity, so the flow a driver gets
     * from the tile is the flow they get from the desktop, and there is one of it to keep working.
     */
    fun launchSplitScreen() {
        val context = appContext ?: return
        runCatching {
            context.startActivity(
                Intent(context, SplitLauncherEntryActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun setWeatherEnabled(enabled: Boolean) {
        val context = appContext ?: return
        WeatherAdapterState.setEnabled(context, enabled)
        if (enabled) WeatherAdapterScheduler.ensureScheduled(context)
        else WeatherAdapterScheduler.cancel(context)
        stateStore.update { current -> current.copy(weatherEnabled = enabled) }
    }

    fun setStockRussianLocaleEnabled(enabled: Boolean) {
        val context = appContext ?: return
        val permissionReady = StockRussianLocaleCoordinator.hasPermission(context)
        val start = claimStockRussianLocaleChange(enabled, permissionReady) ?: return
        localeExecutor.execute {
            val result = runCatching {
                StockRussianLocaleCoordinator.setEnabled(context, enabled)
            }
            val permissionReadyOnFailure = result.exceptionOrNull()?.let {
                StockRussianLocaleCoordinator.hasPermission(context)
            }
            val completed = result.fold(
                onSuccess = { (change, override) ->
                    localeSnapshot(
                        status = override,
                        reapplied = change == StockRussianLocaleChange.REAPPLIED,
                    )
                },
                onFailure = { error ->
                    localeFailure(
                        error = error,
                        previousEnabled = start.previousEnabled,
                        permissionReady = permissionReadyOnFailure == true,
                    )
                },
            )
            stateStore.update { current -> current.copy(stockRussianLocale = completed) }
        }
    }

    private fun initializeAdbGate(context: Context) {
        appContext = context.applicationContext
        AdbRescueCoordinator.initialize(context)
        when (AdbStartupGatePolicy.entryAction(AdbRescueCoordinator.snapshot().phase)) {
            // UNKNOWN is the one automatic startup probe. All other unresolved outcomes stay
            // latched until the user explicitly presses a button in the blocking overlay.
            AdbStartupEntryAction.CHECK_ACCESS -> {
                refresh()
                checkAdbAccess()
            }
            AdbStartupEntryAction.START_RUNTIME -> startAdbRuntime(context)
            AdbStartupEntryAction.NONE -> refresh()
        }
    }

    private fun onAdbRescueChanged(context: Context) {
        if (AdbRescueCoordinator.snapshot().phase == AdbRescuePhase.TRUSTED) {
            startAdbRuntime(context)
        }
        runtimeStep("ADB state refresh") { refresh() }
    }

    private fun startAdbRuntime(context: Context) {
        if (!adbRuntimePassRunning.compareAndSet(false, true)) return
        val app = context.applicationContext
        adbRuntimeStarted.set(true)
        try {
            runtimeStep("split initialize") {
                SplitScreenCoordinator.initialize(app) { refresh() }
            }
            runtimeStep("split reconcile") { reconcileSplitScreenToggle(app) }
            runtimeStep("navigation initialize") {
                NavigationCoordinator.initialize(app) { refresh() }
            }
            runtimeStep("weather initialize") {
                WeatherAdapterScheduler.ensureScheduled(app)
                val weatherEnabled = WeatherAdapterState.enabled(app)
                val weatherTemperature = WeatherAdapterState.lastTemperature(app)
                val weatherUpdatedMillis = WeatherAdapterState.lastSuccessMillis(app)
                stateStore.update { current ->
                    current.copy(
                        weatherEnabled = weatherEnabled,
                        weatherTemperature = weatherTemperature,
                        weatherUpdatedMillis = weatherUpdatedMillis,
                    )
                }
            }
            runtimeStep("dashboard refresh") { refresh() }
            runtimeStep("default apps refresh") { refreshDefaultApps() }
            runtimeStep("steering wheel reconcile") {
                reconcileNavigationSteeringWheelAccess(app)
            }
            runtimeStep("simulcast reconcile") {
                reconcileSimulcast(repairMissingSetup = true)
            }
            runtimeStep("mirrors reconcile") {
                if (MirrorsSettings.isEnabled(app)) reconcileMirrors()
            }
            runtimeStep("HUD reconcile") { reconcileHudNotificationAccess(app) }
            runtimeStep("speaker covers reconcile") { SpeakerCoverService.reconcile(app) }
        } finally {
            adbRuntimePassRunning.set(false)
        }
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
            refresh()
            return
        }
        when (val selection = ClusterDisplayResolver.resolveCameraOverlay(context)) {
            is ClusterDisplaySelection.Selected -> {
                SideCameraMonitorService.start(context)
                refresh()
            }
            else -> {
                val mirrors = MirrorDisplayReadiness.snapshot(selection, active = false)
                val technicalDetails = supportDiagnostics(context)
                stateStore.update { current ->
                    current.copy(
                        mirrors = mirrors,
                        technicalDetails = technicalDetails,
                    )
                }
            }
        }
    }

    private fun reconcileHudNotificationAccess(context: Context) {
        if (!HudGuidanceSettings.isEnabled(context)) return
        HudNotificationAccessCoordinator.ensureAccess(context) { refresh() }
    }

    private fun reconcileNavigationSteeringWheelAccess(context: Context) {
        SteeringWheelNavigationAccessCoordinator.reconcile(context) { refresh() }
        refresh()
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
                    stateStore.update { current ->
                        current.copy(setupRunning = event.setupRunning)
                    }
                    refresh()
                }
                is SimulcastReconcileEvent.Blocked -> {
                    val simulcast = SimulcastCoordinator.blockedSnapshot(event.blocker)
                    val technicalDetails = supportDiagnostics(context)
                    stateStore.update { current ->
                        current.copy(
                            setupRunning = event.setupRunning,
                            simulcast = simulcast,
                            selectedAppCount = event.selectedAppCount,
                            technicalDetails = technicalDetails,
                        )
                    }
                }
                SimulcastReconcileEvent.Repairing -> {
                    val simulcast = FeatureReducer.recovering(
                        FeatureReducer.starting(FeatureId.SIMULCAST),
                        "Восстанавливаю доступ",
                    )
                    stateStore.update { current ->
                        current.copy(
                            setupRunning = event.setupRunning,
                            simulcast = simulcast,
                        )
                    }
                }
                SimulcastReconcileEvent.Repaired -> {
                    stateStore.update { current ->
                        current.copy(setupRunning = event.setupRunning)
                    }
                    refresh()
                }
                is SimulcastReconcileEvent.RepairFailed -> {
                    val simulcast = FeatureReducer.needsAction(
                        FeatureReducer.starting(FeatureId.SIMULCAST),
                        event.message,
                        event.details,
                        event.resolution,
                    )
                    val technicalDetails = supportDiagnostics(context)
                    stateStore.update { current ->
                        current.copy(
                            setupRunning = event.setupRunning,
                            simulcast = simulcast,
                            technicalDetails = technicalDetails,
                        )
                    }
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
        if (!HudGuidanceSettings.isEnabled(context)) {
            return FeatureReducer.disabled(FeatureId.HUD_GUIDANCE)
        }
        if (!isInstalled(context.packageManager, NavigationAppPolicy.DEFAULT_PACKAGE)) {
            return FeatureSnapshot(
                id = FeatureId.HUD_GUIDANCE,
                desiredEnabled = true,
                status = FeatureStatus.UNAVAILABLE,
                message = "Яндекс Навигатор не найден",
            )
        }
        if (!SimulcastCoordinator.isAccessibilityEnabled(context)) {
            return FeatureReducer.needsAction(
                FeatureReducer.starting(FeatureId.HUD_GUIDANCE),
                "Повторите настройку доступа",
                resolution = FeatureResolution.RETRY,
            )
        }
        if (!SimulcastAccessibilityService.isConnected()) {
            return FeatureReducer.recovering(
                FeatureReducer.starting(FeatureId.HUD_GUIDANCE),
                "Подключаю подсказки",
            )
        }
        return FeatureReducer.ready(
            FeatureId.HUD_GUIDANCE,
            active = HudGuidanceRuntime.isActive(),
        ).copy(details = HudGuidanceRuntime.details())
    }

    private fun navigationSnapshot(
        phase: NavigationPhase,
        message: String,
        details: String?,
        resolution: FeatureResolution?,
    ): FeatureSnapshot {
        val status = when (phase) {
            NavigationPhase.READY -> FeatureStatus.READY
            NavigationPhase.OPENING,
            NavigationPhase.PROJECTING,
            NavigationPhase.RETURNING,
            -> FeatureStatus.STARTING
            NavigationPhase.PROJECTED -> FeatureStatus.ACTIVE
            NavigationPhase.RECOVERING -> FeatureStatus.RECOVERING
            NavigationPhase.NEEDS_ACTION -> FeatureStatus.NEEDS_ACTION
        }
        return FeatureSnapshot(
            id = FeatureId.NAVIGATION,
            desiredEnabled = phase == NavigationPhase.PROJECTED,
            status = status,
            message = message,
            details = details,
            resolution = resolution,
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
        // right now and whether its icon is on the launcher, and nothing else.
        return FeatureSnapshot(
            id = FeatureId.SPLIT_SCREEN,
            desiredEnabled = launcherVisible,
            status = status,
            message = session.message.ifBlank {
                if (launcherVisible) {
                    "Иконка Split Screen доступна"
                } else {
                    "Иконка Split Screen скрыта"
                }
            },
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

    /**
     * Whether the last read is recent enough to answer for the car without asking it again.
     *
     * Anything short of three roles read and settled fails this: an error, a role still applying
     * and a refresh already in flight all have to reach the provider before they can be believed.
     */
    private fun defaultAppsReadIsFresh(): Boolean {
        val readAt = defaultAppsReadAtElapsed
        if (readAt == 0L) return false
        if (SystemClock.elapsedRealtime() - readAt > DEFAULT_APPS_FRESH_WINDOW_MS) return false
        val defaults = stateStore.snapshot().state.defaultApps
        return !defaults.refreshing &&
            defaults.roles.all { it.status == DefaultAppRoleStatus.READY }
    }

    /**
     * The installed-launcher catalog, swept only when it cannot answer for [requiredPackage].
     *
     * Storing one choice used to sweep every installed launcher and load an icon for each - a
     * second time within the same second, for labels the refresh it opened with had already read.
     */
    private fun defaultAppsInstalled(
        context: Context,
        requiredPackage: String?,
    ): List<InstalledDefaultApp> {
        val cached = defaultAppsCatalog
        val answers = cached != null &&
            (requiredPackage == null || cached.any { it.packageName == requiredPackage })
        if (answers) return checkNotNull(cached)
        return DefaultAppsCatalog.discover(context).also { defaultAppsCatalog = it }
    }

    /** At most one provider refresh runs and one newer request waits behind it. */
    private fun scheduleDefaultAppsRefresh(context: Context) {
        if (!defaultAppsRefreshRunning.compareAndSet(false, true)) return
        defaultAppsExecutor.execute {
            try {
                defaultAppsRefreshRequested.set(false)
                runDefaultAppsRefresh(context)
            } catch (error: Exception) {
                publishDefaultAppsUnavailable(
                    defaultAppsFailure("Не удалось обновить приложения", error),
                )
            } finally {
                defaultAppsRefreshRunning.set(false)
                if (defaultAppsRefreshRequested.get()) {
                    scheduleDefaultAppsRefresh(context)
                }
            }
        }
    }

    private fun runDefaultAppsRefresh(context: Context) {
        if (!defaultAppsRuntimeReady()) {
            publishDefaultAppsUnavailable("Нужен доверенный локальный ADB")
            return
        }

        val installed = runCatching { DefaultAppsCatalog.discover(context) }
            .getOrElse { error ->
                publishDefaultAppsUnavailable(
                    defaultAppsFailure("Не удалось прочитать список приложений", error),
                )
                return
            }
        defaultAppsCatalog = installed
        val repository = defaultAppRoleRepository(context)

        DefaultAppRole.entries.forEach { role ->
            // A tap accepted while this refresh was already in flight owns the role until its
            // exact set/readback completes. Refreshing the other roles must not undo APPLYING.
            if (stateStore.snapshot().state.defaultApps.stateFor(role).status ==
                DefaultAppRoleStatus.APPLYING
            ) {
                return@forEach
            }

            val roleState = refreshDefaultAppRole(context, repository, role, installed)
            stateStore.updateIf(
                predicate = { current ->
                    current.defaultApps.stateFor(role).status != DefaultAppRoleStatus.APPLYING
                },
                transform = { current ->
                    current.copy(
                        defaultApps = current.defaultApps.update(role) { roleState },
                    )
                },
            )
        }

        stateStore.update { current ->
            current.copy(
                defaultApps = current.defaultApps.copy(
                    refreshing = defaultAppsRefreshRequested.get(),
                ),
            )
        }
        // Only three roles actually read and settled may spare the next gesture its own read.
        val settled = stateStore.snapshot().state.defaultApps.roles
            .all { it.status == DefaultAppRoleStatus.READY }
        defaultAppsReadAtElapsed = if (settled) SystemClock.elapsedRealtime() else 0L
    }

    private fun refreshDefaultAppRole(
        context: Context,
        repository: DefaultAppRoleRepository,
        role: DefaultAppRole,
        installed: List<InstalledDefaultApp>,
    ): DefaultAppRoleUiState {
        val previous = stateStore.snapshot().state.defaultApps.stateFor(role)
        var observedPackage: String? = null
        var writeAttempted = false

        return try {
            observedPackage = runBlocking { repository.read(role) }
            val initializationHandled = DefaultAppsSettings.isInitializationHandled(context, role)
            val launchablePackages = installed.map(InstalledDefaultApp::packageName)
            val coldSelection = DefaultAppsPolicy.coldStartSelection(
                role = role,
                providerPackageName = observedPackage,
                initializationHandled = initializationHandled,
                installedLaunchablePackages = launchablePackages,
            )
            val shouldAutoSelect = DefaultAppsPolicy.shouldAutoApplyColdStartSelection(
                role = role,
                providerPackageName = observedPackage,
                initializationHandled = initializationHandled,
                resolvedPackageName = coldSelection,
            )

            val persisted = if (shouldAutoSelect) {
                writeAttempted = true
                runBlocking {
                    repository.setIfCurrent(
                        role = role,
                        expectedCurrentPackageName = role.stockPackageName,
                        packageName = checkNotNull(coldSelection),
                    )
                }.also {
                    // The provider is the source of truth. Only its exact successful readback may
                    // complete first-run handling after an automatic change.
                    DefaultAppsSettings.markInitializationHandled(context, role)
                }
            } else {
                checkNotNull(observedPackage)
            }
            if (!initializationHandled && !shouldAutoSelect) {
                // A successful first resolution is final even when it keeps stock or preserves an
                // external non-stock value. Installing a known app later must not silently change
                // an already observed role.
                DefaultAppsSettings.markInitializationHandled(context, role)
            }
            defaultAppProviderState(
                role = role,
                providerPackageName = persisted,
                installed = installed,
                message = if (shouldAutoSelect) "Выбрано автоматически" else "",
            )
        } catch (error: Throwable) {
            val recovered = if (writeAttempted) {
                runCatching { runBlocking { repository.read(role) } }.getOrNull()
            } else {
                null
            }
            // Once an update was attempted, the pre-write observation is no longer proof of the
            // current value. Only a recovery read may confirm it; otherwise retain the old value
            // strictly as last-known UI context.
            val confirmedPackage = recovered ?: observedPackage.takeUnless { writeAttempted }
            defaultAppErrorState(
                role = role,
                providerPackageName = confirmedPackage
                    ?: observedPackage
                    ?: previous.selectedPackageName,
                installed = installed,
                providerConfirmed = confirmedPackage != null,
                message = defaultAppsFailure(
                    when {
                        writeAttempted -> "Не удалось завершить автоматический выбор"
                        observedPackage != null -> "Не удалось завершить инициализацию"
                        else -> "Не удалось прочитать настройку"
                    },
                    error,
                ),
            )
        }
    }

    private fun applyDefaultAppSelection(
        context: Context,
        role: DefaultAppRole,
        packageName: String,
    ) {
        // The chosen package is checked against the car as it is now; the catalog behind the
        // labels and icons may be the one the sheet was opened with.
        val selectable = runCatching {
            DefaultAppsCatalog.isLaunchableNow(context, packageName)
        }.getOrElse { error ->
            finishDefaultAppRole(
                role,
                abandonedDefaultAppWrite(
                    role,
                    defaultAppsFailure("Не удалось проверить приложение", error),
                ),
            )
            return
        }
        val installed = runCatching { defaultAppsInstalled(context, packageName) }
            .getOrElse { error ->
                finishDefaultAppRole(
                    role,
                    abandonedDefaultAppWrite(
                        role,
                        defaultAppsFailure("Не удалось прочитать список приложений", error),
                    ),
                )
                return
            }
        if (!selectable) {
            val previous = stateStore.snapshot().state.defaultApps.stateFor(role)
            finishDefaultAppRole(
                role,
                defaultAppErrorState(
                    role = role,
                    providerPackageName = previous.selectedPackageName,
                    installed = installed,
                    providerConfirmed = false,
                    message = "Приложение больше не установлено или не запускается",
                ),
            )
            return
        }
        if (!defaultAppsRuntimeReady()) {
            finishDefaultAppRole(
                role,
                defaultAppErrorState(
                    role = role,
                    providerPackageName = stateStore.snapshot().state.defaultApps
                        .stateFor(role).selectedPackageName,
                    installed = installed,
                    providerConfirmed = false,
                    message = "Нужен доверенный локальный ADB",
                ),
            )
            return
        }

        finishDefaultAppRole(
            role,
            writeDefaultAppRole(context, role, packageName, installed),
        )
    }

    /**
     * One role written to the provider and reported, whichever gesture asked for it.
     *
     * The picker asks for one role and the tile's switch asks for all three; what happens to a
     * role is the same either way, down to the failure, which is why it is written once.
     */
    private fun writeDefaultAppRole(
        context: Context,
        role: DefaultAppRole,
        packageName: String,
        installed: List<InstalledDefaultApp>,
    ): DefaultAppRoleUiState {
        val repository = defaultAppRoleRepository(context)
        var persistedPackage: String? = null
        val result = runCatching {
            persistedPackage = runBlocking { repository.set(role, packageName) }
            // Even a deliberate stock choice becomes distinguishable only after the provider has
            // echoed it back exactly. This marker must never be written optimistically.
            if (!DefaultAppsSettings.isInitializationHandled(context, role)) {
                DefaultAppsSettings.markInitializationHandled(context, role)
            }
            checkNotNull(persistedPackage)
        }

        return result.fold(
            onSuccess = { persisted ->
                defaultAppProviderState(role, persisted, installed)
            },
            onFailure = { error ->
                val recovered = runCatching { runBlocking { repository.read(role) } }.getOrNull()
                val confirmedPackage = recovered ?: persistedPackage
                defaultAppErrorState(
                    role = role,
                    providerPackageName = confirmedPackage
                        ?: stateStore.snapshot().state.defaultApps
                            .stateFor(role).selectedPackageName,
                    installed = installed,
                    providerConfirmed = confirmedPackage != null,
                    message = defaultAppsFailure("Не удалось сохранить выбор", error),
                )
            },
        )
    }

    /**
     * A write that never reached the provider, reported without keeping the mark it moved.
     *
     * The grid marks a tap before the write leaves, so a failure that only changes the status has
     * to put that mark back on the package the car last confirmed - otherwise the panel goes on
     * showing the choice as made while saying it was not.
     */
    private fun abandonedDefaultAppWrite(
        role: DefaultAppRole,
        message: String,
    ): DefaultAppRoleUiState {
        val previous = stateStore.snapshot().state.defaultApps.stateFor(role)
        return previous.copy(
            status = DefaultAppRoleStatus.ERROR,
            providerConfirmed = false,
            pendingPackageName = null,
            message = message,
            choices = previous.choices.map { choice ->
                choice.copy(selected = choice.packageName == previous.selectedPackageName)
            },
        )
    }

    private fun defaultAppProviderState(
        role: DefaultAppRole,
        providerPackageName: String,
        installed: List<InstalledDefaultApp>,
        message: String = "",
    ): DefaultAppRoleUiState {
        if (!DefaultAppsCatalog.isLaunchable(providerPackageName, installed)) {
            return defaultAppErrorState(
                role = role,
                providerPackageName = providerPackageName,
                installed = installed,
                providerConfirmed = true,
                message = "Выбранное приложение не установлено или не запускается",
            )
        }
        // Whatever the car is confirmed to be running for this role is what switching the
        // substitution back on should restore, so it is remembered where it is observed rather
        // than at the moment the switch is thrown - by then the role already says "stock".
        appContext?.let { context ->
            DefaultAppsSettings.rememberPick(context, role, providerPackageName)
        }
        return DefaultAppRoleUiState(
            role = role,
            selectedPackageName = providerPackageName,
            selectedLabel = DefaultAppsCatalog.label(role, providerPackageName, installed),
            choices = DefaultAppsCatalog.choices(role, providerPackageName, installed),
            status = DefaultAppRoleStatus.READY,
            message = message,
            providerConfirmed = true,
        )
    }

    private fun defaultAppErrorState(
        role: DefaultAppRole,
        providerPackageName: String?,
        installed: List<InstalledDefaultApp>,
        providerConfirmed: Boolean,
        message: String,
    ): DefaultAppRoleUiState = DefaultAppRoleUiState(
        role = role,
        selectedPackageName = providerPackageName,
        selectedLabel = DefaultAppsCatalog.label(role, providerPackageName, installed),
        choices = DefaultAppsCatalog.choices(role, providerPackageName, installed),
        status = DefaultAppRoleStatus.ERROR,
        message = message,
        providerConfirmed = providerConfirmed,
    )

    /**
     * Decides what each role should hold, and marks the panel with it before anything is written.
     *
     * Returns null when a read or another write is already in flight, which is the same rule a tap
     * on the grid follows: the provider is one row per role and two writers would race for it.
     */
    private fun claimDefaultAppsSwitch(
        context: Context,
        enabled: Boolean,
    ): Map<DefaultAppRole, String>? {
        while (true) {
            val snapshot = stateStore.snapshot()
            val current = snapshot.state
            if (current.defaultApps.refreshing || current.defaultApps.roles.any { it.busy }) {
                return null
            }

            val targets = DefaultAppRole.entries.mapNotNull { role ->
                val roleState = current.defaultApps.stateFor(role)
                val target = if (enabled) {
                    switchOnTarget(context, role, roleState)
                } else {
                    role.stockPackageName
                }
                // A role already holding its target has nothing to write; the provider would
                // accept the update and report the same value back.
                target?.takeIf { it != roleState.selectedPackageName }?.let { role to it }
            }.toMap()
            if (targets.isEmpty()) return null

            val updated = current.copy(
                defaultApps = targets.entries.fold(current.defaultApps) { defaults, (role, target) ->
                    defaults.update(role) { roleState ->
                        roleState.copy(
                            status = DefaultAppRoleStatus.APPLYING,
                            message = "",
                            pendingPackageName = target,
                            choices = roleState.choices.map { choice ->
                                choice.copy(selected = choice.packageName == target)
                            },
                        )
                    }
                },
            )
            if (stateStore.compareAndSet(snapshot, updated)) return targets
        }
    }

    /**
     * What switching the substitution on should put into a role.
     *
     * The package the car was last seen running for it, and failing that the catalog's first
     * installed suggestion - the same order a first run resolves in, so a role the driver has
     * never touched comes on holding what it would have held anyway.
     */
    private fun switchOnTarget(
        context: Context,
        role: DefaultAppRole,
        roleState: DefaultAppRoleUiState,
    ): String? {
        val remembered = DefaultAppsSettings.rememberedPick(context, role)
        val launchable = roleState.choices.map(DefaultAppChoice::packageName)
        if (remembered != null && remembered in launchable) return remembered
        return role.knownThirdPartyApps
            .firstOrNull { it.packageName in launchable }
            ?.packageName
    }

    /** Writes each role of a switch, one at a time, reporting each as it lands. */
    private fun applyDefaultAppTargets(
        context: Context,
        targets: Map<DefaultAppRole, String>,
    ) {
        val installed = runCatching { defaultAppsInstalled(context, null) }
            .getOrElse { error ->
                val message = defaultAppsFailure("Не удалось прочитать список приложений", error)
                targets.keys.forEach { role ->
                    finishDefaultAppRole(role, abandonedDefaultAppWrite(role, message))
                }
                return
            }
        targets.forEach { (role, packageName) ->
            // The car's own application is what "off" means and is never withheld for not being
            // in a catalog we swept; anything else has to still be there to be worth writing.
            val available = packageName == role.stockPackageName ||
                DefaultAppsCatalog.isLaunchable(packageName, installed)
            val completed = if (available) {
                writeDefaultAppRole(context, role, packageName, installed)
            } else {
                abandonedDefaultAppWrite(
                    role,
                    "Приложение больше не установлено или не запускается",
                )
            }
            finishDefaultAppRole(role, completed)
        }
    }

    private fun claimDefaultAppSelection(
        role: DefaultAppRole,
        packageName: String,
    ): Boolean {
        while (true) {
            val snapshot = stateStore.snapshot()
            val current = snapshot.state
            val roleState = current.defaultApps.stateFor(role)
            if (current.defaultApps.refreshing || roleState.busy) return false

            val launchablePackages = roleState.choices.map { it.packageName }
            val selectable = DefaultAppsPolicy.isSelectable(packageName, launchablePackages)
            val updatedRole = if (selectable) {
                // The mark moves with the finger. It used to wait for the provider to echo the
                // write back, so the tile the driver had just pressed stayed unmarked for as long
                // as the car took to answer, and the old one stayed marked beside it.
                roleState.copy(
                    status = DefaultAppRoleStatus.APPLYING,
                    message = "",
                    pendingPackageName = packageName,
                    choices = roleState.choices.map { choice ->
                        choice.copy(selected = choice.packageName == packageName)
                    },
                )
            } else {
                roleState.copy(
                    status = DefaultAppRoleStatus.ERROR,
                    message = "Приложение больше не доступно",
                    pendingPackageName = null,
                )
            }
            val updated = current.copy(
                defaultApps = current.defaultApps.update(role) { updatedRole },
            )
            if (stateStore.compareAndSet(snapshot, updated)) return selectable
        }
    }

    private fun finishDefaultAppRole(
        role: DefaultAppRole,
        completed: DefaultAppRoleUiState,
    ) {
        stateStore.update { current ->
            current.copy(
                defaultApps = current.defaultApps.update(role) { completed },
            )
        }
    }

    private fun publishDefaultAppRoleError(
        role: DefaultAppRole,
        message: String,
    ) {
        stateStore.update { current ->
            current.copy(
                defaultApps = current.defaultApps.update(role) { roleState ->
                    roleState.copy(
                        status = DefaultAppRoleStatus.ERROR,
                        message = message,
                        providerConfirmed = false,
                        pendingPackageName = null,
                    )
                },
            )
        }
    }

    private fun publishDefaultAppsUnavailable(message: String) {
        stateStore.update { current ->
            current.copy(
                defaultApps = current.defaultApps.copy(
                    refreshing = false,
                    roles = current.defaultApps.roles.map { roleState ->
                        if (roleState.status == DefaultAppRoleStatus.APPLYING) {
                            roleState
                        } else {
                            roleState.copy(
                                status = DefaultAppRoleStatus.ERROR,
                                message = message,
                                providerConfirmed = false,
                            )
                        }
                    },
                ),
            )
        }
    }

    private fun publishDefaultAppsWaitingForRuntime() {
        stateStore.update { current ->
            current.copy(
                defaultApps = current.defaultApps.copy(
                    refreshing = false,
                    roles = current.defaultApps.roles.map { roleState ->
                        if (roleState.status == DefaultAppRoleStatus.APPLYING) {
                            roleState
                        } else {
                            roleState.copy(
                                status = DefaultAppRoleStatus.LOADING,
                                message = "Ждём доступ к машине…",
                                providerConfirmed = false,
                            )
                        }
                    },
                ),
            )
        }
    }

    private fun defaultAppsRuntimeReady(): Boolean =
        adbRuntimeStarted.get() &&
            AdbRescueCoordinator.snapshot().phase == AdbRescuePhase.TRUSTED

    private fun defaultAppRoleRepository(context: Context): DefaultAppRoleRepository {
        defaultAppsRepository?.let { return it }
        return synchronized(defaultAppsRepositoryLock) {
            defaultAppsRepository ?: DefaultAppRoleRepository(context).also {
                defaultAppsRepository = it
            }
        }
    }

    /**
     * What went wrong, in the panel's words - and only in the panel's words.
     *
     * This used to glue the exception's own message, or failing that its class name, onto the
     * prefix and cut the result at three hundred characters. Three hundred characters is four or
     * five lines of the settings panel, written by a content provider, in whatever language and
     * whatever register that provider happens to use - "android.os.DeadObjectException", or a
     * sentence about a cursor. None of it is actionable from a driver's seat, all of it moves
     * everything below it down the panel, and the last thing it does is convince the reader the
     * app is broken in a way they are expected to understand.
     *
     * The prefix already says the thing that matters. The exception goes to logcat, which is where
     * the person who can act on it is looking.
     */
    private fun defaultAppsFailure(prefix: String, error: Throwable): String {
        Log.w(TAG, "$prefix (default applications)", error)
        return prefix
    }

    private fun navigationAppChoices(
        context: Context,
        selectedPackage: String,
    ): List<NavigationAppChoice> = NavigationSettings.choices(context).map { definition ->
        NavigationAppChoice(
            packageName = definition.packageName,
            label = definition.fallbackLabel,
            icon = runCatching {
                context.packageManager.getApplicationIcon(definition.packageName)
            }.getOrNull(),
            selected = definition.packageName == selectedPackage,
        )
    }

    /**
     * What a tap on the passenger picker turns out to be.
     *
     * [STALE] is the one case worth telling apart: the picker was drawn from a list that has since
     * changed under it, so the honest answer is a picker that shows the car as it is now rather
     * than a sentence explaining why the tile the driver just pressed did nothing.
     */
    private enum class FseInstallClaim { START, BUSY, STALE }

    private fun claimFseInstall(packageName: String): FseInstallClaim {
        while (true) {
            val snapshot = stateStore.snapshot()
            val current = snapshot.state
            if (
                current.fseInstaller.status == FeatureStatus.STARTING ||
                current.fseInstaller.status == FeatureStatus.RECOVERING
            ) {
                return FseInstallClaim.BUSY
            }

            val app = current.fseInstallApps.firstOrNull { it.packageName == packageName }
            // A package that cannot be sent across is already drawn as unpressable, so reaching
            // here means the list is out of date. Both of these used to write a line of amber over
            // the grid instead - "APK недоступен", "Приложение больше не найдено" - which is the
            // picker teaching the driver to read explanations of taps that will never work.
            if (app == null || !app.installable) return FseInstallClaim.STALE

            val updated = current.copy(
                fseInstallerPickerVisible = false,
                fseInstaller = FeatureSnapshot(
                    id = FeatureId.FSE_INSTALLER,
                    desiredEnabled = false,
                    status = FeatureStatus.STARTING,
                    message = app.label,
                ),
            )
            if (stateStore.compareAndSet(snapshot, updated)) return FseInstallClaim.START
        }
    }

    private data class LocaleChangeStart(val previousEnabled: Boolean?)

    private fun claimStockRussianLocaleChange(
        enabled: Boolean,
        permissionReady: Boolean,
    ): LocaleChangeStart? {
        while (true) {
            val snapshot = stateStore.snapshot()
            val current = snapshot.state
            if (current.stockRussianLocale.running) return null

            if (!permissionReady && current.adbRescue.phase != AdbRescuePhase.TRUSTED) {
                // The one prerequisite this switch has, as a state rather than as a lesson. The
                // paragraph that used to sit in `details` - what ADB is for and what happens
                // afterwards - was read by nothing on the screen, and the tile stayed grey while
                // the press did nothing at all.
                val blocked = current.copy(
                    stockRussianLocale = StockRussianLocaleSnapshot(
                        enabled = current.stockRussianLocale.enabled,
                        permissionReady = false,
                        failed = true,
                        message = "Нужен доступ к машине",
                    ),
                )
                if (stateStore.compareAndSet(snapshot, blocked)) return null
                continue
            }

            val started = current.copy(
                stockRussianLocale = current.stockRussianLocale.copy(
                    running = true,
                    message = when {
                        !permissionReady -> "Один раз подготавливаю доступ…"
                        enabled -> "Включаю ru-RU напрямую…"
                        else -> "Возвращаю язык системы напрямую…"
                    },
                    details = null,
                ),
            )
            if (stateStore.compareAndSet(snapshot, started)) {
                return LocaleChangeStart(current.stockRussianLocale.enabled)
            }
        }
    }

    private fun supportDiagnostics(context: Context): String =
        SupportDiagnostics.build(context, stateStore.snapshot().state.fseInstaller)

    private fun localeSnapshot(
        status: StockRussianLocaleStatus,
        reapplied: Boolean = false,
    ): StockRussianLocaleSnapshot = StockRussianLocaleSnapshot(
        enabled = status.enabled,
        permissionReady = status.permissionReady,
        message = when {
            status.enabled == null && status.permissionReady ->
                "Выберите «Вкл» или «Выкл» один раз"
            status.enabled == null ->
                "Первый выбор подготовит системное разрешение"
            status.enabled && reapplied ->
                "Русский повторно применён. Переоткройте BYD Настройки"
            status.enabled ->
                "Русский включён. Переоткройте BYD Настройки"
            !status.enabled && reapplied ->
                "Язык системы повторно применён"
            else ->
                "Русский выключен. Используется язык системы"
        },
    )

    /**
     * A language change the car refused, said in a way something reads.
     *
     * The message written here used to go nowhere: the tile took its colour from the saved value
     * alone, so a refusal left "Выключен" in the same grey as a language nobody had ever asked
     * for, and the exception text under it was read by nothing at all. [failed] is what the tile
     * and the service door see; the exception goes to logcat.
     */
    private fun localeFailure(
        error: Throwable,
        previousEnabled: Boolean?,
        permissionReady: Boolean,
    ): StockRussianLocaleSnapshot {
        Log.w(TAG, "Stock Russian locale change failed", error)
        return StockRussianLocaleSnapshot(
            enabled = previousEnabled,
            permissionReady = permissionReady,
            failed = true,
            message = "Язык не переключился",
        )
    }

    private fun loadAppChoices(context: Context): List<SimulcastAppChoice> {
        val selected = SimulcastApps.getSelected(context)
        return loadLaunchableAppChoices(context, selected)
    }

    private fun loadLaunchableAppChoices(
        context: Context,
        selected: Collection<String>,
    ): List<SimulcastAppChoice> {
        val selectedOrder = selected.withIndex().associate { it.value to it.index }
        // Room for one more, or not. The picker greys what a full selection cannot take rather
        // than accepting the tap and printing the rule afterwards.
        val roomLeft = selectedOrder.size < SimulcastApps.MAX_SELECTED
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        return context.packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == context.packageName || !seen.add(packageName)) return@mapNotNull null
                val isSelected = packageName in selectedOrder
                SimulcastAppChoice(
                    packageName = packageName,
                    label = info.loadLabel(context.packageManager).toString(),
                    icon = runCatching { info.loadIcon(context.packageManager) }.getOrNull(),
                    selected = isSelected,
                    // Taking one off the list is always allowed; putting one on is not.
                    selectable = isSelected || roomLeft,
                )
            }
            .sortedWith(
                compareBy<SimulcastAppChoice> { selectedOrder[it.packageName] ?: Int.MAX_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
            )
    }

    private fun selectedAppChoices(context: Context): List<SimulcastAppChoice> =
        SimulcastApps.getSelected(context).map { packageName ->
            val info = runCatching {
                context.packageManager.getApplicationInfo(packageName, 0)
            }.getOrNull()
            SimulcastAppChoice(
                packageName = packageName,
                label = info?.let { context.packageManager.getApplicationLabel(it).toString() }
                    ?: packageName,
                icon = info?.let { runCatching { context.packageManager.getApplicationIcon(it) }.getOrNull() },
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

/**
 * How long a settled provider read answers for the car before the next gesture asks it again.
 *
 * Long enough to cover resume-then-open, which is the pair of gestures that made the tile read
 * the provider twice; short enough that a choice made in stock settings is picked up on the next
 * visit rather than after a restart.
 */
private const val DEFAULT_APPS_FRESH_WINDOW_MS = 30_000L
