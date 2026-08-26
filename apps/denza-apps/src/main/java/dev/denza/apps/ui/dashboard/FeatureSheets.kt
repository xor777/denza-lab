package dev.denza.apps.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
 * Each sheet is built from the shared components, so none of them can drift from the others the way
 * the three card shapes did.
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
        DenzaSheetHeader(
            title = tile.name,
            subtitle = purposeOf(id),
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
            TileId.SPEAKERS -> SpeakerSheet(state, actions, busy)
            TileId.LOCALE -> localeSheet(state, actions)
            TileId.PASSENGER -> passengerSheet(state, actions, busy)
            // Service is a door: its short press opens the service screen, and a long press has
            // nothing else of its own to open.
            // Neither opens a panel: one is a door that opens its own thing, the other a
            // single switch the press already flips.
            TileId.WEATHER,
            TileId.SERVICE,
            -> Unit
        }
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

/** What this panel is for, in the words the board writes under its title. */
/**
 * What a panel is for, under its name.
 *
 * Exhaustive rather than defaulting to the empty string: a new tile with a panel and no purpose
 * line is a header that names a feature and says nothing about what the settings under it decide,
 * and an `else` branch makes that the silent default. Split screen arrived that way and shipped
 * without one.
 */
private fun purposeOf(id: TileId): String = when (id) {
    TileId.CLUSTER -> "Что показывать за рулём"
    TileId.SIMULCAST -> "Какие приложения уходят на экраны"
    TileId.MIRRORS -> "Когда показывать камеры и где"
    TileId.SPLIT -> "Как делить экран между приложениями"
    TileId.WEATHER -> "Погода для штатного виджета"
    // No panel of their own: nothing to head.
    TileId.HUD, TileId.SPEAKERS, TileId.LOCALE, TileId.PASSENGER, TileId.SERVICE -> ""
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
            DenzaNote(placementNote(state.navigationPlacement))
        }
    }
    DenzaSwitchRow(
        title = "Кнопка ★ на руле",
        subtitle = when {
            !state.navigationSteeringWheelButton -> "Штатное действие не перехватывается"
            state.navigationSteeringWheelButtonRepairing -> "Восстанавливаю системный доступ…"
            state.navigationSteeringWheelButtonReady -> "Перехват активен"
            else -> "Системный доступ недоступен"
        },
        checked = state.navigationSteeringWheelButton,
        onCheckedChange = actions.onNavigationSteeringWheelButton,
    )
}

/** Which applications go to the screens. */
@Composable
private fun simulcastSheet(state: DenzaUiState, actions: DashboardActions, busy: Boolean) {
    DenzaSwitchRow(
        title = "Трансляция",
        subtitle = "Приложения на экранах",
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

/** Where the turn-indicator cameras appear, and how their picture is treated. */
@Composable
private fun mirrorsSheet(state: DenzaUiState, actions: DashboardActions, busy: Boolean) {
    DenzaSwitchRow(
        title = "Зеркала",
        subtitle = "Камеры по поворотникам",
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
        DenzaNote(
            if (state.mirrorsPosition == MirrorsPosition.SIDES) {
                "Камера появляется с той стороны, куда включён поворотник."
            } else {
                "Обе камеры показываются по центру экрана, одна над другой."
            },
        )
    }
    DenzaSwitchRow(
        title = "Улучшение изображения",
        subtitle = "Ярче и контрастнее",
        checked = state.mirrorsProcessing,
        onCheckedChange = actions.onMirrorsProcessing,
        enabled = state.mirrors.desiredEnabled,
    )
}

/** One launcher icon, present or absent. There is nothing else to set. */
@Composable
private fun splitSheet(state: DenzaUiState, actions: DashboardActions, busy: Boolean) {
    DenzaSwitchRow(
        title = "Разделение экрана",
        subtitle = "Значок на рабочем столе открывает выбор двух приложений",
        checked = state.splitScreen.desiredEnabled,
        onCheckedChange = actions.onToggleSplitScreen,
        enabled = !busy,
    )
}

/** Whether navigation hints are repeated onto the projection. */
@Composable
private fun hudSheet(state: DenzaUiState, actions: DashboardActions, busy: Boolean) {
    DenzaSwitchRow(
        title = "Подсказки на HUD",
        subtitle = "Указания навигатора на проекции",
        checked = state.hudGuidance.desiredEnabled,
        onCheckedChange = actions.onToggleHudGuidance,
        enabled = !busy,
    )
}

/** Playback-driven opening with a deliberately long, silence-only close delay. */
@Composable
private fun SpeakerSheet(state: DenzaUiState, actions: DashboardActions, busy: Boolean) {
    DenzaSwitchRow(
        title = "Автоматика крышек",
        subtitle = "Закрывать через 30 минут без звука",
        checked = state.speakerCovers.desiredEnabled,
        onCheckedChange = actions.onToggleSpeakerCovers,
        enabled = !busy,
    )
    DenzaSection("Как открываются") {
        Text(
            text = "Сразу для известных аудио- и видеоприложений или активной MediaSession; " +
                "после 3 секунд звука — резервно для остальных.",
            style = MaterialTheme.typography.bodyMedium,
            color = DenzaColors.Muted,
        )
    }
}

/** The passenger's screen has one thing to do, so the sheet is that one thing. */
@Composable
private fun passengerSheet(state: DenzaUiState, actions: DashboardActions, busy: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.M),
    ) {
        Text(
            text = "Приложение с этого экрана ставится на пассажирский.",
            style = MaterialTheme.typography.bodyMedium,
            color = DenzaColors.Muted,
        )
        DenzaPrimaryButton(
            text = "Выбрать приложение",
            onClick = actions.onChooseFseApp,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        )
    }
}
/** Russian in the car's own settings, which is a switch in stock firmware and nothing of ours. */
@Composable
private fun localeSheet(state: DenzaUiState, actions: DashboardActions) {
    val locale = state.stockRussianLocale
    DenzaSection(title = "Штатные настройки") {
        DenzaSwitchRow(
            title = "Русский язык",
            checked = locale.enabled == true,
            onCheckedChange = actions.onSetStockRussianLocale,
            enabled = locale.permissionReady && !locale.running,
        )
    }
}

/** What the chosen placement actually does to the strip, which its name cannot say. */
private fun placementNote(placement: ClusterMapPlacement): String = when (placement) {
    ClusterMapPlacement.FULL ->
        "Приборы свёрстаны на всю полосу и обходят штатные элементы."
    ClusterMapPlacement.LEFT, ClusterMapPlacement.CENTER, ClusterMapPlacement.RIGHT ->
        "Занята часть полосы. Остальное остаётся штатным."
}

private const val SHEET_COLUMNS = 3

private fun placementLabel(placement: ClusterMapPlacement): String = when (placement) {
    ClusterMapPlacement.FULL -> "Полный"
    ClusterMapPlacement.LEFT -> "Слева"
    ClusterMapPlacement.CENTER -> "Центр"
    ClusterMapPlacement.RIGHT -> "Справа"
}
