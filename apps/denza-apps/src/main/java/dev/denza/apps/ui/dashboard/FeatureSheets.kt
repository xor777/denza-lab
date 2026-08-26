package dev.denza.apps.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.denza.apps.DenzaUiState
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.design.DenzaColors
import dev.denza.apps.design.DenzaMetrics
import dev.denza.apps.feature.cluster.ClusterMapPlacement
import dev.denza.apps.feature.mirrors.MirrorsPosition
import dev.denza.apps.feature.speaker.SpeakerCoverApps
import dev.denza.apps.ui.components.DenzaAppTile
import dev.denza.apps.ui.components.DenzaTileTone
import dev.denza.apps.ui.components.DenzaNote
import dev.denza.apps.ui.components.DenzaTileGrid
import dev.denza.apps.ui.components.DenzaPrimaryButton
import dev.denza.apps.ui.components.DenzaSecondaryButton
import dev.denza.apps.ui.components.DenzaSection
import dev.denza.apps.ui.components.DenzaSegmentedRow
import dev.denza.apps.ui.components.DenzaSheet
import dev.denza.apps.ui.components.DenzaSheetHeader
import dev.denza.apps.ui.components.DenzaSheetFootnote
import dev.denza.apps.ui.components.DenzaStatusLine
import dev.denza.apps.ui.components.DenzaSwitchRow

/**
 * What a long press opens: one feature's settings, and nothing else on the screen.
 *
 * These are the controls that used to live on the face of the cards - segmented rows, switches,
 * chooser buttons - and the reason they moved is not tidiness. A car's dashboard is touched by
 * someone who is driving, and a switch sitting on a tile is a switch that gets caught by a thumb
 * reaching for the tile. The tile now has two gestures and no controls; every control is one
 * deliberate long press away.
 *
 * **Every tile has one of these, and every one is built the same way**: the switch that turns the
 * feature on, whatever it has to choose, and then a short paragraph saying what the feature is and
 * how it behaves. Before this, five tiles had no panel at all - their long press did nothing - and
 * the panels that existed introduced themselves with a subtitle under the title that mostly said
 * the name again in other words ("Трансляция" / "Какие приложения уходят на экраны"). The subtitle
 * is gone and the paragraph took its job, because a sentence at the bottom can explain a feature
 * and a fragment at the top cannot.
 *
 * A panel with nothing to switch is still worth opening: it is the only place that says what the
 * thing does. That is why the tiles with a single action have one too.
 */
@Composable
fun FeatureSheet(
    id: TileId,
    state: DenzaUiState,
    actions: DashboardActions,
    compact: Boolean,
    onDismiss: () -> Unit,
) {
    val tile = DashboardTiles.of(state).first { it.id == id }
    val snapshot = DashboardPress.snapshotOf(id, state)
    val busy = snapshot?.status == FeatureStatus.STARTING ||
        snapshot?.status == FeatureStatus.RECOVERING

    val action = panelAction(tile, state, actions, onDismiss)
    DenzaSheet(
        onDismiss = onDismiss,
        compact = compact,
        footer = {
            if (action.label.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.M)) {
                    DenzaPrimaryButton(
                        text = action.label,
                        onClick = action.onClick,
                        modifier = Modifier.fillMaxWidth()
                            .height(DenzaMetrics.Component.PRIMARY_HEIGHT),
                        enabled = !busy && action.enabled,
                    )
                    if (action.isTheTilePress) {
                        DenzaSheetFootnote("Короткое нажатие на плитку делает то же самое")
                    }
                }
            }
        },
    ) {
        // No subtitle. The panel says what it is at the bottom, in a sentence, once.
        DenzaSheetHeader(
            title = tile.name,
            subtitle = "",
            onDismiss = onDismiss,
            icon = tileIcon(tile.icon),
        )
        // Whatever the feature has to say for itself, in the colour that state deserves. It is
        // said once, here, rather than by each sheet in its own words - and only when the state is
        // one the driver has to do something about.
        //
        // A working feature restating itself is noise, and it is the same rule the tile's caption
        // follows: something that speaks on a healthy car teaches the driver to stop reading it.
        // Split screen was heading its panel with "Иконка Split Screen доступна" directly above
        // the switch that says so, in English, inside a Russian sentence.
        val speaks = tile.tone == DenzaTileTone.ATTENTION ||
            tile.tone == DenzaTileTone.BROKEN ||
            tile.tone == DenzaTileTone.WORKING
        DenzaStatusLine(
            text = if (speaks) snapshot?.message.orEmpty().takeIf { it != tile.state }.orEmpty()
            else "",
            tone = tile.tone,
        )
        when (id) {
            TileId.CLUSTER -> clusterSheet(state, actions, busy)
            TileId.SIMULCAST -> simulcastSheet(state, actions, busy)
            TileId.MIRRORS -> mirrorsSheet(state, actions, busy)
            TileId.SPLIT -> splitSheet(state, actions, busy)
            TileId.HUD -> hudSheet(state, actions, busy)
            TileId.WEATHER -> weatherSheet(state, actions)
            TileId.SPEAKERS -> speakerSheet(state, actions, busy)
            TileId.LOCALE -> localeSheet(state, actions)
            // Nothing to switch: the paragraph below is the whole panel, and the button at the
            // foot is the one thing there is to do.
            TileId.PASSENGER, TileId.SERVICE -> Unit
        }
        DenzaNote(helpOf(id))
        val details = snapshot?.details
        if (details != null) {
            Text(
                text = details,
                style = MaterialTheme.typography.bodyMedium,
                color = DenzaColors.MutedDeep,
            )
        }
    }
}

