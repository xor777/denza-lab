package dev.denza.apps.ui.dashboard

import dev.denza.apps.DenzaUiState
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.feature.cluster.ClusterMapPlacement
import dev.denza.apps.feature.mirrors.MirrorsPosition

/**
 * Everything the dashboard can ask the runtime to do, in one parameter.
 *
 * The screen used to take twenty-nine separate lambdas and hand each one down by name, which is why
 * adding a feature meant editing a signature, a call site and an activity together. They are the
 * same callbacks; they simply travel as one thing now, so a tile and its settings sheet can be
 * handed the whole vocabulary and pick what they need.
 */
data class DashboardActions(
    val onToggleSimulcast: (Boolean) -> Unit,
    val onLaunchSimulcast: () -> Unit,
    val onRepairSimulcast: () -> Unit,
    val onChooseApps: () -> Unit,
    val onToggleApp: (String) -> Unit,
    val onToggleMirrors: (Boolean) -> Unit,
    val onMirrorsPosition: (MirrorsPosition) -> Unit,
    val onMirrorsProcessing: (Boolean) -> Unit,
    val onPreviewMirrors: () -> Unit,
    val onNavigationAction: () -> Unit,
    val onNavigationPlacement: (ClusterMapPlacement) -> Unit,
    val onNavigationSteeringWheelButton: (Boolean) -> Unit,
    val onChooseNavigationApp: () -> Unit,
    val onSelectNavigationApp: (String) -> Unit,
    val onToggleSplitScreen: (Boolean) -> Unit,
    val onLaunchSplitScreen: () -> Unit,
    val onSetWeatherEnabled: (Boolean) -> Unit,
    val onToggleHudGuidance: (Boolean) -> Unit,
    val onToggleSpeakerCovers: (Boolean) -> Unit,
    val onSetStockRussianLocale: (Boolean) -> Unit,
    val onChooseFseApp: () -> Unit,
    val onOpenClusterPicker: () -> Unit,
    val onOpenService: () -> Unit,
    val onOpenSettings: (TileId) -> Unit,
)

/**
 * Turning a press into the call it stands for.
 *
 * [DashboardTiles] decides *what* a press means and this decides *who to tell*, which keeps the
 * decision testable and the wiring dull. A toggle reads the feature's current wish and sends back
 * the opposite: the tile has no switch on its face, so the press is the switch.
 */
object DashboardPress {

    fun perform(tile: DashboardTile, state: DenzaUiState, actions: DashboardActions) {
        when (tile.action) {
            TileAction.CLUSTER_PROJECT -> actions.onNavigationAction()
            TileAction.SIMULCAST_LAUNCH -> actions.onLaunchSimulcast()
            TileAction.PASSENGER_INSTALL -> actions.onChooseFseApp()
            TileAction.SERVICE_OPEN -> actions.onOpenService()
            TileAction.SETTINGS -> actions.onOpenSettings(tile.id)
            TileAction.SPLIT_LAUNCH -> actions.onLaunchSplitScreen()
            TileAction.TOGGLE -> toggle(tile.id, state, actions)
            TileAction.RESOLVE -> resolve(tile, state, actions)
        }
    }

    private fun toggle(id: TileId, state: DenzaUiState, actions: DashboardActions) {
        when (id) {
            TileId.SIMULCAST -> actions.onToggleSimulcast(!state.simulcast.desiredEnabled)
            TileId.MIRRORS -> actions.onToggleMirrors(!state.mirrors.desiredEnabled)
            TileId.HUD -> actions.onToggleHudGuidance(!state.hudGuidance.desiredEnabled)
            TileId.SPEAKERS -> actions.onToggleSpeakerCovers(!state.speakerCovers.desiredEnabled)
            TileId.WEATHER -> actions.onSetWeatherEnabled(!state.weatherEnabled)
            // Unknown is not off: a locale nobody has read yet is asked to come on, not to stay as
            // it was, because the driver pressing this tile has said which way they want it.
            TileId.LOCALE ->
                actions.onSetStockRussianLocale(state.stockRussianLocale.enabled != true)
            // None of these is a thing that is on or off, so none can be toggled; the registry
            // never asks, and answering with their settings beats answering with nothing.
            TileId.CLUSTER, TileId.SPLIT, TileId.PASSENGER, TileId.SERVICE ->
                actions.onOpenSettings(id)
        }
    }

    /**
     * A feature waiting on the driver: send the press where the waiting actually ends.
     *
     * The three retry-shaped resolutions all mean the same thing to a tile - try again - and each
     * feature has its own way of trying, which is why this is a table and not one call.
     */
    private fun resolve(tile: DashboardTile, state: DenzaUiState, actions: DashboardActions) {
        val snapshot = snapshotOf(tile.id, state)
        when (snapshot?.let(DashboardTiles::resolutionOf)) {
            FeatureResolution.SELECT_APPS -> actions.onChooseApps()
            FeatureResolution.SELECT_NAVIGATION_APP -> actions.onChooseNavigationApp()
            FeatureResolution.SELECT_CLUSTER_DISPLAY -> actions.onOpenClusterPicker()
            FeatureResolution.CONFIRM_ON_CAR,
            FeatureResolution.ENABLE_CAR_DEBUGGING,
            FeatureResolution.RETRY,
            -> retry(tile.id, actions)
            null -> actions.onOpenSettings(tile.id)
        }
    }

    private fun retry(id: TileId, actions: DashboardActions) {
        when (id) {
            TileId.SIMULCAST -> actions.onRepairSimulcast()
            TileId.CLUSTER -> actions.onNavigationAction()
            TileId.MIRRORS -> actions.onToggleMirrors(true)
            TileId.SPLIT -> actions.onToggleSplitScreen(true)
            TileId.HUD -> actions.onToggleHudGuidance(true)
            TileId.SPEAKERS -> actions.onToggleSpeakerCovers(true)
            TileId.PASSENGER -> actions.onChooseFseApp()
            // Weather has nothing to retry: it is an alarm, not a handshake.
            TileId.LOCALE, TileId.WEATHER, TileId.SERVICE -> actions.onOpenSettings(id)
        }
    }

    /** The runtime snapshot behind a tile, or null for the tiles the runtime does not model. */
    fun snapshotOf(id: TileId, state: DenzaUiState): FeatureSnapshot? = when (id.feature) {
        FeatureId.SIMULCAST -> state.simulcast
        FeatureId.MIRRORS -> state.mirrors
        FeatureId.NAVIGATION -> state.navigation
        FeatureId.SPLIT_SCREEN -> state.splitScreen
        FeatureId.HUD_GUIDANCE -> state.hudGuidance
        FeatureId.SPEAKER_COVERS -> state.speakerCovers
        FeatureId.FSE_INSTALLER -> state.fseInstaller
        null -> null
    }
}
