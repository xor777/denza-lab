package dev.denza.apps.debug

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.denza.apps.DenzaUiState
import dev.denza.apps.NavigationAppChoices
import dev.denza.apps.design.DenzaColors
import dev.denza.apps.design.DenzaTheme
import dev.denza.apps.feature.navigation.NavigationAppPolicy
import dev.denza.apps.feature.navigation.NavigationPlacementPolicy
import dev.denza.apps.ui.dashboard.DashboardActions
import dev.denza.apps.ui.dashboard.FeatureSheet
import dev.denza.apps.ui.dashboard.TileId

/**
 * The «Экран водителя» panel, drawn by the app's own [FeatureSheet] over this device's own
 * applications, so a screenshot can be put beside `Config.dc.html` and `DriverScreen.dc.html`.
 *
 * ```
 * adb shell am start -n dev.denza.apps/.debug.DriverScreenSheetFixtureActivity --es choice <package>
 * ```
 *
 * The list is [NavigationAppChoices], the code the product reads the car with. Choosing on the page
 * only moves the mark here: nothing is stored and nothing reaches the task proxy. The row opens the
 * page exactly as it does in the product, so the page is one tap on the row away. Debug builds only.
 */
class DriverScreenSheetFixtureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        val initial = intent.getStringExtra(EXTRA_CHOICE) ?: NavigationAppPolicy.DASHBOARD_PACKAGE
        val app = applicationContext
        setContent {
            var selected by remember { mutableStateOf(initial) }
            val state = remember(selected) {
                val chosen = NavigationAppChoices.chosen(app, selected)
                DenzaUiState(
                    navigationAppLabel = chosen.label,
                    navigationAppChoice = chosen,
                    navigationAppChoices = NavigationAppChoices.all(app, selected),
                    navigationPlacements = NavigationPlacementPolicy.offered(selected),
                )
            }
            DenzaTheme {
                Box(Modifier.fillMaxSize().background(DenzaColors.Ground)) {
                    FeatureSheet(
                        id = TileId.CLUSTER,
                        state = state,
                        actions = actions(onSelect = { selected = it }),
                        compact = false,
                        onDismiss = {},
                    )
                }
            }
        }
    }

    private fun actions(onSelect: (String) -> Unit) = DashboardActions(
        onToggleSimulcast = {},
        onLaunchSimulcast = {},
        onRepairSimulcast = {},
        onChooseApps = {},
        onLoadAppChoices = {},
        onToggleApp = {},
        onToggleMirrors = {},
        onMirrorsPosition = {},
        onMirrorsProcessing = {},
        onPreviewMirrors = {},
        onNavigationAction = {},
        onNavigationPlacement = {},
        onNavigationSteeringWheelButton = {},
        onChooseNavigationApp = {},
        onLoadNavigationAppChoices = {},
        onSelectNavigationApp = onSelect,
        onToggleSplitScreen = {},
        onLaunchSplitScreen = {},
        onSetWeatherEnabled = {},
        onToggleHudGuidance = {},
        onToggleSpeakerCovers = {},
        onRaiseSpeakerCovers = {},
        onToggleCloudLink = {},
        onSetCloudWifiRetained = {},
        onOpenSystemLanguage = {},
        onSetDefaultAppsEnabled = {},
        onChooseFseApp = {},
        onOpenClusterPicker = {},
        onOpenService = {},
        onOpenSettings = {},
    )

    companion object {
        const val EXTRA_CHOICE = "choice"
    }
}
