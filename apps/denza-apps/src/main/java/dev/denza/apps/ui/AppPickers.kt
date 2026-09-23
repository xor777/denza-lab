package dev.denza.apps.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.denza.apps.NavigationAppChoice
import dev.denza.apps.SIMULCAST_MAX_SELECTED
import dev.denza.apps.SimulcastAppChoice
import dev.denza.apps.design.DenzaIcons
import dev.denza.apps.design.DenzaMetrics
import dev.denza.apps.feature.fse.FseInstallApp
import dev.denza.apps.ui.components.DenzaAppChooser
import dev.denza.apps.ui.components.DenzaAppChooserSheet
import dev.denza.apps.ui.components.DenzaAppTile
import dev.denza.apps.ui.components.DenzaChoiceIcon
import dev.denza.apps.ui.components.DenzaNote
import dev.denza.apps.ui.components.DenzaPrimaryButton
import dev.denza.apps.ui.dashboard.DashboardTiles

/**
 * The three lists of applications this app asks the driver to choose from.
 *
 * They were three dialogs and three tiles; they are three calls now, because once the chooser and
 * the tile are shared there is nothing left of a picker but its words.
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

/**
 * What goes on the driver's screen, opened straight from the tile. One at a time, so choosing
 * closes the sheet.
 *
 * The tile opens this when it has nothing it can put across - the chosen application has gone from
 * the car. It is the same page as [NavigationAppChooser], without the way back.
 */
@Composable
internal fun NavigationPickerDialog(
    apps: List<NavigationAppChoice>,
    compactLayout: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    DenzaAppChooserSheet(
        title = "Экран водителя",
        subtitle = NAVIGATION_CHOICE_TITLE,
        items = apps,
        key = NavigationAppChoice::packageName,
        compact = compactLayout,
        onDismiss = onDismiss,
        emptyText = NAVIGATION_CHOICES_LOADING,
        section = ::navigationChoiceSection,
    ) { app -> NavigationChoiceTile(app, onSelect) }
}

/**
 * «Что показывать» inside the driver's-screen panel, with the way back to it.
 *
 * It used to be a grid of the navigators hung in the panel itself, under a heading, because there
 * were never more than six and one row usually held them. The choice is anything the car can open
 * now - fifty-odd tiles on this car - and a grid of the whole catalog belongs on a page of its own,
 * the one every other chooser in the app already is. The panel says what is chosen on a row.
 *
 * Two groups in one grid: the instruments first, and then every application by name. First,
 * because they are one tile that would otherwise stand somewhere after «Яндекс Музыка»; and under
 * a heading of their own, because they are a different kind of answer - drawn by this app for the
 * whole panel, not an application's picture put there - and the page should say so before the
 * finger finds out.
 */
@Composable
internal fun ColumnScope.NavigationAppChooser(
    apps: List<NavigationAppChoice>,
    compact: Boolean,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    DenzaAppChooser(
        title = NAVIGATION_CHOICE_TITLE,
        subtitle = "",
        items = apps,
        key = NavigationAppChoice::packageName,
        compact = compact,
        onDismiss = onDismiss,
        onBack = onBack,
        emptyText = NAVIGATION_CHOICES_LOADING,
        section = ::navigationChoiceSection,
    ) { app -> NavigationChoiceTile(app, onSelect) }
}

@Composable
private fun NavigationChoiceTile(app: NavigationAppChoice, onSelect: (String) -> Unit) {
    DenzaAppTile(
        label = app.label,
        selected = app.selected,
        onClick = { onSelect(app.packageName) },
        icon = app.icon,
        iconKey = app.packageName,
        glyph = if (app.instruments) DenzaIcons.InstrumentsGlyph else null,
    )
}

/** The group a choice is drawn under: this app's instruments, or the car's applications. */
internal fun navigationChoiceSection(choice: NavigationAppChoice): String =
    if (choice.instruments) NAVIGATION_INSTRUMENTS_SECTION else NAVIGATION_APPLICATIONS_SECTION

/**
 * The chosen answer on the panel's row: the application's own icon, or the instruments' glyph.
 */
internal fun navigationChoiceIcon(choice: NavigationAppChoice): DenzaChoiceIcon = DenzaChoiceIcon(
    key = choice.packageName,
    label = choice.label,
    drawable = choice.icon,
    glyph = if (choice.instruments) DenzaIcons.InstrumentsGlyph else null,
)

/** One question, two doors: the panel's row and the page it opens must not name it differently. */
internal const val NAVIGATION_CHOICE_TITLE = "Что показывать"

internal const val NAVIGATION_INSTRUMENTS_SECTION = "Функции приборов"
internal const val NAVIGATION_APPLICATIONS_SECTION = "Приложения"

/**
 * The instruments are always there, so the page is empty only for the moment before the car's
 * catalog is read; a wait, not a verdict.
 */
private const val NAVIGATION_CHOICES_LOADING = "Ищем приложения…"

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
    val installable = fseChooserApps(apps)
    DenzaAppChooserSheet(
        title = "Экран справа",
        subtitle = subtitleFor(installable.size),
        items = installable,
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
        )
    }
}

/**
 * Only what can actually be put across.
 *
 * An application whose APK cannot be sent in one piece - a split package, an unreadable source -
 * used to be shown and greyed, on the argument that hiding it leaves the driver hunting a tile that
 * is on the car and not in the list. On the car the owner saw no grey tile at all under a sentence
 * promising some, and a rule the screen cannot show is a sentence that lies. So the list holds what
 * the finger can use, and the sentence at its foot says the rest is not here - which is the whole
 * answer to "where is my application", without a dead tile to find it under.
 */
internal fun fseChooserApps(apps: List<FseInstallApp>): List<FseInstallApp> =
    apps.filter(FseInstallApp::installable)

/** "12 приложений с головного устройства" - agreed the way Russian agrees it. */
private fun subtitleFor(count: Int): String =
    "${DashboardTiles.applications(count)} с головного устройства"

/**
 * The one thing about this list a driver cannot see by looking at it: that it is not everything.
 *
 * It used to say what the tile's name already says - that the application goes to the passenger's
 * screen - and then, for one build, that split packages were the grey tiles, in words the owner
 * read back as written for somebody else ("собранные из нескольких split APK"). Plain words, and
 * only the fact a driver needs: what is here can be installed, and what cannot is not here. See
 * [fseChooserApps] for why they are left out rather than greyed.
 *
 * Owned here rather than by the panels' help table because the chooser is the only surface left
 * that says it; the table still reads it for the tile, so the two can never drift.
 */
internal const val FSE_INSTALL_HELP =
    "Показаны только приложения, которые можно поставить на экран справа. " +
        "Несовместимые в список не входят."

/** One question, two doors: the panel's row and the tile's press must not name it differently. */
private const val SIMULCAST_CHOICE_TITLE = "Что транслировать"
