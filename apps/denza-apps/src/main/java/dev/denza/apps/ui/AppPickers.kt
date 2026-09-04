package dev.denza.apps.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.denza.apps.NavigationAppChoice
import dev.denza.apps.SIMULCAST_MAX_SELECTED
import dev.denza.apps.SimulcastAppChoice
import dev.denza.apps.design.DenzaColors
import dev.denza.apps.design.DenzaMetrics
import dev.denza.apps.feature.fse.FseInstallApp
import dev.denza.apps.ui.components.DenzaAppChooser
import dev.denza.apps.ui.components.DenzaAppChooserSheet
import dev.denza.apps.ui.components.DenzaAppGrid
import dev.denza.apps.ui.components.DenzaAppTile
import dev.denza.apps.ui.components.DenzaNote
import dev.denza.apps.ui.components.DenzaPrimaryButton
import dev.denza.apps.ui.dashboard.DashboardTiles

/**
 * The three lists of applications this app asks the driver to choose from.
 *
 * They were three dialogs and three tiles; they are three calls now, because once the chooser and
 * the tile are shared there is nothing left of a picker but its words and how many fit in a row.
 *
 * All three are the whole sheet, and that is what a tile's own press earns: the driver pressed a
 * feature that is waiting on this answer, so there is nothing behind the page to go back to. The
 * same chooser drawn inside a settings panel keeps a way back instead - see [SimulcastAppChooser].
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
    DenzaAppChooserSheet(
        title = SIMULCAST_CHOICE_TITLE,
        subtitle = simulcastChooserSubtitle(selectedCount),
        items = apps,
        key = SimulcastAppChoice::packageName,
        compact = compactLayout,
        onDismiss = onDismiss,
        emptyText = "Приложения не найдены",
        // Choosing several has no closing tap of its own, so the page needs a way out that reads
        // as "finished" rather than as "abandoned". The single-choice pickers below have none:
        // there, the tap that chooses is the tap that closes.
        footer = {
            DenzaPrimaryButton(
                text = "Готово",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
                    .height(DenzaMetrics.Component.PRIMARY_HEIGHT),
            )
        },
    ) { app -> SimulcastAppTile(app = app, onToggle = onToggle) }
}

/**
 * The projection's choice inside the panel it belongs to, with the way back to it.
 *
 * The panel used to hang the same grid under its switch, capped, inside a column that scrolled -
 * the milder half of the defect [dev.denza.apps.ui.components.DenzaAppChooser] describes. It is
 * the same page as [AppPickerDialog] and shares its tile, because the projection carries the same
 * six applications whichever door was used to pick them.
 */
@Composable
internal fun ColumnScope.SimulcastAppChooser(
    apps: List<SimulcastAppChoice>,
    compact: Boolean,
    selectedCount: Int,
    onToggle: (String) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    DenzaAppChooser(
        title = SIMULCAST_CHOICE_TITLE,
        subtitle = simulcastChooserSubtitle(selectedCount),
        items = apps,
        key = SimulcastAppChoice::packageName,
        compact = compact,
        onDismiss = onDismiss,
        onBack = onBack,
        emptyText = "Приложения не найдены",
    ) { app -> SimulcastAppTile(app = app, onToggle = onToggle) }
}

@Composable
private fun SimulcastAppTile(app: SimulcastAppChoice, onToggle: (String) -> Unit) {
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

/**
 * How many the projection carries and how many are picked, in the chooser's own header.
 *
 * The count lives in the subtitle rather than beside the grid because the grid is the page now:
 * there is no line above it and no line below it, and the one thing the driver cannot see by
 * looking at the tiles is how much of the allowance is left.
 */
internal fun simulcastChooserSubtitle(selectedCount: Int): String =
    "Можно выбрать до $SIMULCAST_MAX_SELECTED · выбрано $selectedCount"

/**
 * What the panel's row says beside the icons.
 *
 * Nothing, when there are icons: they are the answer, and a count repeating them in words is the
 * kind of line that gets read once and never again. The empty case is the only one with anything
 * to say, and it says it as a state rather than as a complaint.
 */
internal fun simulcastChoiceValue(selected: List<SimulcastAppChoice>): String =
    if (selected.isEmpty()) "Ничего не выбрано" else ""

/** What goes on the driver's screen. One at a time, so choosing closes the sheet. */
@Composable
internal fun NavigationPickerDialog(
    apps: List<NavigationAppChoice>,
    compactLayout: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    DenzaAppChooserSheet(
        title = "Экран водителя",
        subtitle = "Что показывать за рулём",
        items = apps,
        key = NavigationAppChoice::packageName,
        compact = compactLayout,
        onDismiss = onDismiss,
        emptyText = "Поддерживаемые навигаторы не найдены",
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

/**
 * The navigators this car has, drawn inline for the one panel that holds them inline.
 *
 * There are two doors to this choice - the cluster's settings panel holds it under a heading, and
 * a feature waiting on it opens [NavigationPickerDialog] - and until now each door drew its own
 * grid: four columns of [dev.denza.apps.ui.components.DenzaTileGrid] in the panel against three
 * lazy ones in the sheet, with different gaps and a different empty state.
 *
 * This one stays bounded, because here the grid genuinely is one child of a panel that scrolls.
 * There are four navigators at most - one row, sometimes two - so the cap is never reached and the
 * nesting never bites; a page for a choice that fits under its own heading would be a second
 * surface asking one question.
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

/**
 * Which application to put across on the passenger's screen.
 *
 * This is the whole of what «Экран справа» has to show, so it is what both gestures on the tile
 * open. The tile used to answer a short press with this chooser and a long press with a settings
 * panel that held one sentence and a button opening this chooser - the same tile leading to two
 * screens, one of them empty. The sentence came along as the chooser's foot.
 */
@Composable
internal fun FseInstallerPickerDialog(
    apps: List<FseInstallApp>,
    compactLayout: Boolean,
    onInstall: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    DenzaAppChooserSheet(
        title = "Экран справа",
        subtitle = subtitleFor(apps.size),
        items = apps,
        key = FseInstallApp::packageName,
        compact = compactLayout,
        onDismiss = onDismiss,
        emptyText = "Приложения не найдены",
        footer = { DenzaNote(FSE_INSTALL_HELP) },
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

/**
 * The one thing about this list a driver cannot see by looking at it.
 *
 * It used to say what the tile's name already says - that the application goes to the passenger's
 * screen. The owner, on the car: the foot of the chooser has room for information, and that was
 * not information. What is: why some tiles are grey. An application built from several APKs cannot
 * be sent across in one piece, and the list shows it rather than hiding it - a tile that is on the
 * car and not in the list is a tile the driver goes hunting for.
 *
 * Owned here rather than by the panels' help table because the chooser is the only surface left
 * that says it; the table still reads it for the tile, so the two can never drift.
 */
internal const val FSE_INSTALL_HELP =
    "Установить можно только приложения из одного APK. " +
        "Собранные из нескольких (split APK) показаны серыми."

/** One question, two doors: the panel's row and the tile's press must not name it differently. */
private const val SIMULCAST_CHOICE_TITLE = "Что транслировать"
