package dev.denza.apps.ui.dashboard

import dev.denza.apps.DenzaUiState
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.feature.cluster.ClusterMapPlacement
import dev.denza.apps.feature.defaultapps.DefaultAppRole
import dev.denza.apps.feature.mirrors.MirrorsPosition

/**
 * Everything the screen can ask the app to do, as one object.
 *
 * `MainActivity` builds it once, over the repository and the features' runtimes, and hands it to
 * `DenzaAppsRoot` whole. It used to be forty-two lambdas threaded one by one through the activity,
 * the root's signature, a `remember` keyed on thirty-one of them and the constructor of
 * [DashboardActions] - five edits for every new callback, and a key left out of the `remember`
 * would have handed the screen a stale one without a word from the compiler. A new action is now a
 * member here and its answer in the activity, and the compiler asks for both.
 *
 * Each member is a value, not a function, so a panel handed `actions.onToggleMirrors` is handed the
 * same object on every recomposition and is skipped when nothing else changed. An implementation
 * keeps them that way: stored once, never built in a getter ([IdleActions], `AppActions`).
 *
 * What only opens a window of this screen - a panel, a chooser, «Сервис» - is not here: the root
 * opens those itself ([DashboardActions]).
 */
interface DenzaActions {
    // «Трансляция»
    val onToggleSimulcast: (Boolean) -> Unit
    val onLaunchSimulcast: () -> Unit
    val onRepairSimulcast: () -> Unit

    /** Read the car's applications for «Что транслировать» as a chooser of them opens. */
    val onLoadAppChoices: () -> Unit
    val onToggleApp: (String) -> Unit

    // «Зеркала»
    val onToggleMirrors: (Boolean) -> Unit
    val onMirrorsPosition: (MirrorsPosition) -> Unit
    val onMirrorsProcessing: (Boolean) -> Unit
    val onPreviewMirrors: () -> Unit

    // «Экран водителя»
    val onNavigationAction: () -> Unit
    val onNavigationPlacement: (ClusterMapPlacement) -> Unit
    val onNavigationSteeringWheelButton: (Boolean) -> Unit

    /** Read the car for «Что показывать» as a chooser of it opens. */
    val onLoadNavigationAppChoices: () -> Unit

    /**
     * One answer chosen. True when it was taken to be carried out, and a window showing the choice
     * closes; false when the car no longer offers it or nothing has started to carry it out yet, and
     * the window stays. Taken is not done: a choice arriving while a projection or a return is in
     * flight is refused afterwards, and the tile keeps the choice it had.
     */
    val onSelectNavigationApp: (String) -> Boolean

    /** The instruments' screen chosen by hand, or null to let the app decide again. */
    val onSelectClusterDisplay: (Int?) -> Unit

    /** Read the car's displays again, and nothing else: the instruments' picker while it waits. */
    val onSearchClusterDisplays: () -> Unit

    // «Разделение»
    val onToggleSplitScreen: (Boolean) -> Unit
    val onLaunchSplitScreen: () -> Unit

    // «Погода»
    val onSetWeatherEnabled: (Boolean) -> Unit

    // «HUD Подсказки»
    val onToggleHudGuidance: (Boolean) -> Unit

    // «Динамики»
    val onToggleSpeakerCovers: (Boolean) -> Unit
    val onRaiseSpeakerCovers: () -> Unit

    // «Облако»
    val onToggleCloudLink: (Boolean) -> Unit

    /** Keep client Wi-Fi on while the car sleeps; the car's own setting, read back from it. */
    val onSetCloudWifiRetained: (Boolean) -> Unit

    // «Язык системы»
    val onRefreshSystemLanguage: () -> Unit

    /** Hand the language over to the car's own list; this app does not set it itself. */
    val onOpenSystemLanguage: () -> Unit

    // «Shortcuts»
    /** Read the roles again; `true` also drops the launcher catalog the panel was drawn from. */
    val onRefreshDefaultApps: (force: Boolean) -> Unit
    val onSetDefaultAppsEnabled: (Boolean) -> Unit
    val onSelectDefaultApp: (DefaultAppRole, String) -> Unit

    // «Экран справа»
    /** Read what can go to the passenger's screen; false while an install runs - nothing opens. */
    val onLoadFseApps: () -> Boolean

    /** One application pressed; true when its install has started and the chooser closes. */
    val onInstallFseApp: (String) -> Boolean

    // «Сервис» and the car's ADB access
    /** What «Сервис» reads as it opens: DiShare's screens and the car's displays. */
    val onRefreshScreenDiagnostics: () -> Unit

    /** «Сервис» stands on a started screen, or does not: its report is built only while it does. */
    val onServiceReportVisible: (Boolean) -> Unit

    /** «Сервис» has opened: the automatic ADB restore takes the moment to look again. */
    val onServiceOpened: () -> Unit
    val onSetAdbRestoreEnabled: (Boolean) -> Unit

    /** Look at the car's ADB access, for the gate and «Сервис»; the gate follows the answer. */
    val onCheckAdbAccess: () -> Unit

