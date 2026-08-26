package dev.denza.apps.ui.dashboard

import dev.denza.apps.DenzaUiState
import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.ui.components.DenzaTileCaption
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
    WEATHER,
    SPEAKER,
    LOCALE,
    PASSENGER,
    SERVICE,
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

    /** Split the screen now - the same entry the launcher icon opens. */
    SPLIT_LAUNCH,

    /** Choose an application to put on the passenger screen. */
    PASSENGER_INSTALL,

    /** Open service: the car's own readings, the app's access, the stock settings it reaches. */
    SERVICE_OPEN,

    /** It cannot go until the driver picks something; the press opens that choice. */
    RESOLVE,

    /** Nothing can be done from the face of the tile; the press opens its settings. */
    SETTINGS,
}

/** One tile, fully decided, with nothing left for the screen to work out. */
data class DashboardTile(
    val id: TileId,
    val icon: TileIcon,
    val name: String,
    val state: String,
    val tone: DenzaTileTone,
    val caption: DenzaTileCaption,
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
 * Three rules run through every caption below. The line under the name says what is **configured**,
 * not what state the feature is in - "6 приложений", never "включено" - because the name already
 * says which feature it is and the tone already says whether it is working. It takes the accent
 * only when it is a reading rather than a setting; see [DenzaTileCaption]. And nothing here repeats
 * what the car itself shows a few centimetres away.
 */
object DashboardTiles {

    /**
     * Every tile on the main screen, in the order `Config.dc.html` places them.
     *
     * Ten, and the board draws the same ten. Weather was the one that used to be missing: the
     * adapter ran unconditionally with nothing to switch, so a tile for it would have been inert.
     * It has a switch now, so it has a tile.
     */
    fun of(state: DenzaUiState): List<DashboardTile> = listOf(
        cluster(state),
        simulcast(state),
        mirrors(state),
        split(state),
        hud(state),
        weather(state),
        speakers(state),
        locale(state),
        passenger(state),
        service(),
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
        val waiting = snapshot.status == FeatureStatus.NEEDS_ACTION && snapshot.message.isNotBlank()
        return DashboardTile(
            id = TileId.CLUSTER,
            icon = TileIcon.CLUSTER,
            name = "Экран водителя",
            state = when {
                waiting -> snapshot.message
                projected -> "${state.navigationAppLabel} · на экране"
                else -> state.navigationAppLabel
            },
            tone = toneOf(snapshot),
            // On the cluster is a reading; merely chosen for it is a setting.
            caption = if (projected) DenzaTileCaption.READING else DenzaTileCaption.SETTING,
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
            id = TileId.SIMULCAST,
            icon = TileIcon.SIMULCAST,
            name = "Трансляция",
            state = when {
                snapshot.status == FeatureStatus.NEEDS_ACTION && snapshot.message.isNotBlank() ->
                    snapshot.message
                state.selectedAppCount == 0 -> "Выберите приложения"
                else -> applications(state.selectedAppCount)
            },
            tone = toneOf(snapshot),
            // A count of chosen applications is as true stopped as running.
            caption = DenzaTileCaption.SETTING,
            action = actionOf(snapshot, ready),
        )
    }

    /** The turn-indicator cameras. A watcher, so its main action is simply whether it watches. */
    private fun mirrors(state: DenzaUiState): DashboardTile {
        val snapshot = state.mirrors
        val watching = snapshot.desiredEnabled
        val waiting = snapshot.status == FeatureStatus.NEEDS_ACTION && snapshot.message.isNotBlank()
        return DashboardTile(
            id = TileId.MIRRORS,
            icon = TileIcon.MIRRORS,
            name = "Зеркала",
            state = when {
                waiting -> snapshot.message
                watching -> "Следят за поворотниками"
                else -> "Не следят"
            },
            tone = toneOf(snapshot),
            caption = if (watching && !waiting) DenzaTileCaption.READING else DenzaTileCaption.SETTING,
            action = actionOf(snapshot, TileAction.TOGGLE),
        )
    }

    /**
     * Split screen.
     *
     * The press splits the screen, by the same entry the launcher icon opens - which is what
     * somebody reaching for a tile called "Разделение экрана" is after. Showing and hiding that
     * icon is housekeeping and lives behind the long press.
     *
     * Its caption never takes the accent: "the icon is on the desktop" is a setting, and it is not
     * a reading of anything the feature is doing.
     */
    private fun split(state: DenzaUiState): DashboardTile {
        val snapshot = state.splitScreen
        return DashboardTile(
            id = TileId.SPLIT,
            icon = TileIcon.SPLIT,
            name = "Разделение экрана",
            state = when {
                snapshot.status == FeatureStatus.NEEDS_ACTION && snapshot.message.isNotBlank() ->
                    snapshot.message
                snapshot.desiredEnabled -> "Значок на рабочем столе"
                else -> "Значок скрыт"
            },
            tone = toneOf(snapshot),
            caption = DenzaTileCaption.SETTING,
            action = actionOf(snapshot, TileAction.SPLIT_LAUNCH),
        )
    }

    /** Navigation hints repeated onto the head-up display. */
    private fun hud(state: DenzaUiState): DashboardTile {
        val snapshot = state.hudGuidance
        val showing = snapshot.desiredEnabled
        val waiting = snapshot.status == FeatureStatus.NEEDS_ACTION && snapshot.message.isNotBlank()
        return DashboardTile(
            id = TileId.HUD,
            icon = TileIcon.HUD,
            name = "Подсказки на HUD",
            state = when {
                waiting -> snapshot.message
                showing -> "Указания на проекции"
                else -> "Не показываются"
            },
            tone = toneOf(snapshot),
            caption = if (showing && !waiting) DenzaTileCaption.READING else DenzaTileCaption.SETTING,
            action = actionOf(snapshot, TileAction.TOGGLE),
        )
    }

    /**
     * Weather supplied to the car's own widget.
     *
     * Nothing of ours draws a forecast: the app fetches one and hands it to the stock widget, so
     * the only thing there is to decide is whether it keeps doing that. No coordinator, no
     * handshake - an alarm either stands or it does not, which is why this tile reads its state
     * straight rather than through a snapshot.
     */
    private fun weather(state: DenzaUiState): DashboardTile = DashboardTile(
        id = TileId.WEATHER,
        icon = TileIcon.WEATHER,
        name = "Погода",
        state = if (state.weatherEnabled) "Данные для виджета" else "Данные не уходят",
        tone = if (state.weatherEnabled) DenzaTileTone.LIVE else DenzaTileTone.IDLE,
        caption = DenzaTileCaption.SETTING,
        action = TileAction.TOGGLE,
    )

    /** Motorised speaker covers, driven by app, MediaSession and output-mix signals. */
    private fun speakers(state: DenzaUiState): DashboardTile {
        val snapshot = state.speakerCovers
        val waiting = snapshot.status == FeatureStatus.NEEDS_ACTION && snapshot.message.isNotBlank()
        return DashboardTile(
            id = TileId.SPEAKERS,
            icon = TileIcon.SPEAKER,
            name = "Динамики",
            state = when {
                waiting -> snapshot.message
                snapshot.desiredEnabled -> "Автоматика по звуку"
                else -> "Автоматика выключена"
            },
            tone = toneOf(snapshot),
            caption = DenzaTileCaption.SETTING,
            action = actionOf(snapshot, TileAction.TOGGLE),
        )
    }
    /**
     * Russian inside the car's own settings, which is a switch in stock firmware rather than
     * anything this app draws.
     */
    private fun locale(state: DenzaUiState): DashboardTile {
        val snapshot = state.stockRussianLocale
        return DashboardTile(
            id = TileId.LOCALE,
            icon = TileIcon.LOCALE,
            name = "Русский в настройках",
            state = when (snapshot.enabled) {
                true -> "Включён"
                false -> "Выключен"
                null -> "Не проверено"
            },
            tone = when {
                snapshot.running -> DenzaTileTone.WORKING
                snapshot.enabled == true -> DenzaTileTone.LIVE
                else -> DenzaTileTone.IDLE
            },
            caption = DenzaTileCaption.SETTING,
            action = TileAction.TOGGLE,
        )
    }

    /**
     * The passenger's screen. Its main action is the choice itself - there is nothing to switch on,
     * only an application to put over there - so the press opens the list.
     */
    private fun passenger(state: DenzaUiState): DashboardTile {
        val snapshot = state.fseInstaller
        return DashboardTile(
            id = TileId.PASSENGER,
            icon = TileIcon.PASSENGER,
            name = "Пассажирский экран",
            state = snapshot.message.ifBlank { "Установить приложение" },
            tone = toneOf(snapshot),
            caption = DenzaTileCaption.SETTING,
            action = actionOf(snapshot, TileAction.PASSENGER_INSTALL),
        )
    }

    /**
     * Service: the car's own readings, this app's access to it, and what it can reach in the stock
     * settings.
     *
     * It is a door and not a feature, so it wears the quiet surface always - there is nothing about
     * it to be on or off. It exists because the diagnostics used to be behind seven taps on an
     * undisclosed part of the screen, which is a door too, just one nobody can find and anybody can
     * open by accident.
     */
    private fun service(): DashboardTile = DashboardTile(
        id = TileId.SERVICE,
        icon = TileIcon.SERVICE,
        name = "Сервис",
        state = "Всё в норме",
        tone = DenzaTileTone.IDLE,
        caption = DenzaTileCaption.SETTING,
        action = TileAction.SERVICE_OPEN,
    )

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
