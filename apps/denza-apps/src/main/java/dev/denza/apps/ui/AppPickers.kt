package dev.denza.apps.ui

import androidx.compose.runtime.Composable
import dev.denza.apps.NavigationAppChoice
import dev.denza.apps.SimulcastAppChoice
import dev.denza.apps.design.DenzaMetrics
import dev.denza.apps.feature.fse.FseInstallApp
import dev.denza.apps.ui.components.DenzaAppTile
import dev.denza.apps.ui.components.DenzaPickerSheet
import dev.denza.apps.ui.dashboard.DashboardTiles

/**
 * The three lists of applications this app asks the driver to choose from.
 *
 * They were three dialogs and three tiles; they are three calls now, because once the sheet and the
 * tile are shared there is nothing left of a picker but its words and how many fit in a row.
 */

/** Which applications the projection sends to the other screens. Several at once. */
@Composable
internal fun AppPickerDialog(
    apps: List<SimulcastAppChoice>,
    compactLayout: Boolean,
    selectedCount: Int,
    message: String,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    DenzaPickerSheet(
        title = "Приложения на экранах",
        subtitle = "Можно выбрать до 6 · выбрано $selectedCount",
        items = apps,
        key = { it.packageName },
        compact = compactLayout,
        onDismiss = onDismiss,
        note = message,
        emptyText = "Приложения не найдены",
    ) { app ->
        DenzaAppTile(
            label = app.label,
            selected = app.selected,
            onClick = { onToggle(app.packageName) },
            icon = app.icon,
            iconKey = app.packageName,
        )
    }
}

/** What goes on the driver's screen. One at a time, so choosing closes the sheet. */
@Composable
internal fun NavigationPickerDialog(
    apps: List<NavigationAppChoice>,
    compactLayout: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    DenzaPickerSheet(
        title = "Экран водителя",
        subtitle = "Что показывать за рулём",
        items = apps,
        key = { it.packageName },
        compact = compactLayout,
        onDismiss = onDismiss,
        emptyText = "Поддерживаемые навигаторы не найдены",
        columns = DenzaMetrics.Component.TILE_COLUMNS_MEDIUM,
    ) { app ->
        DenzaAppTile(
            label = app.label,
            selected = app.selected,
            onClick = { onSelect(app.packageName) },
            icon = app.icon,
            iconKey = app.packageName,
        )
    }
}

/** Which application to put across on the passenger's screen. */
@Composable
internal fun FseInstallerPickerDialog(
    apps: List<FseInstallApp>,
    compactLayout: Boolean,
    message: String,
    onInstall: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    DenzaPickerSheet(
        title = "Экран справа",
        subtitle = subtitleFor(apps.size),
        items = apps,
        key = { it.packageName },
        compact = compactLayout,
        onDismiss = onDismiss,
        note = message,
        emptyText = "Приложения не найдены",
    ) { app ->
        DenzaAppTile(
            label = app.label,
            selected = false,
            onClick = { onInstall(app.packageName) },
            icon = app.icon,
            iconKey = app.packageName,
        )
    }
}

/** "12 приложений с головного устройства" - agreed the way Russian agrees it. */
private fun subtitleFor(count: Int): String =
    "${DashboardTiles.applications(count)} с головного устройства"
