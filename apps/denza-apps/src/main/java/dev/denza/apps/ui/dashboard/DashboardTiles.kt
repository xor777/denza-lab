package dev.denza.apps.ui.dashboard

import dev.denza.apps.DenzaUiState
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.ui.components.DenzaTileTone

/**
 * The icon a tile wears, as a closed vocabulary rather than a drawable.
 *
 * The registry stays free of Compose on purpose - what a tile says and how it reads are policy, and
 * policy that needs a Compose runtime to test stops being tested. The screen turns these into
 * vectors and that is the only place that knows what a vector is.
 */
enum class TileIcon {
    CLUSTER,
    SIMULCAST,
    MIRRORS,
    SPLIT,
    HUD,
    PASSENGER,
}

/**
 * What a short press on a tile does.
 *
 * Naming the actions rather than passing lambdas through the registry is what makes the choice
 * testable: "pressing Projection while it is off turns it on rather than trying to start it" is a
 * product decision, and it should fail a test when someone changes it by accident.
 */
enum class TileAction {

    /** Put what is chosen onto the driver's cluster, or take it off again. */
    CLUSTER_PROJECT,

    /** Start the projection on the screens it is set up for. */
    SIMULCAST_LAUNCH,

    /** Turn the feature on or off. For a watcher, that is the whole of it. */
    TOGGLE,

    /** Choose an application to put on the passenger screen. */
    PASSENGER_INSTALL,

    /** It cannot go until the driver picks something; the press opens that choice. */
    RESOLVE,

    /** Nothing can be done from the face of the tile; the press opens its settings. */
    SETTINGS,
}

/** One tile, fully decided, with nothing left for the screen to work out. */
data class DashboardTile(
    val id: FeatureId,
    val icon: TileIcon,
    val name: String,
    val state: String,
    val tone: DenzaTileTone,
    val action: TileAction,
)

/**
 * The dashboard, as data.
 *
 * This is the register of features the app never had. Adding a feature used to mean touching three
 * files and inventing a card shape for it: a signature in the screen, a lambda in the activity, and
 * a settings object of its own - twenty-nine lambdas by the end, and three card shapes that decided
 * a feature's apparent importance by which one it happened to get.
 *
 * Here a feature is a row in one list. Its name, the line under it, how it reads at a glance and
 * what pressing it does are all decided in one place and all testable without a screen.
 *
 * Two rules run through every caption below. The line under the name says what is **configured**,
 * not what state the feature is in - "6 applications", never "enabled" - because the name already
 * says which feature it is and the tone already says whether it is working. And nothing here
 * repeats what the car itself shows a few centimetres away.
 */
object DashboardTiles {

    /** Every tile on the main screen, in the order the design boards place them. */
    fun of(state: DenzaUiState): List<DashboardTile> = listOf(
        cluster(state),
        simulcast(state),
        mirrors(state),
        split(state),
        hud(state),
        passenger(state),
    )

    /**
     * How a feature's state reads before any word on it is read.
     *
     * A feature that is off is not a problem and must not look like one: the old screen painted a
     * disabled card in the same muted grey as a broken one, so "I turned that off" and "that
     * failed" were the same picture. Amber is a decision waiting for the driver, coral is broken,
     * and neither is spent on anything else.
     */
    fun toneOf(snapshot: FeatureSnapshot): DenzaTileTone = when (snapshot.status) {
        FeatureStatus.OFF -> DenzaTileTone.IDLE
        FeatureStatus.STARTING, FeatureStatus.RECOVERING -> DenzaTileTone.WORKING
        FeatureStatus.READY -> if (snapshot.desiredEnabled) DenzaTileTone.LIVE else DenzaTileTone.IDLE
        FeatureStatus.ACTIVE -> DenzaTileTone.LIVE
        FeatureStatus.NEEDS_ACTION -> DenzaTileTone.ATTENTION
        FeatureStatus.UNAVAILABLE, FeatureStatus.ERROR -> DenzaTileTone.BROKEN
    }

    /**
     * What the press does, given where the feature has got to.
     *
     * A feature waiting on a choice answers the press with that choice rather than with its main
     * action, because the main action would only fail and put the same words back on the tile. A
     * feature this car does not have answers with its settings, which is the only place able to say
     * why.
     */
    private fun actionOf(snapshot: FeatureSnapshot, whenReady: TileAction): TileAction = when {
        snapshot.status == FeatureStatus.UNAVAILABLE -> TileAction.SETTINGS
        snapshot.status == FeatureStatus.NEEDS_ACTION && snapshot.resolution != null ->
            TileAction.RESOLVE
        else -> whenReady
    }

