package dev.denza.apps.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import dev.denza.apps.ui.components.DenzaPrimaryButton
import dev.denza.apps.ui.components.DenzaSecondaryButton
import dev.denza.apps.ui.components.DenzaSection
import dev.denza.apps.ui.components.DenzaSegmentedRow
import dev.denza.apps.ui.components.DenzaSheet
import dev.denza.apps.ui.components.DenzaSheetHeader
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

    DenzaSheet(onDismiss = onDismiss, compact = compact) {
        DenzaSheetHeader(
            title = tile.name,
            subtitle = tile.state,
            actionLabel = "Готово",
            onAction = onDismiss,
            compact = compact,
        )
        // Whatever the feature has to say for itself, in the colour that state deserves. It is said
        // once, here, rather than by each sheet in its own words.
        DenzaStatusLine(
            text = snapshot?.message.orEmpty().takeIf { it != tile.state }.orEmpty(),
            tone = tile.tone,
        )
        when (id) {
            TileId.CLUSTER -> clusterSheet(state, actions, busy)
            TileId.SIMULCAST -> simulcastSheet(state, actions, busy)
            TileId.MIRRORS -> mirrorsSheet(state, actions, busy)
            TileId.SPLIT -> splitSheet(state, actions, busy)
            TileId.HUD -> hudSheet(state, actions, busy)
            TileId.SPEAKERS -> SpeakerSheet(state, actions, busy)
            TileId.STEERING_WHEEL -> steeringWheelSheet(state, actions, busy)
            TileId.LOCALE -> localeSheet(state, actions)
            TileId.PASSENGER -> passengerSheet(state, actions, busy)
            // Service is a door: its short press opens the service screen, and a long press has
            // nothing else of its own to open.
            TileId.SERVICE -> Unit
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

/** What goes on the driver's screen, where it goes, and whether the wheel button reaches it. */
@Composable
private fun clusterSheet(state: DenzaUiState, actions: DashboardActions, busy: Boolean) {
    DenzaSection("Что показывать") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.M),
        ) {
            DenzaSecondaryButton(
                text = state.navigationAppLabel,
                onClick = actions.onChooseNavigationApp,
                modifier = Modifier.weight(1f),
                enabled = !busy,
            )
            DenzaPrimaryButton(
                text = state.navigationButtonLabel,
                onClick = actions.onNavigationAction,
                modifier = Modifier.weight(1f),
                enabled = !busy,
            )
        }
    }
    // Our own instruments are drawn for the whole panel and have one placement, so the row is
    // absent rather than shown with one live cell and three dead ones.
    if (state.navigationPlacements.size > 1) {
        DenzaSection("Где на экране") {
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

/** Which applications go to the screens, and whether the projection runs at all. */
@Composable
private fun simulcastSheet(state: DenzaUiState, actions: DashboardActions, busy: Boolean) {
    DenzaSwitchRow(
        title = "Трансляция",
        subtitle = "Приложения на экранах",
        checked = state.simulcast.desiredEnabled,
        onCheckedChange = actions.onToggleSimulcast,
        enabled = !busy,
    )
    DenzaSection("Приложения") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DenzaMetrics.Space.M),
        ) {
            DenzaSecondaryButton(
                text = if (state.selectedAppCount == 0) {
                    "Выбрать"
                } else {
                    DashboardTiles.applications(state.selectedAppCount)
                },
                onClick = actions.onChooseApps,
                modifier = Modifier.weight(1f),
                enabled = !busy,
            )
            DenzaPrimaryButton(
                text = "Запустить",
                onClick = actions.onLaunchSimulcast,
                modifier = Modifier.weight(1f),
                enabled = !busy && state.simulcast.desiredEnabled && state.selectedAppCount > 0,
            )
        }
    }
}

/** Where the turn-indicator cameras appear, and how their picture is treated. */
@Composable
private fun mirrorsSheet(state: DenzaUiState, actions: DashboardActions, busy: Boolean) {
    DenzaSwitchRow(
        title = "Зеркала",
        subtitle = "Камеры поворотников",
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
        subtitle = "Ярче и контрастнее",
        checked = state.mirrorsProcessing,
        onCheckedChange = actions.onMirrorsProcessing,
        enabled = state.mirrors.desiredEnabled,
    )
    DenzaSecondaryButton(
        text = "Проверить камеры",
        onClick = actions.onPreviewMirrors,
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy,
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

/**
 * The button on the wheel: one switch, and the car keeps its own behaviour when it is off.
 */
@Composable
private fun steeringWheelSheet(state: DenzaUiState, actions: DashboardActions, busy: Boolean) {
    DenzaSection(title = "Что делает кнопка") {
        DenzaSwitchRow(
            title = "Открывать экран водителя",
            checked = state.navigationSteeringWheelButton,
            onCheckedChange = actions.onNavigationSteeringWheelButton,
            enabled = !busy && !state.navigationSteeringWheelButtonRepairing,
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

private fun placementLabel(placement: ClusterMapPlacement): String = when (placement) {
    ClusterMapPlacement.FULL -> "Полный"
    ClusterMapPlacement.LEFT -> "Слева"
    ClusterMapPlacement.CENTER -> "Центр"
    ClusterMapPlacement.RIGHT -> "Справа"
}
