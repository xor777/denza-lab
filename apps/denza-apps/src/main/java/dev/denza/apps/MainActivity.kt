package dev.denza.apps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import dev.denza.apps.feature.navigation.NavigationTransferOverlay
import dev.denza.apps.ui.DenzaAppsRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        DenzaAppRepository.initialize(this)
        setContent(
            content = {
                DenzaAppsRoot(
                    state = DenzaAppRepository.state,
                    onToggleSimulcast = DenzaAppRepository::setSimulcastEnabled,
                    onLaunchSimulcast = DenzaAppRepository::launchSimulcast,
                    onRepairSimulcast = DenzaAppRepository::repairSimulcast,
                    onToggleMirrors = DenzaAppRepository::setMirrorsEnabled,
                    onMirrorsPosition = DenzaAppRepository::setMirrorsPosition,
                    onMirrorsProcessing = DenzaAppRepository::setMirrorsProcessing,
                    onPreviewMirrors = DenzaAppRepository::previewMirrors,
                    onNavigationAction = DenzaAppRepository::performNavigationAction,
                    onNavigationSteeringWheelButton =
                        DenzaAppRepository::setNavigationSteeringWheelButton,
                    onNavigationPlacement = DenzaAppRepository::setNavigationPlacement,
                    onChooseNavigationApp = DenzaAppRepository::showNavigationAppPicker,
                    onCloseNavigationPicker = DenzaAppRepository::hideNavigationAppPicker,
                    onSelectNavigationApp = DenzaAppRepository::selectNavigationApp,
                    onToggleSplitScreen = DenzaAppRepository::setSplitScreenEnabled,
                    onLaunchSplitScreen = DenzaAppRepository::launchSplitScreen,
                    onSetWeatherEnabled = DenzaAppRepository::setWeatherEnabled,
                    onToggleHudGuidance = DenzaAppRepository::setHudGuidanceEnabled,
                    onToggleSpeakerCovers = DenzaAppRepository::setSpeakerCoversEnabled,
                    onRaiseSpeakerCovers = DenzaAppRepository::raiseSpeakerCovers,
                    onLowerSpeakerCovers = DenzaAppRepository::lowerSpeakerCovers,
                    onSelectClusterDisplay = DenzaAppRepository::selectClusterDisplay,
                    onRefreshScreenDiagnostics = DenzaAppRepository::refreshScreenDiagnostics,
                    onCheckAdbAccess = DenzaAppRepository::checkAdbAccess,
                    onRequestAdbAuthorizationOnce =
                        DenzaAppRepository::requestAdbAuthorizationOnce,
                    onAllowNewAdbAuthorizationAttempt =
                        DenzaAppRepository::allowNewAdbAuthorizationAttempt,
                    onRefreshStockRussianLocale =
                        DenzaAppRepository::refreshStockRussianLocale,
                    onSetStockRussianLocaleEnabled =
                        DenzaAppRepository::setStockRussianLocaleEnabled,
                    onRefreshDefaultApps = DenzaAppRepository::refreshDefaultApps,
                    onSelectDefaultApp = DenzaAppRepository::selectDefaultApp,
                    onChooseApps = DenzaAppRepository::showAppPicker,
                    onCloseAppPicker = DenzaAppRepository::hideAppPicker,
                    onToggleApp = DenzaAppRepository::toggleAppSelection,
                    onChooseFseApp = DenzaAppRepository::showFseInstallerPicker,
                    onCloseFseInstallerPicker = DenzaAppRepository::hideFseInstallerPicker,
                    onInstallFseApp = DenzaAppRepository::installOnPassengerScreen,
                )
            },
        )
    }

    override fun onResume() {
        super.onResume()
        NavigationTransferOverlay.setMainActivityResumed(this, true)
        DenzaAppRepository.refresh()
        DenzaAppRepository.refreshDefaultApps()
        SimulcastOverlayService.hide(this)
    }

    override fun onPause() {
        NavigationTransferOverlay.setMainActivityResumed(this, false)
        super.onPause()
        if (SimulcastIntegration.isEnabled(this) &&
            SimulcastIntegration.getLastTargetPackage(this) != null
        ) {
            SimulcastOverlayService.showActiveExit(this)
        }
    }

}