    /**
     * A tile's press on «Нет доступа»: look at the car's ADB access without asking it for anything,
     * and run `onTrusted` if the car still trusts this app. If it does not, the startup gate comes
     * up by itself.
     */
    val onCheckAdbAccessThen: (onTrusted: () -> Unit) -> Unit
    val onRequestAdbAuthorizationOnce: () -> Unit
    val onAllowNewAdbAuthorizationAttempt: () -> Unit
}

/**
 * An app that answers every action by doing nothing - and, asked to check its access, finds a car
 * that trusts it. What the debug build's fixture screens and the tests stand on; each overrides
 * the few actions it watches. Never the product's: `MainActivity` answers every one.
 */
open class IdleActions : DenzaActions {
    override val onToggleSimulcast: (Boolean) -> Unit = {}
    override val onLaunchSimulcast: () -> Unit = {}
    override val onRepairSimulcast: () -> Unit = {}
    override val onLoadAppChoices: () -> Unit = {}
    override val onToggleApp: (String) -> Unit = {}
    override val onToggleMirrors: (Boolean) -> Unit = {}
    override val onMirrorsPosition: (MirrorsPosition) -> Unit = {}
    override val onMirrorsProcessing: (Boolean) -> Unit = {}
    override val onPreviewMirrors: () -> Unit = {}
    override val onNavigationAction: () -> Unit = {}
    override val onNavigationPlacement: (ClusterMapPlacement) -> Unit = {}
    override val onNavigationSteeringWheelButton: (Boolean) -> Unit = {}
    override val onLoadNavigationAppChoices: () -> Unit = {}
    override val onSelectNavigationApp: (String) -> Boolean = { false }
    override val onSelectClusterDisplay: (Int?) -> Unit = {}
    override val onSearchClusterDisplays: () -> Unit = {}
    override val onToggleSplitScreen: (Boolean) -> Unit = {}
    override val onLaunchSplitScreen: () -> Unit = {}
    override val onSetWeatherEnabled: (Boolean) -> Unit = {}
    override val onToggleHudGuidance: (Boolean) -> Unit = {}
    override val onToggleSpeakerCovers: (Boolean) -> Unit = {}
    override val onRaiseSpeakerCovers: () -> Unit = {}
    override val onToggleCloudLink: (Boolean) -> Unit = {}
    override val onSetCloudWifiRetained: (Boolean) -> Unit = {}
    override val onRefreshSystemLanguage: () -> Unit = {}
    override val onOpenSystemLanguage: () -> Unit = {}
    override val onRefreshDefaultApps: (Boolean) -> Unit = {}
    override val onSetDefaultAppsEnabled: (Boolean) -> Unit = {}
    override val onSelectDefaultApp: (DefaultAppRole, String) -> Unit = { _, _ -> }
    override val onLoadFseApps: () -> Boolean = { false }
    override val onInstallFseApp: (String) -> Boolean = { false }
    override val onRefreshScreenDiagnostics: () -> Unit = {}
    override val onServiceReportVisible: (Boolean) -> Unit = {}
    override val onServiceOpened: () -> Unit = {}
    override val onSetAdbRestoreEnabled: (Boolean) -> Unit = {}
    override val onCheckAdbAccess: () -> Unit = {}
    override val onCheckAdbAccessThen: (() -> Unit) -> Unit = { onTrusted -> onTrusted() }
    override val onRequestAdbAuthorizationOnce: () -> Unit = {}
    override val onAllowNewAdbAuthorizationAttempt: () -> Unit = {}
}

/**
 * What a press on the dashboard can do: everything the app does ([DenzaActions], delegated to
 * [app]), and the windows of this screen the root opens itself - a panel, a chooser, «Сервис».
 *
 * Built once by `DenzaAppsRoot` and remembered with the one object it wraps, so a tile and its
 * panel are handed the same vocabulary on every recomposition.
 */
class DashboardActions(
    app: DenzaActions,
    /** «Что транслировать» as a window of its own: a tile waiting on the choice. */
    val onChooseApps: () -> Unit,
    /** «Что показывать» as a window of its own: a tile waiting on the choice. */
    val onChooseNavigationApp: () -> Unit,
    /** «Экран справа»'s chooser, which both gestures on its tile open. */
    val onChooseFseApp: () -> Unit,
    val onOpenClusterPicker: () -> Unit,
    val onOpenService: () -> Unit,
    val onOpenSettings: (TileId) -> Unit,
) : DenzaActions by app

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
     * Each feature has its own way of trying again, which is why that is a table and not one call.
     * A channel that failed is looked at first: retrying over a car that no longer trusts the app
     * only fails the same way, while the gate that comes up is a choice the driver can act on.
     */
    private fun resolve(tile: DashboardTile, state: DenzaUiState, actions: DashboardActions) {
        val snapshot = snapshotOf(tile.id, state)
        when (snapshot?.let(DashboardTiles::resolutionOf)) {
            FeatureResolution.SELECT_APPS -> actions.onChooseApps()
            FeatureResolution.SELECT_NAVIGATION_APP -> actions.onChooseNavigationApp()
            FeatureResolution.SELECT_CLUSTER_DISPLAY -> actions.onOpenClusterPicker()
            FeatureResolution.CHECK_ACCESS -> actions.onCheckAdbAccessThen { retry(tile.id, actions) }
            FeatureResolution.RETRY -> retry(tile.id, actions)
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
