package dev.denza.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
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
import dev.denza.apps.feature.adb.AdbRescueCoordinator
import dev.denza.apps.feature.adb.AdbRescuePhase
import dev.denza.apps.feature.adb.AdbRescueSnapshot
import dev.denza.apps.feature.adb.AdbStartupEntryAction
import dev.denza.apps.feature.adb.AdbStartupGatePolicy
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class SimulcastAppChoice(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val selected: Boolean,
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
    val stockRussianLocale: StockRussianLocaleSnapshot = StockRussianLocaleSnapshot(),
    val weatherEnabled: Boolean = true,
    val weatherTemperature: Int? = null,
    val weatherUpdatedMillis: Long = 0L,
    val technicalDetails: String = "",
    val clusterCandidates: List<ClusterDisplayDescriptor> = emptyList(),
    val appPickerVisible: Boolean = false,
    val appChoices: List<SimulcastAppChoice> = emptyList(),
    val appPickerMessage: String = "",
    val fseInstallerPickerVisible: Boolean = false,
    val fseInstallApps: List<FseInstallApp> = emptyList(),
    val fseInstallerMessage: String = "",
)

/** Android-facing state owner shared by the Compose shell and runtime services. */
object DenzaAppRepository {
    private val executor = Executors.newSingleThreadExecutor()
    private val localeExecutor = Executors.newSingleThreadExecutor()
    private val adbRuntimeStarted = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(DenzaUiState())
    val state: StateFlow<DenzaUiState> = mutableState.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        initializeAdbGate(context.applicationContext)
    }

    fun recoverEnabledFeatures(context: Context) {
        initializeAdbGate(context.applicationContext)
    }

    fun refresh() {
        val context = appContext ?: return
        val adbRescue = AdbRescueCoordinator.snapshot()
        if (adbRescue.phase != AdbRescuePhase.TRUSTED || !adbRuntimeStarted.get()) {
            // Keep the last healthy dashboard (or its neutral first-launch defaults) behind the
            // startup overlay. Individual feature probes must not turn a missing global ADB
            // prerequisite into a wall of unrelated errors.
            mutableState.value = mutableState.value.copy(adbRescue = adbRescue)
            return
        }
        val snapshot = SimulcastCoordinator.evaluate(SimulcastCoordinator.inspect(context))
        val navigationSession = NavigationCoordinator.snapshot()
        val navigationPackage = NavigationCoordinator.selectedPackage()
        val steeringWheelAccess = SteeringWheelNavigationAccessCoordinator.inspect(context)
        val splitLauncherVisible = SplitLauncherIconController.isVisible(context)
        val splitScreenSession = SplitScreenCoordinator.snapshot()
        val selectedApps = selectedAppChoices(context)
        mutableState.value = mutableState.value.copy(
            simulcast = snapshot,
            mirrors = evaluateMirrors(context),
            selectedAppCount = SimulcastApps.selectedCount(context),
            selectedAppLabels = selectedApps.map(SimulcastAppChoice::label),
            selectedApps = selectedApps,
            mirrorsPosition = MirrorsSettings.position(context),
            mirrorsProcessing = MirrorsSettings.processingEnabled(context),
            navigation = navigationSnapshot(
                navigationSession.phase,
                navigationSession.message,
                navigationSession.details,
                navigationSession.resolution,
            ),
            navigationButtonLabel = navigationSession.buttonLabel,
            navigationSteeringWheelButton = steeringWheelAccess.desired,
            navigationSteeringWheelButtonReady = steeringWheelAccess.ready,
            navigationSteeringWheelButtonRepairing =
                steeringWheelAccess.desired &&
                    SteeringWheelNavigationAccessCoordinator.isRepairing(),
            navigationPlacement = NavigationPlacementPolicy.resolve(
                navigationPackage,
                NavigationCoordinator.placement(),
            ),
            navigationPlacements = NavigationPlacementPolicy.offered(navigationPackage),
            navigationAppLabel = NavigationAppPolicy.fallbackLabel(navigationPackage),
            navigationAppChoices = navigationAppChoices(context, navigationPackage),
            // Loaded here rather than when a picker asks for it. The projection panel offers this
            // list inline, and a panel that says "which applications" over an empty space until
            // some other flow happens to have run is a panel that lies about what it is for.
            appChoices = loadAppChoices(context),
            splitScreen = splitScreenSnapshot(
                launcherVisible = splitLauncherVisible,
                session = splitScreenSession,
            ),
            hudGuidance = evaluateHudGuidance(context),
            speakerCovers = SpeakerCoverRuntime.featureSnapshot(context),
            adbRescue = adbRescue,
            technicalDetails = supportDiagnostics(context),
            clusterCandidates = ClusterDisplayResolver.candidates(context),
        )
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
        mutableState.value = mutableState.value.copy(
            simulcast = FeatureReducer.starting(FeatureId.SIMULCAST),
        )
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
            mutableState.value = mutableState.value.copy(
                simulcast = SimulcastCoordinator.blockedSnapshot(
                    SimulcastBlocker.DISHARE_UNAVAILABLE,
                ),
            )
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
    }

    fun showAppPicker() {
        val context = appContext ?: return
        mutableState.value = mutableState.value.copy(
            appPickerVisible = true,
            appChoices = loadAppChoices(context),
            appPickerMessage = "",
        )
    }

    fun hideAppPicker() {
        mutableState.value = mutableState.value.copy(appPickerVisible = false)
    }

    fun showFseInstallerPicker() {
        val context = appContext ?: return
        mutableState.value = mutableState.value.copy(
            fseInstallerPickerVisible = true,
            fseInstallApps = FseAppInstaller.installedApps(context),
            fseInstallerMessage = "",
        )
    }

    fun hideFseInstallerPicker() {
        mutableState.value = mutableState.value.copy(fseInstallerPickerVisible = false)
    }

    fun installOnPassengerScreen(packageName: String) {
        val context = appContext ?: return
        val current = mutableState.value
        if (current.fseInstaller.status == FeatureStatus.STARTING ||
            current.fseInstaller.status == FeatureStatus.RECOVERING
        ) {
            return
        }
        val app = current.fseInstallApps.firstOrNull { it.packageName == packageName }
        if (app == null) {
            mutableState.value = current.copy(fseInstallerMessage = "Приложение больше не найдено")
            return
        }
        if (!app.installable) {
            mutableState.value = current.copy(
                fseInstallerMessage = app.unavailableReason.ifBlank { "APK недоступен" },
            )
            return
        }

        mutableState.value = current.copy(
            fseInstallerPickerVisible = false,
            fseInstaller = FeatureSnapshot(
                id = FeatureId.FSE_INSTALLER,
                desiredEnabled = false,
                status = FeatureStatus.STARTING,
                message = "Подготавливаю ${app.label}",
            ),
        )
        executor.execute {
            val result = FseAppInstaller.install(context, packageName) { message ->
                mutableState.value = mutableState.value.copy(
                    fseInstaller = FeatureSnapshot(
                        id = FeatureId.FSE_INSTALLER,
                        desiredEnabled = false,
                        status = FeatureStatus.STARTING,
                        message = message,
                    ),
                )
            }
            mutableState.value = when (result) {
                is FseInstallResult.Installed -> mutableState.value.copy(
                    fseInstaller = FeatureSnapshot(
                        id = FeatureId.FSE_INSTALLER,
                        desiredEnabled = false,
                        status = FeatureStatus.READY,
                        message = "Установлено: ${result.app.label}",
                    ),
                )
                is FseInstallResult.Failed -> mutableState.value.copy(
                    fseInstaller = FeatureSnapshot(
                        id = FeatureId.FSE_INSTALLER,
                        desiredEnabled = false,
                        status = FeatureStatus.ERROR,
                        message = result.message,
                        details = result.details,
                    ),
                )
            }
        }
    }

    fun toggleAppSelection(packageName: String) {
        val context = appContext ?: return
        val selected = SimulcastApps.getSelected(context).toMutableList()
        if (packageName in selected) {
            selected.remove(packageName)
        } else if (selected.size >= SimulcastApps.MAX_SELECTED) {
            mutableState.value = mutableState.value.copy(
                appPickerMessage = "Можно выбрать не больше ${SimulcastApps.MAX_SELECTED}",
            )
            return
        } else {
            selected.add(packageName)
        }
        SimulcastApps.setSelected(context, selected)
        refresh()
        mutableState.value = mutableState.value.copy(
            appChoices = loadAppChoices(context),
            appPickerMessage = "",
        )
    }

    fun setMirrorsEnabled(enabled: Boolean) {
        val context = appContext ?: return
        MirrorsSettings.setEnabled(context, enabled)
        if (!enabled) {
            SideCameraMonitorService.stop(context)
            refresh()
            return
        }
        mutableState.value = mutableState.value.copy(
            mirrors = FeatureReducer.starting(FeatureId.MIRRORS),
        )
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
        mutableState.value = mutableState.value.copy(
            navigationAppChoices = navigationAppChoices(context, selected),
            navigationPickerVisible = true,
        )
    }

    fun hideNavigationAppPicker() {
        mutableState.value = mutableState.value.copy(navigationPickerVisible = false)
    }

    fun selectNavigationApp(packageName: String) {
        val context = appContext ?: return
        if (!NavigationAppPolicy.isAllowed(packageName)) return
        if (!NavigationSettings.isInstalled(context, packageName)) return
        mutableState.value = mutableState.value.copy(navigationPickerVisible = false)
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
            mutableState.value = mutableState.value.copy(
                splitScreen = FeatureReducer.failed(
                    previous = mutableState.value.splitScreen.copy(desiredEnabled = enabled),
                    message = "Не удалось изменить Split Screen",
                    details = error.toString(),
                ),
            )
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
        mutableState.value = mutableState.value.copy(
            hudGuidance = FeatureReducer.starting(FeatureId.HUD_GUIDANCE),
        )
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
                mutableState.value = mutableState.value.copy(
                    hudGuidance = FeatureReducer.needsAction(
                        FeatureReducer.starting(FeatureId.HUD_GUIDANCE),
                        problem.message,
                        failure.toString(),
                        problem.resolution,
                    ),
                    technicalDetails = supportDiagnostics(context),
                )
            }
        }
    }

    fun setSpeakerCoversEnabled(enabled: Boolean) {
        val context = appContext ?: return
        SpeakerCoverSettings.setEnabled(context, enabled)
        if (enabled) {
            mutableState.value = mutableState.value.copy(
                speakerCovers = FeatureReducer.starting(FeatureId.SPEAKER_COVERS),
            )
            SpeakerCoverService.reconcile(context)
        } else {
            // A close command suppresses the amplifier's stock auto-lift for this ignition
            // cycle. Leave the covers physically open when the user turns our automation off.
            SpeakerCoverService.disableAndOpen(context)
            refresh()
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

    fun refreshStockRussianLocale() {
        val context = appContext ?: return
        val current = mutableState.value
        if (current.stockRussianLocale.running) return
        val result = runCatching { StockRussianLocaleCoordinator.inspect(context) }
        mutableState.value = current.copy(
            stockRussianLocale = result.fold(
                onSuccess = { status -> localeSnapshot(status) },
                onFailure = { error -> localeFailure(error) },
            ),
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
        mutableState.value = mutableState.value.copy(weatherEnabled = enabled)
    }

    fun setStockRussianLocaleEnabled(enabled: Boolean) {
        val context = appContext ?: return
        val current = mutableState.value
        if (current.stockRussianLocale.running) return
        val permissionReady = StockRussianLocaleCoordinator.hasPermission(context)
        if (!permissionReady && current.adbRescue.phase != AdbRescuePhase.TRUSTED) {
            mutableState.value = current.copy(
                stockRussianLocale = StockRussianLocaleSnapshot(
                    enabled = current.stockRussianLocale.enabled,
                    permissionReady = false,
                    message = "Нужен доверенный локальный ADB",
                    details = "ADB нужен один раз для системного разрешения. После этого язык " +
                        "переключается напрямую.",
                ),
            )
            return
        }

        mutableState.value = current.copy(
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
        localeExecutor.execute {
            val result = runCatching {
                StockRussianLocaleCoordinator.setEnabled(context, enabled)
            }
            mutableState.value = mutableState.value.copy(
                stockRussianLocale = result.fold(
                    onSuccess = { (change, override) ->
                        localeSnapshot(
                            status = override,
                            reapplied = change == StockRussianLocaleChange.REAPPLIED,
                        )
                    },
                    onFailure = { error ->
                        localeFailure(error, previousEnabled = current.stockRussianLocale.enabled)
                    },
                ),
            )
        }
    }

    private fun initializeAdbGate(context: Context) {
        appContext = context.applicationContext
        AdbRescueCoordinator.initialize(context)
        refresh()
        when (AdbStartupGatePolicy.entryAction(AdbRescueCoordinator.snapshot().phase)) {
            // UNKNOWN is the one automatic startup probe. All other unresolved outcomes stay
            // latched until the user explicitly presses a button in the blocking overlay.
            AdbStartupEntryAction.CHECK_ACCESS -> checkAdbAccess()
            AdbStartupEntryAction.START_RUNTIME -> startAdbRuntime(context)
            AdbStartupEntryAction.NONE -> Unit
        }
    }

    private fun onAdbRescueChanged(context: Context) {
        if (AdbRescueCoordinator.snapshot().phase == AdbRescuePhase.TRUSTED) {
            startAdbRuntime(context)
        }
        refresh()
    }

    private fun startAdbRuntime(context: Context) {
        if (!adbRuntimeStarted.compareAndSet(false, true)) return
        val app = context.applicationContext
        SplitScreenCoordinator.initialize(app) { refresh() }
        reconcileSplitScreenToggle(app)
        NavigationCoordinator.initialize(app) { refresh() }
        WeatherAdapterScheduler.ensureScheduled(app)
        mutableState.value = mutableState.value.copy(
            weatherEnabled = WeatherAdapterState.enabled(app),
            weatherTemperature = WeatherAdapterState.lastTemperature(app),
            weatherUpdatedMillis = WeatherAdapterState.lastSuccessMillis(app),
        )
        refresh()
        reconcileNavigationSteeringWheelAccess(app)
        reconcileSimulcast(repairMissingSetup = true)
        if (MirrorsSettings.isEnabled(app)) reconcileMirrors()
        reconcileHudNotificationAccess(app)
        SpeakerCoverService.reconcile(app)
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
            else -> mutableState.value = mutableState.value.copy(
                mirrors = MirrorDisplayReadiness.snapshot(selection, active = false),
                technicalDetails = supportDiagnostics(context),
            )
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
            mutableState.value = mutableState.value.copy(setupRunning = event.setupRunning)
            when (event) {
                SimulcastReconcileEvent.Refresh -> refresh()
                is SimulcastReconcileEvent.Blocked -> {
                    mutableState.value = mutableState.value.copy(
                        simulcast = SimulcastCoordinator.blockedSnapshot(event.blocker),
                        selectedAppCount = event.selectedAppCount,
                        technicalDetails = supportDiagnostics(context),
                    )
                }
                SimulcastReconcileEvent.Repairing -> {
                    mutableState.value = mutableState.value.copy(
                        simulcast = FeatureReducer.recovering(
                            FeatureReducer.starting(FeatureId.SIMULCAST),
                            "Восстанавливаю доступ",
                        ),
                    )
                }
                SimulcastReconcileEvent.Repaired -> refresh()
                is SimulcastReconcileEvent.RepairFailed -> {
                    mutableState.value = mutableState.value.copy(
                        simulcast = FeatureReducer.needsAction(
                            FeatureReducer.starting(FeatureId.SIMULCAST),
                            event.message,
                            event.details,
                            event.resolution,
                        ),
                        technicalDetails = supportDiagnostics(context),
                    )
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

    private fun supportDiagnostics(context: Context): String =
        SupportDiagnostics.build(context, mutableState.value.fseInstaller)

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

    private fun localeFailure(
        error: Throwable,
        previousEnabled: Boolean? = mutableState.value.stockRussianLocale.enabled,
    ): StockRussianLocaleSnapshot = StockRussianLocaleSnapshot(
        enabled = previousEnabled,
        permissionReady = appContext?.let(StockRussianLocaleCoordinator::hasPermission) == true,
        message = "Не удалось изменить штатную локаль",
        details = error.message ?: error.toString(),
    )

    private fun loadAppChoices(context: Context): List<SimulcastAppChoice> {
        val selected = SimulcastApps.getSelected(context)
        return loadLaunchableAppChoices(context, selected)
    }

    private fun loadLaunchableAppChoices(
        context: Context,
        selected: Collection<String>,
    ): List<SimulcastAppChoice> {
        val selectedOrder = selected.withIndex().associate { it.value to it.index }
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        return context.packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == context.packageName || !seen.add(packageName)) return@mapNotNull null
                SimulcastAppChoice(
                    packageName = packageName,
                    label = info.loadLabel(context.packageManager).toString(),
                    icon = runCatching { info.loadIcon(context.packageManager) }.getOrNull(),
                    selected = packageName in selectedOrder,
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
