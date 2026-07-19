package dev.denza.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.provider.Settings
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.feature.cluster.ClusterDisplayResolver
import dev.denza.apps.feature.cluster.ClusterDisplayDescriptor
import dev.denza.apps.feature.cluster.ClusterDisplaySelection
import dev.denza.apps.feature.cluster.ClusterMapPlacement
import dev.denza.apps.feature.cluster.ClusterSceneService
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
import dev.denza.disharebridge.LocalAdbClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.GeneralSecurityException
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
)

/** Android-facing state owner shared by the Compose shell and runtime services. */
object DenzaAppRepository {
    private const val DISHARE_PACKAGE = "com.byd.dishare"
    private const val ADB_KEY_COMMENT = "denza-apps@denza"
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
        val desired = SimulcastIntegration.isEnabled(context)
        val snapshot = evaluateSimulcast(context, desired)
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
            technicalDetails = diagnostics(context),
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
        val launch = context.packageManager.getLaunchIntentForPackage(DISHARE_PACKAGE)
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
        if (isAccessibilityEnabled(context) && SimulcastAccessibilityService.isConnected()) {
            SimulcastAccessibilityService.requestHudGuidanceRefresh()
            refresh()
            return
        }
        executor.execute {
            val failure = try {
                repairSimulcastAccess(context)
                null
            } catch (error: Exception) {
                error
            }
            if (failure == null) {
                SimulcastAccessibilityService.requestHudGuidanceRefresh()
                refresh()
            } else {
                mutableState.value = mutableState.value.copy(
                    hudGuidance = FeatureReducer.needsAction(
                        FeatureReducer.starting(FeatureId.HUD_GUIDANCE),
                        friendlySetupError(failure),
                        failure.toString(),
                    ),
                    technicalDetails = diagnostics(context),
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
                    technicalDetails = diagnostics(context),
                )
            ClusterDisplaySelection.Missing -> mutableState.value = mutableState.value.copy(
                mirrors = FeatureReducer.needsAction(
                    FeatureReducer.starting(FeatureId.MIRRORS),
                    "Приборный экран пока не найден",
                ),
                technicalDetails = diagnostics(context),
            )
        }
    }

    private fun reconcileSimulcast(
        repairMissingSetup: Boolean,
        forceRepair: Boolean = false,
    ) {
        val context = appContext ?: return
        val desired = SimulcastIntegration.isEnabled(context)
        if (!desired) {
            refresh()
            return
        }
        val blocking = blockingProblem(context)
        if (blocking != null) {
            mutableState.value = mutableState.value.copy(
                simulcast = FeatureReducer.needsAction(
                    FeatureReducer.starting(FeatureId.SIMULCAST),
                    blocking,
                ),
                selectedAppCount = SimulcastApps.selectedCount(context),
                technicalDetails = diagnostics(context),
            )
            return
        }
        val needsSetup = !hasOverlayPermission(context) ||
            !isAccessibilityEnabled(context) ||
            !SimulcastAccessibilityService.isConnected()
        if (!needsSetup && !forceRepair) {
            SimulcastOverlayService.startMonitor(context)
            refresh()
            return
        }
        if (!repairMissingSetup && !forceRepair) {
            refresh()
            return
        }

        mutableState.value = mutableState.value.copy(
            simulcast = FeatureReducer.recovering(
                FeatureReducer.starting(FeatureId.SIMULCAST),
                "Восстанавливаю доступ",
            ),
            setupRunning = true,
        )
        executor.execute {
            val failure = try {
                repairSimulcastAccess(context)
                null
            } catch (error: Exception) {
                error
            }
            if (failure == null && hasOverlayPermission(context) && isAccessibilityEnabled(context)) {
                SimulcastOverlayService.startMonitor(context)
                mutableState.value = mutableState.value.copy(setupRunning = false)
                refresh()
            } else {
                val message = friendlySetupError(failure)
                mutableState.value = mutableState.value.copy(
                    simulcast = FeatureReducer.needsAction(
                        FeatureReducer.starting(FeatureId.SIMULCAST),
                        message,
                        failure?.toString(),
                    ),
                    setupRunning = false,
                    technicalDetails = diagnostics(context),
                )
            }
        }
    }