/**
 * What the feature is and how it behaves, in the driver's words.
 *
 * One of these per tile, exhaustively - a new tile cannot ship without writing one, which is the
 * point of leaving the `when` without an `else`. They replaced a subtitle under the title that had
 * room for a fragment and used it to say the name again; a sentence has room to say the thing a
 * driver actually cannot work out by looking, which is what a covered speaker or a turn-indicator
 * camera does when nobody is watching it.
 *
 * Short, present tense, no instructions the screen already gives.
 */
private fun helpOf(id: TileId): String = when (id) {
    TileId.CLUSTER ->
        "Выбранное приложение занимает приборную панель за рулём. " +
            "Короткое нажатие на плитку ставит его туда и убирает обратно."
    TileId.SIMULCAST ->
        "Выбранные приложения показываются на пассажирском экране и на экране сзади. " +
            "Запуск открывает их там сразу."
    TileId.MIRRORS ->
        "Когда включён поворотник, на экране появляется камера с этой стороны и пропадает " +
            "вместе с ним. «По центру» показывает обе камеры одну над другой."
    TileId.SPLIT ->
        "При включении на рабочем столе появляется значок разделения экрана. " +
            "Он открывает выбор двух приложений, которые встанут рядом."
    TileId.HUD ->
        "Указания навигатора повторяются на проекции на лобовом стекле."
    TileId.WEATHER ->
        "Приложение забирает прогноз и отдаёт его штатному виджету погоды. " +
            "Своей погоды оно не рисует — виджет остаётся штатным."
    TileId.SPEAKERS ->
        "Крышки поднимаются сами, когда что-то играет: сразу для известных приложений " +
            "(${SpeakerCoverApps.EXAMPLES}) и через три секунды звука для остальных. " +
            "После получаса тишины они уезжают обратно."
    TileId.LOCALE ->
        "Родной русский язык уже встроен в систему — " +
            "переключатель включает его в штатных настройках машины."
    TileId.PASSENGER ->
        "Выбранное приложение устанавливается на экран перед пассажиром."
    TileId.SERVICE ->
        "Показания машины, доступ приложения к ней и штатные настройки, до которых оно дотягивается."
}

/**
 * The one thing a panel's button does.
 *
 * Every panel has the same two organs and they never swap jobs: **a switch turns the feature on,
 * the button at the foot does something**. The button is a verb - "Запустить", "Разделить экран",
 * "Проверить камеры" - and never an on/off.
 *
 * Before this, it was whichever the tile happened to need. Projection and split screen showed a
 * switch at the top and a verb at the foot; the mirrors panel had no switch at all and put its
 * on/off in the button as "Выключить" - the same decision offered through two different controls
 * depending on which panel you opened. Worse, three of the mirrors panel's controls grey out when
 * the feature is off and nothing in it said why, because the switch that would have said so was
 * the one thing missing.
 *
 * [isTheTilePress] is what the footnote is allowed to promise. The button usually is the tile's
 * own press said in words, and where it is not - the mirrors panel previews the cameras, it does
 * not stop watching them - the footnote goes rather than becoming untrue.
 */
private data class PanelAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val isTheTilePress: Boolean = true,
)

