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
    DEFAULT_APPS,
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
 * One rule outranks every other here: **a name is one line and a caption is one line, always.**
 * The tile stacks them from the bottom edge, so a caption that grows to two lines pushes the name up
 * - and since captions used to change length with state ("Следят за поворотниками" against "Не
 * следят"), switching a feature on made its name jump. Eleven tiles doing that at different
 * moments is the screen twitching, which is how it read on the car and why this rule now comes
 * first.
 *
 * What survives of the older rule: the line says what is **configured** rather than repeating the
 * name, it takes the accent only when it is a reading rather than a setting (see [DenzaTileCaption]),
 * and nothing here repeats what the car itself shows a few centimetres away. Where a feature has
 * nothing configurable to report, on and off is the honest short answer and no longer a forbidden
 * one - the owner asked for it by name, and a caption invented to avoid saying "включено" is worse
 * than the word.
 */
object DashboardTiles {

    /**
     * Every tile on the main screen, in the order `Config.dc.html` places them.
     *
     * Eleven, with Shortcuts immediately before the service door. The Shortcuts tile
     * is not a runtime feature: it is the settings entry for the three stock Shortcuts roles.
     */
    fun of(state: DenzaUiState): List<DashboardTile> {
        // The door is built from the tiles in front of it rather than from the state again. It used
        // to count seven runtime features while the panel behind it filtered all eleven tiles, so
        // the two disagreed about what "2 функции ждут" meant - and the door counted itself.
        val features = listOf(
            cluster(state),
            simulcast(state),
            mirrors(state),
            split(state),
            hud(state),
            weather(state),
            speakers(state),
            locale(state),
            passenger(state),
            defaultApps(state),
        )
        return features + service(features)
    }

    /**
     * The tiles something is wrong with.
     *
     * **Every tile wearing [DenzaTileTone.ATTENTION] or [DenzaTileTone.BROKEN], the service door
     * itself excluded.** That is the whole contract, and it is deliberately one list rather than
     * two: the count on the door's face and the "Что не так" section inside it are the same
     * question asked in two places, and while they were two filters they answered it differently -
     * the face knew about the seven coordinated features and the panel knew about all eleven tiles,
     * including the door, which therefore appeared in its own list of things that need somebody.
     */
    fun attentionTiles(state: DenzaUiState): List<DashboardTile> =
        of(state).filter { it.id != TileId.SERVICE && needsSomebody(it.tone) }

    /** Amber or coral: a decision the driver has to make, or something that broke. */
    private fun needsSomebody(tone: DenzaTileTone): Boolean =
        tone == DenzaTileTone.ATTENTION || tone == DenzaTileTone.BROKEN

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
            // The label alone, projected or not. "· на экране" made this the longest caption on
            // the screen and the only one that changed length when the feature was used, which is
            // the jump this whole pass exists to remove. Whether it is on the cluster is already
            // said twice over - by the tone, and by the accent this caption takes below.
            state = if (waiting) snapshot.message else state.navigationAppLabel,
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
                // "6 приложений" made this tile read like a second application selector beside
                // Shortcuts.
                // one row along; "выбрано" names the projection's own fact.
                state.selectedAppCount == 0 -> "Не выбрано"
                else -> "Выбрано ${state.selectedAppCount}"
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
                watching -> "Включены"
                else -> "Выключены"
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
            // "Разделение экрана" wrapped onto two lines over a two-line caption, which was the
            // untidiest tile on the board. The icon says which screen, and the panel says the rest.
            name = "Разделение",
            state = when {
                snapshot.status == FeatureStatus.NEEDS_ACTION && snapshot.message.isNotBlank() ->
                    snapshot.message
                // A failed switch keeps the wish it was given, so reading the caption off
                // `desiredEnabled` had the tile saying "Включено" in coral - the words claiming
                // the thing the colour was there to deny. What is true after a refusal is that
                // nothing moved, and that is what it says.
                snapshot.status == FeatureStatus.UNAVAILABLE -> "Недоступно"
                snapshot.status == FeatureStatus.ERROR ->
                    snapshot.message.ifBlank { "Не переключилось" }
                snapshot.desiredEnabled -> "Включено"
                else -> "Выключено"
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
            name = "HUD Подсказки",
            state = when {
                waiting -> snapshot.message
                showing -> "Включены"
                else -> "Выключены"
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
    private fun weather(state: DenzaUiState): DashboardTile {
        // The last thing actually handed to the car, and how long ago - which is what the board
        // draws, and the only caption here that can be checked against the widget a few
        // centimetres away. "Данные для виджета" was the switch said twice.
        val reading = state.weatherTemperature
        val fresh = reading != null && state.weatherUpdatedMillis > 0L
        return DashboardTile(
            id = TileId.WEATHER,
            icon = TileIcon.WEATHER,
            name = "Погода",
            state = when {
                !state.weatherEnabled -> "Выключена"
                // The age went with the jump: "+14° · 12 минут назад" is two lines, and it changed
                // length every minute. The temperature alone is still the one caption on this
                // screen that can be checked against the widget a few centimetres away.
                fresh -> degrees(reading)
                else -> "Данных ещё нет"
            },
            // The switch is not the reading. A tile lit in the accent over "Данных ещё нет" is the
            // tone promising something the caption is denying, and on a car that had never once
            // fetched a forecast the weather looked like the healthiest thing on the screen. Lit
            // means the widget has been handed a temperature; waiting means the alarm stands and
            // nothing has come back yet.
            tone = when {
                !state.weatherEnabled -> DenzaTileTone.IDLE
                fresh -> DenzaTileTone.LIVE
                else -> DenzaTileTone.WORKING
            },
            caption = if (fresh && state.weatherEnabled) {
                DenzaTileCaption.READING
            } else {
                DenzaTileCaption.SETTING
            },
            action = TileAction.TOGGLE,
        )
    }

    /** "+14°", "0°", "-3°" - the sign is carried, because below zero is the point of reading it. */
    fun degrees(value: Int): String = when {
        value > 0 -> "+$value°"
        else -> "$value°"
    }

    /**
     * How long ago, in the coarsest unit that is still true.
     *
     * The tile does not say this any more - an age changes length every minute and the tile stacks
     * its words up from the bottom edge, so the name moved while nobody touched anything. The
     * weather panel does, because a panel is opened deliberately, holds still while it is read, and
     * is the only place that can answer "is this forecast the one outside".
     */
    fun ago(elapsedMillis: Long): String {
        val minutes = elapsedMillis / 60_000L
        return when {
            elapsedMillis < 0L -> "только что"
            minutes < 1L -> "только что"
            minutes < 60L -> "${minutes.toInt()} ${plural(minutes.toInt(), "минуту", "минуты", "минут")} назад"
            minutes < 24L * 60L -> {
                val hours = (minutes / 60L).toInt()
                "$hours ${plural(hours, "час", "часа", "часов")} назад"
            }
            else -> "больше суток назад"
        }
    }

    /** The last digit decides, and the teens are the exception. */
    private fun plural(count: Int, one: String, few: String, many: String): String {
        val tail = count % 100
        val last = count % 10
        return when {
            tail in 11..14 -> many
            last == 1 -> one
            last in 2..4 -> few
            else -> many
        }
    }

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
                snapshot.desiredEnabled -> "Включена"
                else -> "Выключена"
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
            name = "Русский язык",
            // A refusal is the news, and it used to be the one thing this tile could not report:
            // the failure wrote a message nobody read and the tile went on saying "Выключен" in
            // grey, which is what it says when nothing has been asked of it at all.
            state = when {
                // Two different refusals, and the difference is the only thing a driver can act
                // on: the car said no, or nothing has been asked of it yet because the app has no
                // way in. The sentence-length version of either is in the panel.
                snapshot.failed && !snapshot.permissionReady -> "Нужен доступ"
                snapshot.failed -> "Не переключился"
                snapshot.enabled == true -> "Включён"
                snapshot.enabled == false -> "Выключен"
                else -> "Не проверено"
            },
            tone = when {
                snapshot.running -> DenzaTileTone.WORKING
                // Amber rather than coral: the press retries the change, so this is a decision
                // still open rather than a feature that has broken.
                snapshot.failed -> DenzaTileTone.ATTENTION
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
            // Named for the screen, like the driver's own, and from where the reader is sitting.
            // "Пассажирский экран" is 194 dp of Roboto at 19/500 in a tile that gives a name 147,
            // which is why it wrapped; "Экран пассажира" is 162 and wraps too. This is 125.
            name = "Экран справа",
            // The application that went over there, in the same words the driver's tile uses for
            // its own. "Установить приложение" described the machinery instead of the result.
            state = snapshot.message.ifBlank { "Не выбрано" },
            tone = toneOf(snapshot),
            caption = DenzaTileCaption.SETTING,
            action = actionOf(snapshot, TileAction.PASSENGER_INSTALL),
        )
    }

    /** The launch targets used by the car's own navigation, music and video Shortcuts actions. */
    private fun defaultApps(state: DenzaUiState): DashboardTile {
        val defaults = state.defaultApps
        val configured = defaults.configuredCount
        return DashboardTile(
            id = TileId.DEFAULT_APPS,
            icon = TileIcon.DEFAULT_APPS,
            name = "Shortcuts",
            state = when {
                defaults.hasError -> "Не проверено"
                defaults.reading -> "Проверяем…"
                // Off is not "nothing configured", it is a car running its own applications, and
                // that is a state worth naming: the panel behind the tile still holds the choices
                // the switch would put back.
                defaults.substituting -> configuredApps(configured)
                else -> "Штатные"
            },
            tone = when {
                defaults.hasError -> DenzaTileTone.BROKEN
                defaults.reading -> DenzaTileTone.WORKING
                defaults.substituting -> DenzaTileTone.LIVE
                else -> DenzaTileTone.IDLE
            },
            caption = DenzaTileCaption.SETTING,
            // The press is the switch. Until there is anything to switch to, it is the panel
            // instead - a press that resolves to writing the same three stock packages back into
            // the three roles already holding them is a press that does nothing.
            action = if (defaults.substituting || defaults.canSubstitute) {
                TileAction.TOGGLE
            } else {
                TileAction.SETTINGS
            },
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
    private fun service(others: List<DashboardTile>): DashboardTile {
        // What the rest of the screen adds up to. "Всё в норме" was a constant, which made the one
        // tile whose job is to say something is wrong the one tile that could not - and it said so
        // in champagne on a car with two broken features. `Attention.dc.html` has drawn this as a
        // count in amber since the boards were made; the code simply never read it.
        //
        // It counts the tiles rather than the runtime features, and [others] is every tile but this
        // one, so the number on the face is by construction the length of [attentionTiles] - the
        // list the panel behind it prints. The seven-feature count it replaces could not see a
        // stuck language or an unread application role at all.
        //
        // Broken and waiting counted together, and amber either way, which is what the board
        // draws: one broken feature and one waiting reads "2 функции ждут". Coral would be this
        // tile claiming to be the thing that failed, and nothing about a door has failed - it is
        // the room behind it that needs somebody. The count is how many things need one.
        val needing = others.count { needsSomebody(it.tone) }
        return DashboardTile(
            id = TileId.SERVICE,
            icon = TileIcon.SERVICE,
            name = "Сервис",
            state = if (needing > 0) {
                "${featureCount(needing)} ${verb(needing, "ждёт", "ждут")}"
            } else {
                "Всё в норме"
            },
            tone = if (needing > 0) DenzaTileTone.ATTENTION else DenzaTileTone.IDLE,
            // A reading of the car, not a setting - so on a healthy car it stays grey, and when it
            // is not grey the tone has already said which kind of trouble it is.
            caption = DenzaTileCaption.READING,
            action = TileAction.SERVICE_OPEN,
        )
    }

    /** "1 функция", "2 функции", "5 функций" - the same teens exception as [applications]. */
    fun featureCount(count: Int): String {
        val tail = count % 100
        val last = count % 10
        val word = when {
            tail in 11..14 -> "функций"
            last == 1 -> "функция"
            last in 2..4 -> "функции"
            else -> "функций"
        }
        return "$count $word"
    }

    /** The verb agrees with the noun the count produced, not with the digit. */
    private fun verb(count: Int, singular: String, plural: String): String {
        val tail = count % 100
        return if (tail !in 11..14 && count % 10 == 1) singular else plural
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

    /**
     * The deliberately short line under the default-applications tile.
     *
     * Never asked for none: a car with nothing substituted is running its own applications, and
     * "Штатные" says that where "Не настроены" said the panel had not been filled in.
     */
    fun configuredApps(count: Int): String = when (count) {
        1 -> "1 настроено"
        else -> "$count настроены"
    }

    /** The resolution a waiting feature is waiting on, for the screen to open the right chooser. */
    fun resolutionOf(snapshot: FeatureSnapshot): FeatureResolution? =
        snapshot.resolution.takeIf { snapshot.status == FeatureStatus.NEEDS_ACTION }
}