    /**
     * The driver's own screen: what is on it, or what would go on it.
     *
     * Named for the screen rather than for navigation because it is no longer only maps - our own
     * instruments are one of the choices - and a tile named "Navigation" showing "Instruments" is a
     * tile arguing with itself.
     */
    private fun cluster(state: DenzaUiState): DashboardTile {
        val snapshot = state.navigation
        val projected = snapshot.status == FeatureStatus.ACTIVE
        return DashboardTile(
            id = FeatureId.NAVIGATION,
            icon = TileIcon.CLUSTER,
            name = "Экран водителя",
            state = when {
                snapshot.status == FeatureStatus.NEEDS_ACTION && snapshot.message.isNotBlank() ->
                    snapshot.message
                projected -> "${state.navigationAppLabel} · на экране"
                else -> state.navigationAppLabel
            },
            tone = toneOf(snapshot),
            action = actionOf(snapshot, TileAction.CLUSTER_PROJECT),
        )
    }

    /**
     * Projection: how many applications are set up to go to the screens.
     *
     * Pressing it while it is switched off turns it on rather than trying to start it. Asking a
     * disabled feature to run and watching it refuse is a dead end the driver has to read their way
     * out of, and the tile has one press to spend.
     */
    private fun simulcast(state: DenzaUiState): DashboardTile {
        val snapshot = state.simulcast
        val ready = if (snapshot.desiredEnabled) TileAction.SIMULCAST_LAUNCH else TileAction.TOGGLE
        return DashboardTile(
            id = FeatureId.SIMULCAST,
            icon = TileIcon.SIMULCAST,
            name = "Трансляция",
            state = when {
                snapshot.status == FeatureStatus.NEEDS_ACTION && snapshot.message.isNotBlank() ->
                    snapshot.message
                state.selectedAppCount == 0 -> "Выберите приложения"
                else -> applications(state.selectedAppCount)
            },
            tone = toneOf(snapshot),
            action = actionOf(snapshot, ready),
        )
    }

    /** The turn-indicator cameras. A watcher, so its main action is simply whether it watches. */
    private fun mirrors(state: DenzaUiState): DashboardTile {
        val snapshot = state.mirrors
        return DashboardTile(
            id = FeatureId.MIRRORS,
            icon = TileIcon.MIRRORS,
            name = "Зеркала",
            state = when {
                snapshot.status == FeatureStatus.NEEDS_ACTION && snapshot.message.isNotBlank() ->
                    snapshot.message
                snapshot.desiredEnabled -> "Следят за поворотниками"
                else -> "Не следят"
            },
            tone = toneOf(snapshot),
            action = actionOf(snapshot, TileAction.TOGGLE),
        )
    }

    /** Split screen, which is one launcher icon either present or absent. */
    private fun split(state: DenzaUiState): DashboardTile {
        val snapshot = state.splitScreen
        return DashboardTile(
            id = FeatureId.SPLIT_SCREEN,
            icon = TileIcon.SPLIT,
            name = "Разделение экрана",
            state = when {
                snapshot.status == FeatureStatus.NEEDS_ACTION && snapshot.message.isNotBlank() ->
                    snapshot.message
                snapshot.desiredEnabled -> "Значок на рабочем столе"
                else -> "Значок скрыт"
            },
            tone = toneOf(snapshot),
            action = actionOf(snapshot, TileAction.TOGGLE),
        )
    }

    /** Navigation hints repeated onto the head-up display. */
    private fun hud(state: DenzaUiState): DashboardTile {
        val snapshot = state.hudGuidance
        return DashboardTile(
            id = FeatureId.HUD_GUIDANCE,
            icon = TileIcon.HUD,
            name = "Подсказки на HUD",
            state = when {
                snapshot.status == FeatureStatus.NEEDS_ACTION && snapshot.message.isNotBlank() ->
                    snapshot.message
                snapshot.desiredEnabled -> "Указания на проекции"
                else -> "Не показываются"
            },
            tone = toneOf(snapshot),
            action = actionOf(snapshot, TileAction.TOGGLE),
        )
    }

    /**
     * The passenger's screen. Its main action is the choice itself - there is nothing to switch on,
     * only an application to put over there - so the press opens the list.
     */
    private fun passenger(state: DenzaUiState): DashboardTile {
        val snapshot = state.fseInstaller
        return DashboardTile(
            id = FeatureId.FSE_INSTALLER,
            icon = TileIcon.PASSENGER,
            name = "Пассажирский экран",
            state = snapshot.message.ifBlank { "Установить приложение" },
            tone = toneOf(snapshot),
            action = actionOf(snapshot, TileAction.PASSENGER_INSTALL),
        )
    }

    /**
     * "1 приложение", "3 приложения", "6 приложений".
     *
     * Russian agrees the noun with the last digit and then makes an exception of the teens, so
     * eleven takes the same form as five and twenty-one the same as one. Getting this wrong is the
     * kind of thing nobody reports and everybody notices.
     */
    fun applications(count: Int): String {
        val tail = count % 100
        val last = count % 10
        val word = when {
            tail in 11..14 -> "приложений"
            last == 1 -> "приложение"
            last in 2..4 -> "приложения"
            else -> "приложений"
        }
        return "$count $word"
    }

    /** The resolution a waiting feature is waiting on, for the screen to open the right chooser. */
    fun resolutionOf(snapshot: FeatureSnapshot): FeatureResolution? =
        snapshot.resolution.takeIf { snapshot.status == FeatureStatus.NEEDS_ACTION }
}
