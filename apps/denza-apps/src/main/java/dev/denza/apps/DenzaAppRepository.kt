package dev.denza.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.feature.cluster.ClusterDisplayResolver
import dev.denza.apps.feature.cluster.ClusterDisplayDescriptor
import dev.denza.apps.feature.cluster.ClusterDisplaySelection
import dev.denza.apps.feature.cluster.ClusterMapPlacement
import dev.denza.apps.feature.cluster.ClusterSceneService
import dev.denza.apps.feature.fse.FseAppInstaller
import dev.denza.apps.feature.fse.FseInstallApp
import dev.denza.apps.feature.fse.FseInstallResult
import dev.denza.apps.feature.hud.HudGuidanceRuntime
import dev.denza.apps.feature.hud.HudGuidanceSettings
import dev.denza.apps.feature.mirrors.MirrorsPosition
import dev.denza.apps.feature.mirrors.MirrorsSettings
import dev.denza.apps.feature.mirrors.SideCameraMonitorService
import dev.denza.apps.feature.navigation.NavigationCoordinator
import dev.denza.apps.feature.navigation.NavigationAppPolicy
import dev.denza.apps.feature.navigation.NavigationPhase
import dev.denza.apps.feature.navigation.NavigationSettings
import dev.denza.apps.feature.split.SplitScreenCoordinator
import dev.denza.apps.feature.split.SplitScreenPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

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
    val fseInstaller: FeatureSnapshot = FeatureSnapshot(
        id = FeatureId.FSE_INSTALLER,
        desiredEnabled = false,
        status = FeatureStatus.READY,
    ),
    val navigationButtonLabel: String = "Открыть",
    val navigationAutomatic: Boolean = false,
    val navigationPlacement: ClusterMapPlacement = ClusterMapPlacement.FULL,
    val navigationAppLabel: String = "Яндекс Навигатор",
    val navigationAppChoices: List<NavigationAppChoice> = emptyList(),
    val navigationPickerVisible: Boolean = false,
    val selectedAppCount: Int = 0,
    val selectedAppLabels: List<String> = emptyList(),
    val selectedApps: List<SimulcastAppChoice> = emptyList(),
    val mirrorsPosition: MirrorsPosition = MirrorsPosition.SIDES,
    val mirrorsProcessing: Boolean = true,
    val setupRunning: Boolean = false,
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
    private val mutableState = MutableStateFlow(DenzaUiState())
    val state: StateFlow<DenzaUiState> = mutableState.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        SplitScreenCoordinator.initialize(context) { refresh() }
        refresh()
        reconcileSimulcast(repairMissingSetup = true)
        if (MirrorsSettings.isEnabled(context)) reconcileMirrors()
        NavigationCoordinator.initialize(context) { refresh() }
    }

    fun recoverEnabledFeatures(context: Context) {
        appContext = context.applicationContext
        SplitScreenCoordinator.initialize(context) { refresh() }
        refresh()
        if (SimulcastIntegration.isEnabled(context)) {
            reconcileSimulcast(repairMissingSetup = true)
        }
        if (MirrorsSettings.isEnabled(context)) {
            reconcileMirrors()
        }
    }

    fun refresh() {
        val context = appContext ?: return
        val snapshot = SimulcastCoordinator.evaluate(SimulcastCoordinator.inspect(context))
        val navigationSession = NavigationCoordinator.snapshot()
        val navigationPackage = NavigationCoordinator.selectedPackage()
        val splitSession = SplitScreenCoordinator.snapshot()
        val selectedApps = selectedAppChoices(context)
        mutableState.value = mutableState.value.copy(
            simulcast = snapshot,
            mirrors = evaluateMirrors(context),
            selectedAppCount = SimulcastApps.selectedCount(context),
            selectedAppLabels = selectedApps.map(SimulcastAppChoice::label),
            selectedApps = selectedApps,
            mirrorsPosition = MirrorsSettings.position(context),
            mirrorsProcessing = MirrorsSettings.processingEnabled(context),
            navigation = navigationSnapshot(navigationSession.phase, navigationSession.message, navigationSession.details),
            navigationButtonLabel = navigationSession.buttonLabel,
            navigationAutomatic = NavigationCoordinator.automaticEnabled(),
            navigationPlacement = NavigationCoordinator.placement(),
            navigationAppLabel = NavigationAppPolicy.fallbackLabel(navigationPackage),
            navigationAppChoices = navigationAppChoices(context, navigationPackage),
            splitScreen = splitScreenSnapshot(
                splitSession.enabled,
                splitSession.phase,
                splitSession.message,
                splitSession.details,
            ),
            hudGuidance = evaluateHudGuidance(context),
            technicalDetails = supportDiagnostics(context),
            clusterCandidates = ClusterDisplayResolver.candidates(context),
        )
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
                simulcast = FeatureReducer.needsAction(
                    FeatureReducer.starting(FeatureId.SIMULCAST),
                    "Simulcast не найден",
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
        when (ClusterDisplayResolver.resolve(context)) {
            is ClusterDisplaySelection.Selected -> ClusterSceneService.preview(
                context,
                MirrorsSettings.position(context),
                visible = true,
                durationMs = 2_200L,
            )
            else -> refresh()
        }
    }

    fun performNavigationAction() {
        NavigationCoordinator.performPrimaryAction()
    }

    fun setNavigationAutomatic(enabled: Boolean) {
        NavigationCoordinator.setAutomaticEnabled(enabled)
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
        SplitScreenCoordinator.setEnabled(enabled)
    }

    fun setHudGuidanceEnabled(enabled: Boolean) {
        val context = appContext ?: return
        HudGuidanceSettings.setEnabled(context, enabled)
        SimulcastAccessibilityService.requestHudGuidanceRefresh()
        if (!enabled) {
            refresh()
            return
        }
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
                mutableState.value = mutableState.value.copy(
                    hudGuidance = FeatureReducer.needsAction(
                        FeatureReducer.starting(FeatureId.HUD_GUIDANCE),
                        SimulcastCoordinator.friendlySetupError(failure),
                        failure.toString(),
                    ),
                    technicalDetails = supportDiagnostics(context),
                )
            }
        }
    }

    fun selectClusterDisplay(displayId: Int?) {
        val context = appContext ?: return
        ClusterDisplayResolver.saveOverride(context, displayId)
        if (displayId != null) {
            ClusterSceneService.preview(
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

    private fun reconcileMirrors() {
        val context = appContext ?: return
        if (!MirrorsSettings.isEnabled(context)) {
            refresh()
            return
        }
        when (ClusterDisplayResolver.resolve(context)) {
            is ClusterDisplaySelection.Selected -> {
                SideCameraMonitorService.start(context)
                refresh()
            }
            is ClusterDisplaySelection.NeedsVerification -> mutableState.value =
                mutableState.value.copy(
                    mirrors = FeatureReducer.needsAction(
                        FeatureReducer.starting(FeatureId.MIRRORS),
                        "Выберите экран в разделе помощи",
                    ),
                    technicalDetails = supportDiagnostics(context),
                )
            ClusterDisplaySelection.Missing -> mutableState.value = mutableState.value.copy(
                mirrors = FeatureReducer.needsAction(
                    FeatureReducer.starting(FeatureId.MIRRORS),
                    "Приборный экран пока не найден",
                ),
                technicalDetails = supportDiagnostics(context),
            )
        }
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
                SimulcastReconcileEvent.Refresh -> refresh()
                is SimulcastReconcileEvent.Blocked -> {
                    mutableState.value = mutableState.value.copy(
                        simulcast = FeatureReducer.needsAction(
                            FeatureReducer.starting(FeatureId.SIMULCAST),
                            event.message,
                        ),
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
                        setupRunning = true,
                    )
                }
                SimulcastReconcileEvent.Repaired -> {
                    mutableState.value = mutableState.value.copy(setupRunning = false)
                    refresh()
                }
                is SimulcastReconcileEvent.RepairFailed -> {
                    mutableState.value = mutableState.value.copy(
                        simulcast = FeatureReducer.needsAction(
                            FeatureReducer.starting(FeatureId.SIMULCAST),
                            event.message,
                            event.details,
                        ),
                        setupRunning = false,
                        technicalDetails = supportDiagnostics(context),
                    )
                }
            }
        }
    }

    private fun evaluateMirrors(context: Context): FeatureSnapshot {
        if (!MirrorsSettings.isEnabled(context)) return FeatureReducer.disabled(FeatureId.MIRRORS)
        return when (ClusterDisplayResolver.resolve(context)) {
            is ClusterDisplaySelection.Selected -> FeatureReducer.ready(
                FeatureId.MIRRORS,
                active = MirrorsSettings.observedSide(context) != null,
            )
            is ClusterDisplaySelection.NeedsVerification -> FeatureReducer.needsAction(
                FeatureReducer.starting(FeatureId.MIRRORS),
                "Нужно выбрать приборный экран",
            )
            ClusterDisplaySelection.Missing -> FeatureReducer.recovering(
                FeatureReducer.starting(FeatureId.MIRRORS),
                "Ищу приборный экран",
            )
        }
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
                "Нужно разрешить доступ",
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
        )
    }

    private fun splitScreenSnapshot(
        enabled: Boolean,
        phase: SplitScreenPhase,
        message: String,
        details: String?,
    ): FeatureSnapshot = FeatureSnapshot(
        id = FeatureId.SPLIT_SCREEN,
        desiredEnabled = enabled,
        status = when (phase) {
            SplitScreenPhase.OFF -> FeatureStatus.OFF
            SplitScreenPhase.STARTING -> FeatureStatus.STARTING
            SplitScreenPhase.ACTIVE -> FeatureStatus.ACTIVE
            SplitScreenPhase.ERROR -> FeatureStatus.ERROR
        },
        message = message,
        details = details,
    )

    private fun navigationAppChoices(
        context: Context,
        selectedPackage: String,
    ): List<NavigationAppChoice> = NavigationSettings.installedApps(context).map { definition ->
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

    private fun loadAppChoices(context: Context): List<SimulcastAppChoice> {
        val selected = SimulcastApps.getSelected(context)
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
