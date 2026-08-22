package dev.denza.apps

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import dev.denza.apps.feature.navigation.NavigationTransferOverlay
import dev.denza.apps.feature.split.SplitScreenCoordinator
import dev.denza.apps.feature.split.SplitScreenSettings
import dev.denza.apps.ui.DenzaAppsRoot

class MainActivity : ComponentActivity() {
    private val restoreSplitOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            isEnabled = false
            if (!SplitScreenSettings.isEnabled(this@MainActivity)) {
                finish()
                return
            }
            val controlTaskId = taskId
            SplitScreenCoordinator.restorePickerSessionAfterControl(
                context = applicationContext,
                controlTaskId = controlTaskId,
            )
            // The native BYD organizer cannot rebuild a deterministic pair underneath this
            // package's full-screen control task. The coordinator waits until ActivityTaskManager
            // confirms that this exact task is gone before touching either split root.
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, restoreSplitOnBack)
        updateRestoreSplitOnBack(intent)
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
                    onNavigationAutomatic = DenzaAppRepository::setNavigationAutomatic,
                    onNavigationSteeringWheelButton =
                        DenzaAppRepository::setNavigationSteeringWheelButton,
                    onNavigationPlacement = DenzaAppRepository::setNavigationPlacement,
                    onChooseNavigationApp = DenzaAppRepository::showNavigationAppPicker,
                    onCloseNavigationPicker = DenzaAppRepository::hideNavigationAppPicker,
                    onSelectNavigationApp = DenzaAppRepository::selectNavigationApp,
                    onToggleSplitScreen = DenzaAppRepository::setSplitScreenEnabled,
                    onToggleHudGuidance = DenzaAppRepository::setHudGuidanceEnabled,
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateRestoreSplitOnBack(intent)
    }

    override fun onResume() {
        super.onResume()
        NavigationTransferOverlay.setMainActivityResumed(this, true)
        DenzaAppRepository.refresh()
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

    private fun updateRestoreSplitOnBack(intent: Intent?) {
        restoreSplitOnBack.isEnabled =
            intent?.getBooleanExtra(EXTRA_RESTORE_SPLIT_ON_BACK, false) == true
    }

    companion object {
        const val EXTRA_RESTORE_SPLIT_ON_BACK =
            "dev.denza.apps.extra.RESTORE_SPLIT_ON_BACK"
    }
}
