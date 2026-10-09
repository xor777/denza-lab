package dev.denza.apps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import dev.denza.apps.feature.adb.AdbRestore
import dev.denza.apps.feature.cluster.ClusterMapPlacement
import dev.denza.apps.feature.defaultapps.DefaultAppRole
import dev.denza.apps.feature.mirrors.MirrorsPosition
import dev.denza.apps.feature.navigation.NavigationTransferOverlay
import dev.denza.apps.feature.simulcast.SimulcastIntegration
import dev.denza.apps.feature.simulcast.SimulcastOverlayService
import dev.denza.apps.ui.DenzaAppsRoot
import dev.denza.apps.ui.dashboard.DenzaActions

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        DenzaAppRepository.initialize(this)
        setContent(
            content = {
                DenzaAppsRoot(state = DenzaAppRepository.state, actions = AppActions)
            },
        )
    }

    override fun onResume() {
        super.onResume()
        NavigationTransferOverlay.setMainActivityResumed(this, true)
        DenzaAppRepository.refresh("resume")
        DenzaAppRepository.defaultApps.refresh()
        DenzaAppRepository.refreshCloudLink()
    }

    override fun onPause() {
        NavigationTransferOverlay.setMainActivityResumed(this, false)
        super.onPause()
        if (SimulcastIntegration.isEnabled(this) &&
            SimulcastIntegration.getLastTargetPackage() != null
        ) {
            SimulcastOverlayService.showActiveExit(this)
        }
    }

}

/**
 * What the screen can ask the app to do, answered by the repository and the features' runtimes.
 *
 * One object for the process, so the screen is handed the same one after every recreation of the
 * activity, and every member is stored once: a panel handed `onToggleMirrors` gets the same
 * lambda on every recomposition (`AppActionsTest`). Each lambda reaches the repository only when
 * it is called, so building this object starts nothing.
 */
internal object AppActions : DenzaActions {
    override val onToggleSimulcast: (Boolean) -> Unit =
        { enabled -> DenzaAppRepository.setSimulcastEnabled(enabled) }
    override val onLaunchSimulcast: () -> Unit = { DenzaAppRepository.launchSimulcast() }
    override val onRepairSimulcast: () -> Unit = { DenzaAppRepository.repairSimulcast() }
    override val onLoadAppChoices: () -> Unit = { DenzaAppRepository.refreshAppChoices() }
    override val onToggleApp: (String) -> Unit =
        { packageName -> DenzaAppRepository.toggleAppSelection(packageName) }

    override val onToggleMirrors: (Boolean) -> Unit =
        { enabled -> DenzaAppRepository.setMirrorsEnabled(enabled) }
    override val onMirrorsPosition: (MirrorsPosition) -> Unit =
        { position -> DenzaAppRepository.setMirrorsPosition(position) }
    override val onMirrorsProcessing: (Boolean) -> Unit =
        { enabled -> DenzaAppRepository.setMirrorsProcessing(enabled) }
    override val onPreviewMirrors: () -> Unit = { DenzaAppRepository.previewMirrors() }

    override val onNavigationAction: () -> Unit = { DenzaAppRepository.performNavigationAction() }
    override val onNavigationPlacement: (ClusterMapPlacement) -> Unit =
        { placement -> DenzaAppRepository.setNavigationPlacement(placement) }
    override val onNavigationSteeringWheelButton: (Boolean) -> Unit =
        { enabled -> DenzaAppRepository.setNavigationSteeringWheelButton(enabled) }
    override val onLoadNavigationAppChoices: () -> Unit =
        { DenzaAppRepository.refreshNavigationAppChoices() }
    override val onSelectNavigationApp: (String) -> Boolean =
        { packageName -> DenzaAppRepository.selectNavigationApp(packageName) }
    override val onSelectClusterDisplay: (Int?) -> Unit =
        { displayId -> DenzaAppRepository.selectClusterDisplay(displayId) }
    override val onSearchClusterDisplays: () -> Unit = { DenzaAppRepository.searchClusterDisplays() }

    override val onToggleSplitScreen: (Boolean) -> Unit =
        { enabled -> DenzaAppRepository.setSplitScreenEnabled(enabled) }
    override val onLaunchSplitScreen: () -> Unit = { DenzaAppRepository.launchSplitScreen() }

    override val onSetWeatherEnabled: (Boolean) -> Unit =
        { enabled -> DenzaAppRepository.setWeatherEnabled(enabled) }

    override val onToggleHudGuidance: (Boolean) -> Unit =
        { enabled -> DenzaAppRepository.setHudGuidanceEnabled(enabled) }

    override val onToggleSpeakerCovers: (Boolean) -> Unit =
        { enabled -> DenzaAppRepository.setSpeakerCoversEnabled(enabled) }
    override val onRaiseSpeakerCovers: () -> Unit = { DenzaAppRepository.raiseSpeakerCovers() }

    override val onToggleCloudLink: (Boolean) -> Unit =
        { enabled -> DenzaAppRepository.setCloudLinkEnabled(enabled) }
    override val onSetCloudWifiRetained: (Boolean) -> Unit =
        { retain -> DenzaAppRepository.setCloudWifiRetained(retain) }

    override val onRefreshSystemLanguage: () -> Unit = { DenzaAppRepository.refreshSystemLanguage() }
    override val onOpenSystemLanguage: () -> Unit = { DenzaAppRepository.openSystemLanguage() }

    override val onRefreshDefaultApps: (Boolean) -> Unit =
        { force -> DenzaAppRepository.defaultApps.refresh(force) }
    override val onSetDefaultAppsEnabled: (Boolean) -> Unit =
        { enabled -> DenzaAppRepository.defaultApps.setEnabled(enabled) }
    override val onSelectDefaultApp: (DefaultAppRole, String) -> Unit =
        { role, packageName -> DenzaAppRepository.defaultApps.select(role, packageName) }

    override val onLoadFseApps: () -> Boolean = { DenzaAppRepository.fseInstall.refreshApps() }
    override val onInstallFseApp: (String) -> Boolean =
        { packageName -> DenzaAppRepository.fseInstall.install(packageName) }

    override val onRefreshScreenDiagnostics: () -> Unit =
        { DenzaAppRepository.refreshScreenDiagnostics() }
    override val onServiceReportVisible: (Boolean) -> Unit =
        { open -> DenzaAppRepository.setServiceReportOpen(open) }
    override val onServiceOpened: () -> Unit = { AdbRestore.trigger("settings") }
    override val onSetAdbRestoreEnabled: (Boolean) -> Unit =
        { enabled -> AdbRestore.setEnabled(enabled) }
    override val onCheckAdbAccess: () -> Unit = { DenzaAppRepository.checkAdbAccess() }
    override val onCheckAdbAccessThen: (() -> Unit) -> Unit =
        { onTrusted -> DenzaAppRepository.checkAdbAccessThen(onTrusted) }
    override val onRequestAdbAuthorizationOnce: () -> Unit =
        { DenzaAppRepository.requestAdbAuthorizationOnce() }
    override val onAllowNewAdbAuthorizationAttempt: () -> Unit =
        { DenzaAppRepository.allowNewAdbAuthorizationAttempt() }
}
