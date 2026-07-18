package dev.denza.apps

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.TextUtils
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.feature.cluster.ClusterDisplayResolver
import dev.denza.apps.feature.cluster.ClusterDisplaySelection
import dev.denza.apps.feature.cluster.ClusterSceneService
import dev.denza.apps.feature.mirrors.MirrorsPosition
import dev.denza.apps.feature.mirrors.MirrorsSettings
import dev.denza.apps.feature.mirrors.SideCameraMonitorService
import dev.denza.apps.feature.navigation.NavigationCoordinator
import dev.denza.apps.feature.navigation.NavigationPhase
import dev.denza.disharebridge.LocalAdbClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.GeneralSecurityException
import java.util.concurrent.Executors

data class DenzaUiState(
    val simulcast: FeatureSnapshot = FeatureReducer.disabled(FeatureId.SIMULCAST),
    val mirrors: FeatureSnapshot = FeatureReducer.disabled(FeatureId.MIRRORS),
    val navigation: FeatureSnapshot = FeatureSnapshot(
        id = FeatureId.NAVIGATION,
        desiredEnabled = false,
        status = FeatureStatus.READY,
    ),
    val navigationButtonLabel: String = "Открыть Яндекс",
    val selectedAppCount: Int = 0,
    val mirrorsPosition: MirrorsPosition = MirrorsPosition.SIDES,
    val mirrorsProcessing: Boolean = true,
    val setupRunning: Boolean = false,
    val technicalDetails: String = "",
)

/** Android-facing state owner shared by the Compose shell and runtime services. */
object DenzaAppRepository {
    private const val DISHARE_PACKAGE = "com.byd.dishare"
    private const val ADB_KEY_COMMENT = "denza-apps@denza"
    private const val ACCESSIBILITY_COMPONENT =
        "dev.denza.apps/dev.denza.apps.SimulcastAccessibilityService"

    private val executor = Executors.newSingleThreadExecutor()
    private val mutableState = MutableStateFlow(DenzaUiState())
    val state: StateFlow<DenzaUiState> = mutableState.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        refresh()
        reconcileSimulcast(repairMissingSetup = true)
        if (MirrorsSettings.isEnabled(context)) reconcileMirrors()
        NavigationCoordinator.initialize(context) { refresh() }
    }

    fun refresh() {
        val context = appContext ?: return
        val desired = SimulcastIntegration.isEnabled(context)
        val snapshot = evaluateSimulcast(context, desired)
        val navigationSession = NavigationCoordinator.snapshot()
        mutableState.value = mutableState.value.copy(
            simulcast = snapshot,
            mirrors = evaluateMirrors(context),
            selectedAppCount = SimulcastApps.selectedCount(context),
            mirrorsPosition = MirrorsSettings.position(context),
            mirrorsProcessing = MirrorsSettings.processingEnabled(context),
            navigation = navigationSnapshot(navigationSession.phase, navigationSession.message, navigationSession.details),
            navigationButtonLabel = navigationSession.buttonLabel,
            technicalDetails = diagnostics(context),
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
        val needsSetup = !hasOverlayPermission(context) || !isAccessibilityEnabled(context)
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
                LocalAdbClient(context, ADB_KEY_COMMENT).shell(buildRepairCommand(context))
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

    private fun blockingProblem(context: Context): String? {
        if (!isInstalled(context.packageManager, DISHARE_PACKAGE)) return "Simulcast не найден"
        if (SimulcastApps.getSelected(context).isEmpty()) return "Выберите приложения"
        return null
    }

    private fun hasOverlayPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        if (TextUtils.isEmpty(flat)) return false
        return flat.contains(ACCESSIBILITY_COMPONENT) ||
            flat.contains("dev.denza.apps/.SimulcastAccessibilityService")
    }

    private fun buildRepairCommand(context: Context): String {
        val packageName = shellQuote(context.packageName)
        val service = shellQuote(ACCESSIBILITY_COMPONENT)
        return "cmd appops set $packageName SYSTEM_ALERT_WINDOW allow" +
            "; svc=$service" +
            "; current=\"\$(settings get secure enabled_accessibility_services)\"" +
            "; if [ \"\$current\" = \"null\" ] || [ -z \"\$current\" ]; then next=\"\$svc\"" +
            "; else case \":\$current:\" in *\":\$svc:\"*) next=\"\$current\";;" +
            " *) next=\"\$current:\$svc\";; esac; fi" +
            "; settings put secure enabled_accessibility_services \"\$next\"" +
            "; settings put secure accessibility_enabled 1"
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
        appendLine("Denza Apps 0.2.0")
        appendLine("Simulcast desired=${SimulcastIntegration.isEnabled(context)}")
        appendLine("DiShare installed=${isInstalled(context.packageManager, DISHARE_PACKAGE)}")
        appendLine("Overlay allowed=${hasOverlayPermission(context)}")
        appendLine("Accessibility enabled=${isAccessibilityEnabled(context)}")
        appendLine("Selected apps=${SimulcastApps.selectedCount(context)}")
        appendLine("Mirrors desired=${MirrorsSettings.isEnabled(context)}")
        appendLine("Mirrors position=${MirrorsSettings.position(context)}")
        appendLine("Mirrors processing=${MirrorsSettings.processingEnabled(context)}")
        appendLine("Mirrors runtime=${MirrorsSettings.statusDetails(context)}")
        appendLine("Cluster selection=${ClusterDisplayResolver.resolve(context)}")
        append("Navigation=${NavigationCoordinator.snapshot()}")
    }

    private fun isInstalled(packageManager: PackageManager, packageName: String): Boolean = try {
        packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
}
