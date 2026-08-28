package dev.denza.apps.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.denza.apps.NavigationAppChoice
import dev.denza.apps.SimulcastAppChoice
import dev.denza.apps.design.DenzaColors
import dev.denza.apps.design.DenzaMetrics
import dev.denza.apps.feature.fse.FseInstallApp
import dev.denza.apps.ui.components.DenzaAppGrid
import dev.denza.apps.ui.components.DenzaAppTile
import dev.denza.apps.ui.components.DenzaPickerSheet
import dev.denza.apps.ui.components.DenzaSheet
import dev.denza.apps.ui.components.DenzaSheetHeader
import dev.denza.apps.ui.dashboard.DashboardTiles

/**
 * The three lists of applications this app asks the driver to choose from.
 *
 * They were three dialogs and three tiles; they are three calls now, because once the sheet and the
 * tile are shared there is nothing left of a picker but its words and how many fit in a row.
 *
 * None of them answers a tap with a sentence. What cannot be chosen is drawn as not choosable - a
 * limit that has been reached greys the tiles beyond it, an application whose APK cannot be sent
 * over greys itself - so the refusal is in the picture before the finger arrives rather than in a
 * line of text after it.
 */

/** Which applications the projection sends to the other screens. Several at once. */
@Composable
internal fun AppPickerDialog(
    apps: List<SimulcastAppChoice>,
    compactLayout: Boolean,
    selectedCount: Int,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    DenzaPickerSheet(
        title = "Что транслировать",
        subtitle = "Можно выбрать до 6 · выбрано $selectedCount",
        items = apps,
        key = { it.packageName },
        compact = compactLayout,
        onDismiss = onDismiss,
        emptyText = "Приложения не найдены",
    ) { app ->
        DenzaAppTile(
            label = app.label,
            selected = app.selected,
            onClick = { onToggle(app.packageName) },
            icon = app.icon,
            iconKey = app.packageName,
            // At the limit the unchosen go quiet. Pressing a seventh used to be accepted as a
            // gesture and answered with "Можно выбрать не больше 6" over the grid, which is the
            // screen letting the driver make a mistake so it can tell them off for it.
            enabled = app.selectable,
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
    DenzaSheet(onDismiss = onDismiss, compact = compactLayout) {
        DenzaSheetHeader(
            title = "Экран водителя",
            subtitle = "Что показывать за рулём",
            onDismiss = onDismiss,
        )
        NavigationAppChoices(apps = apps, compact = compactLayout, onSelect = onSelect)
    }
}

/**
 * The navigators this car has, drawn one way.
 *
 * There are two doors to this choice - the cluster's settings panel holds it inline, and a feature
 * waiting on it opens this sheet - and until now each door drew its own grid: four columns of
 * [dev.denza.apps.ui.components.DenzaTileGrid] in the panel against three lazy ones in the sheet,
 * with different gaps and a different empty state. The doors are a real difference and worth
 * keeping; the choice behind them is one thing and is drawn here, once.
 */
@Composable
internal fun NavigationAppChoices(
    apps: List<NavigationAppChoice>,
    compact: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (apps.isEmpty()) {
        Text(
            text = "Поддерживаемые навигаторы не найдены",
            style = MaterialTheme.typography.bodyLarge,
            color = DenzaColors.Muted,
            modifier = modifier,
        )
        return
    }
    DenzaAppGrid(
        items = apps,
        key = NavigationAppChoice::packageName,
        compact = compact,
        modifier = modifier,
        columns = DenzaMetrics.Component.NAVIGATION_PICKER_COLUMNS,
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
        emptyText = "Приложения не найдены",
    ) { app ->
        DenzaAppTile(
            label = app.label,
            selected = false,
            onClick = { onInstall(app.packageName) },
            icon = app.icon,
            iconKey = app.packageName,
            // An application whose APK cannot be sent across - a split package, an unreadable
            // source - is shown and is not choosable. Hiding it would leave the driver hunting a
            // tile that is on the car and not in the list; accepting the tap and printing the
            // reason in amber was the other half of the same mistake. The list already sorts the
            // installable first, so the quiet ones sit at the end where they read as a footnote.
            enabled = app.installable,
        )
    }
}

/** "12 приложений с головного устройства" - agreed the way Russian agrees it. */
private fun subtitleFor(count: Int): String =
    "${DashboardTiles.applications(count)} с головного устройства"