    private fun evaluateSimulcast(context: Context, desired: Boolean): FeatureSnapshot {
        if (!desired) return FeatureReducer.disabled(FeatureId.SIMULCAST)
        blockingProblem(context)?.let {
            return FeatureReducer.needsAction(FeatureReducer.starting(FeatureId.SIMULCAST), it)
        }
        if (!hasOverlayPermission(context) || !isAccessibilityEnabled(context)) {
            return FeatureReducer.needsAction(
                FeatureReducer.starting(FeatureId.SIMULCAST),
                "Нужно разрешить доступ",
            )
        }
        if (!SimulcastAccessibilityService.isConnected()) {
            return FeatureReducer.recovering(
                FeatureReducer.starting(FeatureId.SIMULCAST),
                "Восстанавливаю трансляцию",
            )
        }
        return FeatureReducer.ready(
            FeatureId.SIMULCAST,
            active = SimulcastIntegration.getLastTargetPackage(context) != null,
        )
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
        if (!isAccessibilityEnabled(context)) {
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

    private fun blockingProblem(context: Context): String? {
        if (!isInstalled(context.packageManager, DISHARE_PACKAGE)) return "Simulcast не найден"
        if (SimulcastApps.getSelected(context).isEmpty()) return "Выберите приложения"
        return null
    }

    private fun hasOverlayPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val setting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        return SimulcastAccessibilityAccess.isEnabled(setting)
    }

    private fun repairSimulcastAccess(context: Context) {
        val adb = LocalAdbClient(context, ADB_KEY_COMMENT)
        val packageName = shellQuote(context.packageName)
        adb.shell("cmd appops set $packageName SYSTEM_ALERT_WINDOW allow")

        val current = adb.shell(
            "settings get secure enabled_accessibility_services",
        ).trim()
        val withoutService = SimulcastAccessibilityAccess.withoutService(current)
        adb.shell(
            "settings put secure enabled_accessibility_services " +
                shellQuote(withoutService),
        )
        Thread.sleep(250L)

        // Re-read before enabling so another accessibility setting changed during
        // the short rebind window is preserved.
        val refreshed = adb.shell(
            "settings get secure enabled_accessibility_services",
        ).trim()
        val withService = SimulcastAccessibilityAccess.withService(refreshed)
        adb.shell(
            "settings put secure enabled_accessibility_services " +
                shellQuote(withService) +
                "; settings put secure accessibility_enabled 1",
        )
    }

    private fun friendlySetupError(error: Exception?): String {
        if (error == null) return "Нужно подтвердить доступ"
        val message = error.message.orEmpty()
        return when {
            message.contains("authorization pending", ignoreCase = true) ->
                "Подтвердите ADB-ключ на экране автомобиля"
            message.contains("refused", ignoreCase = true) -> "Включите ADB на машине"
            message.contains("timeout", ignoreCase = true) ||
                message.contains("timed out", ignoreCase = true) -> "ADB пока не отвечает"
            error is GeneralSecurityException -> "Не удалось подготовить ключ доступа"
            else -> "Не удалось восстановить доступ"
        }
    }

    private fun diagnostics(context: Context): String = buildString {
        appendLine("Версия=0.2.0")
        appendLine("DiShare=${yesNo(isInstalled(context.packageManager, DISHARE_PACKAGE))}")
        appendLine("Доступ поверх окон=${yesNo(hasOverlayPermission(context))}")
        appendLine("Управление интерфейсом=${yesNo(isAccessibilityEnabled(context))}")
        appendLine("Сервис трансляции=${yesNo(SimulcastAccessibilityService.isConnected())}")
        SimulcastScreenDiagnostics.diagnosticLines().forEach { appendLine(it) }
        val displays = ClusterDisplayResolver.candidates(context)
        appendLine("Android displays=${displays.size}")
        displays.forEach { display ->
            appendLine(
                "Android display #${display.id}=" +
                    "name=${display.name.ifBlank { "—" }}; " +
                    "size=${display.width}×${display.height}; " +
                    "dpi=${display.densityDpi}; " +
                    "type=${display.type}; " +
                    "flags=0x${Integer.toHexString(display.flags)}; " +
                    "Denza virtual=${if (display.isOwnVirtualDisplay) "да" else "нет"}",
            )
        }
        appendLine("Трансляция=${enabledLabel(SimulcastIntegration.isEnabled(context))}")
        appendLine("Выбрано приложений=${SimulcastApps.selectedCount(context)}")
        appendLine("Зеркала=${enabledLabel(MirrorsSettings.isEnabled(context))}")
        appendLine(
            "Расположение зеркал=" + if (MirrorsSettings.position(context) == MirrorsPosition.CENTER) {
                "По центру"
            } else {
                "По сторонам"
            },
        )
        appendLine("Улучшение изображения=${enabledLabel(MirrorsSettings.processingEnabled(context))}")
        appendLine("Состояние зеркал=${mirrorRuntimeLabel(MirrorsSettings.statusDetails(context))}")
        appendLine("Экран приборки=${clusterSelectionLabel(ClusterDisplayResolver.resolve(context))}")
        val navigation = NavigationCoordinator.snapshot()
        appendLine("Навигация=${navigation.message.ifBlank { navigation.phase.name.lowercase() }}")
        val split = SplitScreenCoordinator.snapshot()
        appendLine("Split screen=${split.message.ifBlank { split.phase.name.lowercase() }}")
        appendLine("HUD-подсказки=${enabledLabel(HudGuidanceSettings.isEnabled(context))}")
        append("Данные HUD=${HudGuidanceRuntime.details()}")
    }

    private fun yesNo(value: Boolean) = if (value) "Доступен" else "Недоступен"

    private fun enabledLabel(value: Boolean) = if (value) "Включено" else "Выключено"

    private fun mirrorRuntimeLabel(value: String): String = when {
        value == "monitor running" -> "Монитор работает"
        value == "monitor stopped" -> "Монитор остановлен"
        value == "disabled after com.byd.avc failure" -> "Отключены после сбоя штатной камеры"
        value.startsWith("showing left") -> "Показывается левая камера"
        value.startsWith("showing right") -> "Показывается правая камера"
        value.isBlank() -> "Нет данных"
        else -> value
    }

    private fun clusterSelectionLabel(selection: ClusterDisplaySelection): String = when (selection) {
        is ClusterDisplaySelection.Selected -> with(selection.display) {
            "#$id · ${width}×$height · $name"
        }
        is ClusterDisplaySelection.NeedsVerification -> "Нужно выбрать экран"
        ClusterDisplaySelection.Missing -> "Не найден"
    }

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

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
}