private fun panelAction(
    tile: DashboardTile,
    state: DenzaUiState,
    actions: DashboardActions,
    onDismiss: () -> Unit,
): PanelAction {
    val press = { DashboardPress.perform(tile, state, actions); onDismiss() }
    return when (tile.id) {
        // Watching or not is the switch's job now, so the foot is free for the only verb this
        // feature has.
        TileId.MIRRORS -> PanelAction(
            label = "Проверить камеры",
            onClick = { actions.onPreviewMirrors(); onDismiss() },
            enabled = state.mirrors.desiredEnabled,
            isTheTilePress = false,
        )
        // The tile presses this into a toggle while projection is off; the panel does not, because
        // the switch above is already that decision.
        TileId.SIMULCAST -> PanelAction(
            label = "Запустить",
            onClick = { actions.onLaunchSimulcast(); onDismiss() },
            enabled = state.simulcast.desiredEnabled,
            isTheTilePress = tile.action == TileAction.SIMULCAST_LAUNCH,
        )
        // Their switch is in the panel, so the foot would only be that switch again.
        TileId.HUD, TileId.WEATHER, TileId.SPEAKERS, TileId.LOCALE -> PanelAction(
            label = "",
            onClick = {},
        )
        else -> PanelAction(label = primaryLabel(tile, state), onClick = press)
    }
}

/**
 * The name of the tile's own action, for the panels whose button is that press.
 *
 * It reads the same wish the tile's press reads, so the two can never offer opposite things.
 */
private fun primaryLabel(tile: DashboardTile, state: DenzaUiState): String {
    val on = DashboardPress.snapshotOf(tile.id, state)?.desiredEnabled == true
    return when (tile.action) {
        TileAction.CLUSTER_PROJECT -> state.navigationButtonLabel
        TileAction.SIMULCAST_LAUNCH -> "Запустить"
        TileAction.SPLIT_LAUNCH -> "Разделить экран"
        TileAction.PASSENGER_INSTALL -> "Выбрать приложение"
        TileAction.SERVICE_OPEN -> "Открыть сервис"
        TileAction.TOGGLE -> if (on) "Выключить" else "Включить"
        TileAction.RESOLVE -> "Продолжить"
        TileAction.SETTINGS -> ""
    }
}

/**
 * What goes on the driver's screen, where it goes, and whether the wheel button reaches it.
 *
 * The applications are chosen here rather than behind another dialog. A settings panel whose first
 * control opens a second panel over itself is two surfaces asking one question, and on the board
 * there is only ever one.
 */
