package dev.denza.apps

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import dev.denza.apps.feature.cloud.CloudAppOpenPolicy
import dev.denza.apps.feature.navigation.NavigationTransferOverlay
import dev.denza.apps.ui.DenzaAppsRoot

class MainActivity : ComponentActivity() {
    companion object {
        private var previousDestroyWasConfiguration = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val explicitOpen = CloudAppOpenPolicy.handleCreate(
            hasSavedState = savedInstanceState != null,
            previousDestroyWasConfiguration = previousDestroyWasConfiguration,
        )
        previousDestroyWasConfiguration = false
        WindowCompat.setDecorFitsSystemWindows(window, false)
        DenzaAppRepository.initialize(this)
        if (explicitOpen) DenzaAppRepository.explicitCloudAppOpened()
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
                    onLoadNavigationAppChoices = DenzaAppRepository::refreshNavigationAppChoices,
                    onCloseNavigationPicker = DenzaAppRepository::hideNavigationAppPicker,
                    onSelectNavigationApp = DenzaAppRepository::selectNavigationApp,
                    onToggleSplitScreen = DenzaAppRepository::setSplitScreenEnabled,
                    onLaunchSplitScreen = DenzaAppRepository::launchSplitScreen,
                    onSetWeatherEnabled = DenzaAppRepository::setWeatherEnabled,
                    onToggleHudGuidance = DenzaAppRepository::setHudGuidanceEnabled,
                    onToggleSpeakerCovers = DenzaAppRepository::setSpeakerCoversEnabled,
                    onRaiseSpeakerCovers = DenzaAppRepository::raiseSpeakerCovers,
                    onToggleCloudLink = DenzaAppRepository::setCloudLinkEnabled,
                    onSelectCloudMode = DenzaAppRepository::setCloudMode,
                    onSaveCloudIdentity = DenzaAppRepository::saveCloudIdentity,
                    onRegenerateCloudIdentity = DenzaAppRepository::regenerateCloudIdentity,
                    onSetCloudWifiRetained = DenzaAppRepository::setCloudWifiRetained,
                    onSetCloudReportExport = DenzaAppRepository::setCloudReportExport,
                    onSelectClusterDisplay = DenzaAppRepository::selectClusterDisplay,
                    onRefreshScreenDiagnostics = DenzaAppRepository::refreshScreenDiagnostics,
                    onCheckAdbAccess = DenzaAppRepository::checkAdbAccess,
                    onRequestAdbAuthorizationOnce =
                        DenzaAppRepository::requestAdbAuthorizationOnce,
                    onAllowNewAdbAuthorizationAttempt =
                        DenzaAppRepository::allowNewAdbAuthorizationAttempt,
                    onRefreshSystemLanguage =
                        DenzaAppRepository::refreshSystemLanguage,
                    onOpenSystemLanguage =
                        DenzaAppRepository::openSystemLanguage,
                    onRefreshDefaultApps = DenzaAppRepository::refreshDefaultApps,
                    onSetDefaultAppsEnabled =
                        DenzaAppRepository::setDefaultAppsEnabled,
                    onSelectDefaultApp = DenzaAppRepository::selectDefaultApp,
                    onChooseApps = DenzaAppRepository::showAppPicker,
                    onLoadAppChoices = DenzaAppRepository::refreshAppChoices,
                    onCloseAppPicker = DenzaAppRepository::hideAppPicker,
                    onToggleApp = DenzaAppRepository::toggleAppSelection,
                    onChooseFseApp = DenzaAppRepository::showFseInstallerPicker,
                    onCloseFseInstallerPicker = DenzaAppRepository::hideFseInstallerPicker,
                    onInstallFseApp = DenzaAppRepository::installOnPassengerScreen,
                )
            },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask delivers an explicit launcher reopen here, without onCreate.
        DenzaAppRepository.explicitCloudAppOpened()
    }

    override fun onResume() {
        super.onResume()
        NavigationTransferOverlay.setMainActivityResumed(this, true)
        DenzaAppRepository.refresh()
        DenzaAppRepository.refreshDefaultApps()
        DenzaAppRepository.refreshCloudLink()
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

    override fun onDestroy() {
        previousDestroyWasConfiguration = isChangingConfigurations
        super.onDestroy()
    }

}
