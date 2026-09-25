package dev.denza.apps.ui.dashboard

import dev.denza.apps.DenzaUiState
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
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
    /** Read the car's applications for a chooser that is already open, without opening one. */
    val onLoadAppChoices: () -> Unit,
    val onToggleApp: (String) -> Unit,
    val onToggleMirrors: (Boolean) -> Unit,
    val onMirrorsPosition: (MirrorsPosition) -> Unit,
    val onMirrorsProcessing: (Boolean) -> Unit,
    val onPreviewMirrors: () -> Unit,
    val onNavigationAction: () -> Unit,
    val onNavigationPlacement: (ClusterMapPlacement) -> Unit,
    val onNavigationSteeringWheelButton: (Boolean) -> Unit,
    val onChooseNavigationApp: () -> Unit,
    /** Read the car for «Что показывать» when the panel's own page opens, without opening a window. */
    val onLoadNavigationAppChoices: () -> Unit,
    val onSelectNavigationApp: (String) -> Unit,
    val onToggleSplitScreen: (Boolean) -> Unit,
    val onLaunchSplitScreen: () -> Unit,
    val onSetWeatherEnabled: (Boolean) -> Unit,
    val onToggleHudGuidance: (Boolean) -> Unit,
    val onToggleSpeakerCovers: (Boolean) -> Unit,
    val onRaiseSpeakerCovers: () -> Unit,
    val onToggleCloudLink: (Boolean) -> Unit,
    /** Keep client Wi-Fi on while the car sleeps; the car's own setting, read back from it. */
    val onSetCloudWifiRetained: (Boolean) -> Unit,
    /** Hand the language over to the car's own list; this app does not set it itself. */
    val onOpenSystemLanguage: () -> Unit,
    val onSetDefaultAppsEnabled: (Boolean) -> Unit,
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
        // The settings switches already do this. The tile must not queue another request
        // against the old desired value while its current write is still being persisted.
        if (tile.id == TileId.CLOUD && state.cloudLinkBusy && tile.action != TileAction.SETTINGS) return
        when (tile.action) {
            TileAction.CLUSTER_PROJECT -> actions.onNavigationAction()
            TileAction.SIMULCAST_LAUNCH -> actions.onLaunchSimulcast()
            TileAction.PASSENGER_INSTALL -> actions.onChooseFseApp()
            TileAction.LANGUAGE_PICK -> actions.onOpenSystemLanguage()
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
            // A press the car refused is asked again, not reversed. The tile says «Не включилось»
            // over a wish that is still on, and a press that answered it by switching off would do
            // the opposite of what its own words offer.
            TileId.CLOUD -> actions.onToggleCloudLink(
                if (state.cloudLink.status == FeatureStatus.ERROR) {
                    state.cloudLink.desiredEnabled
                } else {
                    !state.cloudLink.desiredEnabled
                },
            )
            TileId.WEATHER -> actions.onSetWeatherEnabled(!state.weatherEnabled)
            TileId.DEFAULT_APPS ->
                actions.onSetDefaultAppsEnabled(!state.defaultApps.substituting)
            // None of these is a thing that is on or off, so none can be toggled; the registry
            // never asks, and answering with their settings beats answering with nothing.
            TileId.CLUSTER, TileId.SPLIT, TileId.PASSENGER, TileId.SERVICE, TileId.LOCALE ->
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
            TileId.CLOUD -> actions.onToggleCloudLink(true)
            TileId.PASSENGER -> actions.onChooseFseApp()
            // Weather has nothing to retry: it is an alarm, not a handshake. Nor has the
            // language: the car's list cannot refuse and so never asks to be tried again.
            TileId.LOCALE, TileId.WEATHER, TileId.DEFAULT_APPS, TileId.SERVICE ->
                actions.onOpenSettings(id)
        }
    }

    /**
     * What a tile's feature has to say for itself, for the ten that have a snapshot and the one
     * that does not.
     *
     * The language has no [FeatureSnapshot] and never will - inventing a [FeatureId] for it is
     * exactly the fake feature [TileId] exists to avoid. It used to need a line here anyway,
     * because the per-application override could be refused and the refusal had nowhere else to
     * go. Opening the car's own list cannot be refused, so it has nothing to report and says
     * nothing.
     */
    fun messageOf(id: TileId, state: DenzaUiState): String =
        snapshotOf(id, state)?.message.orEmpty()

    /** The runtime snapshot behind a tile, or null for the tiles the runtime does not model. */
    fun snapshotOf(id: TileId, state: DenzaUiState): FeatureSnapshot? = when (id.feature) {
        FeatureId.SIMULCAST -> state.simulcast
        FeatureId.MIRRORS -> state.mirrors
        FeatureId.NAVIGATION -> state.navigation
        FeatureId.SPLIT_SCREEN -> state.splitScreen
        FeatureId.HUD_GUIDANCE -> state.hudGuidance
        FeatureId.SPEAKER_COVERS -> state.speakerCovers
        FeatureId.FSE_INSTALLER -> state.fseInstaller
        FeatureId.CLOUD_LINK -> state.cloudLink
        null -> null
    }
}