@Composable
private fun clusterSheet(state: DenzaUiState, actions: DashboardActions, busy: Boolean) {
    if (state.navigationAppChoices.isNotEmpty()) {
        DenzaSection("Что показывать") {
            DenzaTileGrid(
                columns = SHEET_COLUMNS,
                itemCount = state.navigationAppChoices.size,
                modifier = Modifier.fillMaxWidth(),
            ) { index, cell ->
                val choice = state.navigationAppChoices[index]
                DenzaAppTile(
                    label = choice.label,
                    selected = choice.selected,
                    onClick = { actions.onSelectNavigationApp(choice.packageName) },
                    modifier = cell,
                    icon = choice.icon,
                    iconKey = choice.packageName,
                )
            }
        }
    }
    // Our own instruments are drawn for the whole panel and have one placement, so the row is
    // absent rather than shown with one live cell and three dead ones.
    if (state.navigationPlacements.size > 1) {
        DenzaSection("Размещение") {
            DenzaSegmentedRow(
                labels = state.navigationPlacements.map(::placementLabel),
                selectedIndex = state.navigationPlacements.indexOf(state.navigationPlacement)
                    .coerceAtLeast(0),
                onSelect = { actions.onNavigationPlacement(state.navigationPlacements[it]) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
        }
    }
    DenzaSwitchRow(
        title = "Кнопка ★ на руле",
        checked = state.navigationSteeringWheelButton,
        onCheckedChange = actions.onNavigationSteeringWheelButton,
    )
}

/** Which applications go to the screens. */
@Composable
private fun simulcastSheet(state: DenzaUiState, actions: DashboardActions, busy: Boolean) {
    DenzaSwitchRow(
        title = "Поддержка трансляции",
        checked = state.simulcast.desiredEnabled,
        onCheckedChange = actions.onToggleSimulcast,
        enabled = !busy,
    )
    if (state.appChoices.isNotEmpty()) {
        DenzaSection("Какие приложения") {
            DenzaTileGrid(
                columns = SHEET_COLUMNS,
                itemCount = state.appChoices.size,
                modifier = Modifier.fillMaxWidth(),
            ) { index, cell ->
                val choice = state.appChoices[index]
                DenzaAppTile(
                    label = choice.label,
                    selected = choice.selected,
                    onClick = { actions.onToggleApp(choice.packageName) },
                    modifier = cell,
                    icon = choice.icon,
                    iconKey = choice.packageName,
                )
            }
        }
    }
}

/**
 * Where the turn-indicator cameras appear, and how their picture is treated.
 *
 * This panel used to say the same thing in four places at once - a subtitle under the switch, a
 * heading over the row, the row's own two words, and a note under it that changed with the choice.
 * The controls are the same; everything that was prose about them is now one paragraph at the foot
 * of the panel, where the other nine tiles keep theirs.
 */
@Composable
private fun mirrorsSheet(state: DenzaUiState, actions: DashboardActions, busy: Boolean) {
    DenzaSwitchRow(
        title = "Зеркала",
        checked = state.mirrors.desiredEnabled,
        onCheckedChange = actions.onToggleMirrors,
        enabled = !busy,
    )
    DenzaSection("Где показывать") {
        DenzaSegmentedRow(
            labels = listOf("По сторонам", "По центру"),
            selectedIndex = when (state.mirrorsPosition) {
                MirrorsPosition.SIDES -> 0
                MirrorsPosition.CENTER -> 1
            },
            onSelect = {
                actions.onMirrorsPosition(
                    if (it == 0) MirrorsPosition.SIDES else MirrorsPosition.CENTER,
                )
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && state.mirrors.desiredEnabled,
        )
    }
    DenzaSwitchRow(
        title = "Улучшение изображения",
        checked = state.mirrorsProcessing,
        onCheckedChange = actions.onMirrorsProcessing,
        enabled = state.mirrors.desiredEnabled,
    )
}

/** One launcher icon, present or absent. There is nothing else to set. */
@Composable
private fun splitSheet(state: DenzaUiState, actions: DashboardActions, busy: Boolean) {
    DenzaSwitchRow(
        title = "Значок на рабочем столе",
        checked = state.splitScreen.desiredEnabled,
        onCheckedChange = actions.onToggleSplitScreen,
        enabled = !busy,
    )
}

/** Whether navigation hints are repeated onto the projection. */
@Composable
private fun hudSheet(state: DenzaUiState, actions: DashboardActions, busy: Boolean) {
    DenzaSwitchRow(
        title = "Подсказки на проекции",
        checked = state.hudGuidance.desiredEnabled,
        onCheckedChange = actions.onToggleHudGuidance,
        enabled = !busy,
    )
}

/** Whether the app keeps feeding the car's own weather widget. */
@Composable
private fun weatherSheet(state: DenzaUiState, actions: DashboardActions) {
    DenzaSwitchRow(
        title = "Данные для виджета",
        checked = state.weatherEnabled,
        onCheckedChange = actions.onSetWeatherEnabled,
    )
}

/**
 * Playback-driven opening, and the two buttons that overrule it.
 *
 * The buttons answer whether or not the automation is on: the covers belong to the car, and wanting
 * them up at a standstill with nothing playing is a perfectly ordinary thing. The automation is
 * told where they went, so it does not immediately argue - though three seconds of music will
 * still raise them again, which is the paragraph's job to say.
 */
@Composable
private fun speakerSheet(state: DenzaUiState, actions: DashboardActions, busy: Boolean) {
    DenzaSwitchRow(
        title = "Автоматика крышек",
        checked = state.speakerCovers.desiredEnabled,
        onCheckedChange = actions.onToggleSpeakerCovers,
        enabled = !busy,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.M),
    ) {
        DenzaSecondaryButton(
            text = "Поднять",
            onClick = actions.onRaiseSpeakerCovers,
            modifier = Modifier.weight(1f),
            enabled = !busy,
        )
        DenzaSecondaryButton(
            text = "Опустить",
            onClick = actions.onLowerSpeakerCovers,
            modifier = Modifier.weight(1f),
            enabled = !busy,
        )
    }
}

/** Russian in the car's own settings, which is a switch in stock firmware and nothing of ours. */
@Composable
private fun localeSheet(state: DenzaUiState, actions: DashboardActions) {
    val locale = state.stockRussianLocale
    DenzaSwitchRow(
        title = "Русский язык",
        checked = locale.enabled == true,
        onCheckedChange = actions.onSetStockRussianLocale,
        enabled = locale.permissionReady && !locale.running,
    )
}

private const val SHEET_COLUMNS = 3

private fun placementLabel(placement: ClusterMapPlacement): String = when (placement) {
    ClusterMapPlacement.FULL -> "Полный"
    ClusterMapPlacement.LEFT -> "Слева"
    ClusterMapPlacement.CENTER -> "Центр"
    ClusterMapPlacement.RIGHT -> "Справа"
}
